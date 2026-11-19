"""Memory-only regressions for shared shutdown publication and recovery."""

from contextlib import contextmanager
from copy import deepcopy
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import run_picker_adapter as runner
from test_picker_runner import MemoryLock, MemoryPath


ORIGINALS = [[11, 101], [22, 202]]
RESERVED_KIB = 4194304
QUIT = (runner.CONSOLE, "quit", "--index", "2")
IS_RUNNING = (runner.CONSOLE, "isrunning", "--index", "2")
FINAL = "final-manifest.json"


class NamedMemoryPath(MemoryPath):
    def __init__(self, name, value=None):
        super().__init__(value)
        self.name = name


class MemoryTranscript(NamedMemoryPath):
    """In-process stand-in for the existing transcript; the synthetic descriptor is never sent to the OS."""

    def __init__(self, events, fail_barrier):
        super().__init__("commands.jsonl")
        self.events = events
        self.fail_barrier = fail_barrier
        self.entries = []
        self.handle = None

    @contextmanager
    def open(self, mode):
        if mode not in ("r+b", "rb+") or self.handle is not None:
            raise AssertionError("Shutdown must open the existing transcript without creating or truncating it")
        handle = self.handle = object()
        try:
            yield SimpleNamespace(fileno=lambda: handle, flush=lambda: None)
        finally:
            self.handle = None

    def fsync(self, descriptor):
        if self.handle is None or descriptor is not self.handle:
            raise AssertionError("Barrier did not target the open transcript")
        if self.fail_barrier:
            self.events.append(("barrier-failed", self))
            raise OSError("synthetic transcript barrier refused")
        self.events.append(("barrier", self, tuple(self.entries)))


class ShutdownFixture:
    # Share only the pure guards and shutdown bookkeeping; inherited operational I/O is not reachable via self.
    owned = runner.PickerAdapterRun.owned
    exact = runner.PickerAdapterRun.exact

    def __init__(self, *, slot, fail_final=False, fail_release=False, replace_record=False, replace_slot=False,
                 fail_blockers=False, fail_transcript_barrier=False):
        self.generation = "assigned-generation"
        self.prefix = "picker-adapter-shutdown-draft"
        self.record = NamedMemoryPath("record", {
            "owner": runner.OWNER, "generation": self.generation, "claim_generation": self.generation,
            "state": "running", "keep_running": False, "handoff_to": None,
            "shutdown_verified": False, "runtime_processes": deepcopy(ORIGINALS),
            "shutdown_processes": [], "cleanup_attempt": None, "reservedKiB": RESERVED_KIB,
        })
        self.slot = NamedMemoryPath("slot", deepcopy(slot))
        self.lock = MemoryLock()
        self.events = []
        self.transcript = MemoryTranscript(self.events, fail_transcript_barrier)
        self.raw = SimpleNamespace(glob=lambda pattern: ())
        self.files = {}
        self.quit_seen = False
        self.fail_final = fail_final
        self.fail_release = fail_release
        self.replace_record = replace_record
        self.replace_slot = replace_slot
        self.fail_blockers = fail_blockers

    def path(self, name):
        return self.files.setdefault(name, NamedMemoryPath(name))

    def save(self, path, value, *, durable=False):
        if not self.lock.held:
            raise AssertionError("Shared publication attempted outside the fake allocation lock")
        # Snapshot per write so later updates to the record cannot erase an earlier transient release.
        snapshot = deepcopy(value)
        if self.fail_blockers and value.get("state") in ("shutdown_failed", "released-with-unresolved-shutdown"):
            self.events.append(("write-failed", path.name, snapshot))
            raise OSError("synthetic blocker write refused")
        if self.fail_final and path.name == FINAL:
            self.events.append(("write-failed", path.name, snapshot))
            raise OSError("synthetic final manifest write refused")
        self.events.append(("write", path.name, snapshot))
        path.value = deepcopy(snapshot)
        if path.name == "slot" and value["state"] == "released" and self.fail_release:
            # The atomic replace happened, but its result was uncertain to the caller. Unwind must check both actual records.
            if self.replace_record:
                self.record.value.update(owner="new-owner", generation="new-generation")
            if self.replace_slot:
                self.slot.value.update(owner="new-owner", generation="new-generation", state="held")
            raise OSError("synthetic slot release acknowledgement lost")
        if durable:
            self.events.append(("flushed", path.name))

    def evidence(self, path):
        if path is self.transcript:
            self.events.append(("transcript-hash", tuple(path.entries)))
        return {"path": path.name, "bytes": 0, "sha256": "a" * 64}

    def inventory(self):
        row = ["2", runner.NAME, "0", "0", "0", "-1", "-1"] if self.quit_seen else ["2", runner.NAME, "0", "0", "1", "11", "22"]
        self.events.append(("inventory", row))
        self.transcript.entries.append(("list2",))
        return [row]

    def process(self, pid):
        # Both process objects remain openable with the original creation times after quit, but are now signaled.
        state = {"pid": pid, "start": {11: 101, 22: 202}[pid], "running": not self.quit_seen,
                 "wait": 0 if self.quit_seen else 258, "exit": 0 if self.quit_seen else 259}
        self.events.append(("process", state))
        return state

    def command(self, args, timeout=15, allowed=(0,)):
        argv = tuple(args)
        self.events.append(("command", argv))
        self.transcript.entries.append(argv[1:])
        if argv == QUIT and not self.quit_seen:
            self.quit_seen = True
            return ""
        if argv == IS_RUNNING and self.quit_seen:
            return "stop"
        raise AssertionError(f"Unexpected synthetic command: {argv!r}")

    def shutdown(self):
        def unexpected_sleep(_seconds):
            raise AssertionError("Matching synthetic exit observations should not poll again")

        # Keep even the success path inert when a broken guard lets it through: file handles, fsync, source-path resolution, hashing, time, UUID and reporting are all local doubles.
        with patch.multiple(
            runner, create=True,
            os=SimpleNamespace(fsync=self.transcript.fsync), ctypes=SimpleNamespace(), subprocess=SimpleNamespace(),
            Path=lambda _path: SimpleNamespace(resolve=lambda: NamedMemoryPath("runner-source")),
            sha=lambda _path: "a" * 64, uuid=SimpleNamespace(uuid4=lambda: "synthetic-cleanup-token"),
            now=lambda: "2026-11-19T00:00:00Z", time=SimpleNamespace(monotonic=lambda: 0, sleep=unexpected_sleep),
            print=lambda *_args, **_kwargs: None,
        ):
            runner.PickerAdapterRun.shutdown(self)


class PickerShutdownFenceTest(unittest.TestCase):
    def test_durable_evidence_flushes_bytes_before_write_through_replace(self):
        events = []

        @contextmanager
        def open_temporary(mode, *, encoding):
            events.append(("open", mode, encoding))
            yield SimpleNamespace(write=lambda text: events.append(("write", text)),
                                  flush=lambda: events.append(("flush",)), fileno=lambda: 17)
            events.append(("close",))

        temporary = SimpleNamespace(open=open_temporary)
        target = SimpleNamespace(name=FINAL, with_name=lambda name: temporary)

        def replace(source, destination, flags):
            events.append(("replace", source, destination, flags))
            return True

        fake_os = SimpleNamespace(fsync=lambda fd: events.append(("fsync", fd)))
        fake_ctypes = SimpleNamespace(WinDLL=lambda *_args, **_kwargs: SimpleNamespace(MoveFileExW=replace))
        run = SimpleNamespace(generation="test-generation")
        with patch.multiple(runner, os=fake_os, ctypes=fake_ctypes):
            runner.PickerAdapterRun.save(run, target, {"usageReleased": False}, durable=True)
        self.assertEqual(events, [
            ("open", "w", "utf-8"), ("write", '{\n  "usageReleased": false\n}\n'),
            ("flush",), ("fsync", 17), ("close",), ("replace", str(temporary), str(target), 0x9),
        ])

    def test_other_generation_slot_is_never_overwritten_or_used_to_quit(self):
        # Same session token, independently held generation: checking owner alone must not authorize an overwrite.
        other = {"owner": runner.OWNER, "generation": "other-generation", "state": "held", "operation": "unrelated allocation"}
        run = ShutdownFixture(slot=other)
        try:
            run.shutdown()
        except (AssertionError, RuntimeError, OSError):
            pass
        quits = [event for event in run.events if event == ("command", QUIT)]
        slot_writes = [event for event in run.events if event[:2] == ("write", "slot")]
        self.assertEqual((quits, slot_writes), ([], []), "A foreign-generation hold must refuse quit and all slot writes, even on unwind")
        self.assertEqual(other, run.slot.value)
        self.assertEqual(runner.OWNER, run.record.value["owner"])
        self.assertFalse(run.lock.held)

    def test_final_manifest_failure_never_publishes_release_after_confirmed_exit(self):
        run = ShutdownFixture(slot={"state": "released", "generation": "prior-generation"}, fail_final=True)
        with self.assertRaisesRegex(OSError, "synthetic final manifest write refused"):
            run.shutdown()

        events = run.events
        quit_at = events.index(("command", QUIT))
        stopped_at = events.index(("command", IS_RUNNING))
        proof_at, proof = next((i, event[2]) for i, event in enumerate(events) if event[:2] == ("write", "pre-shutdown.json"))
        failure_at = next(i for i, event in enumerate(events) if event[:2] == ("write-failed", FINAL))
        self.assertLess(proof_at, quit_at)
        self.assertLess(quit_at, stopped_at)
        self.assertLess(stopped_at, failure_at)
        self.assertEqual([[p["pid"], p["start"]] for p in proof["processes"]], ORIGINALS)
        self.assertTrue(all(p["running"] for p in proof["processes"]))
        self.assertEqual(run.files["pre-shutdown.json"].value, proof)
        self.assertIn(("inventory", ["2", runner.NAME, "0", "0", "0", "-1", "-1"]), events[quit_at:stopped_at])
        exited = [event[1] for event in events[quit_at:stopped_at] if event[0] == "process"]
        self.assertEqual(
            [(p["pid"], p["start"], p["running"], p["wait"], p["exit"]) for p in exited],
            [(11, 101, False, 0, 0), (22, 202, False, 0, 0)],
        )

        writes = [(i, event[1], event[2]) for i, event in enumerate(events) if event[0] == "write"]
        releases = [(i, name, value.get("state")) for i, name, value in writes
                    if (name == "record" and (value["owner"] is None or value["state"] == "stopped-released"))
                    or (name == "slot" and value["state"] == "released")
                    or value.get("usageReleased") is True]
        self.assertEqual(releases, [], "The write log must contain no transient release, even if the handler restores ownership")
        self.assertEqual(run.record.value["state"], "shutdown_failed")
        self.assertEqual(run.record.value["owner"], runner.OWNER)
        self.assertEqual(run.record.value["generation"], run.generation)
        self.assertEqual(run.record.value["shutdown_processes"], ORIGINALS)
        self.assertEqual(run.record.value["reservedKiB"], RESERVED_KIB)
        self.assertIn(run.slot.value["state"], ("held", "released-with-unresolved-shutdown"))
        self.assertEqual(run.slot.value["generation"], run.generation)
        self.assertFalse(run.lock.held)

    def test_only_verified_manifest_allows_release_for_released_or_own_held_slot(self):
        for slot in (
            {"state": "released", "generation": "prior-generation"},
            {"state": "held", "owner": runner.OWNER, "generation": "assigned-generation"},
        ):
            with self.subTest(slot=slot):
                run = ShutdownFixture(slot=slot)
                if slot["state"] == "held":
                    run.record.value["state"] = "launching"  # A partial claim has captured both original process identities but has not released its allocation slot.
                run.shutdown()
                flushed_manifest = run.events.index(("flushed", FINAL))
                receipt = run.files["shutdown.json"].value
                self.assertLess(run.events.index(("flushed", "shutdown.json")), flushed_manifest)
                self.assertTrue(receipt["ownedShutdownVerified"])
                self.assertEqual(receipt["isrunning"], "stop")
                self.assertFalse(receipt["usageReleased"])
                manifest = run.files[FINAL].value
                self.assertEqual(manifest["recordSnapshot"]["owner"], runner.OWNER)
                self.assertEqual(manifest["recordSnapshot"]["reservedKiB"], RESERVED_KIB)
                self.assertEqual(manifest["stage"], "verified-before-release")
                self.assertEqual([item["path"] for item in manifest["evidence"]], ["commands.jsonl", "pre-shutdown.json", "shutdown.json"])
                writes = [(i, event[1], event[2]) for i, event in enumerate(run.events) if event[0] == "write"]
                release_order = [(i, name) for i, name, value in writes if
                                 (name == "record" and value["owner"] is None) or
                                 (name == "slot" and value["state"] == "released")]
                self.assertEqual([name for _, name in release_order], ["record", "slot"])
                self.assertTrue(all(i > flushed_manifest for i, _ in release_order))
                self.assertEqual(run.record.value["state"], "stopped-released")
                self.assertEqual(run.record.value["reservedKiB"], 0)
                self.assertEqual(run.slot.value["state"], "released")
                self.assertFalse(run.lock.held)

    def test_shutdown_flushes_exact_complete_transcript_before_hashing_and_releasing(self):
        run = ShutdownFixture(slot={"state": "released"})
        run.raw.glob = lambda _pattern: [SimpleNamespace(name="ancillary.json", suffix=".json")]
        run.shutdown()

        entries = (("list2",), ("quit", "--index", "2"), ("list2",), ("isrunning", "--index", "2"))
        barriers = [event for event in run.events if event[0] == "barrier"]
        self.assertEqual(barriers, [("barrier", run.transcript, entries)], "Finalization must flush the actual complete transcript once")
        barrier_at = run.events.index(barriers[0])
        self.assertLess(run.events.index(("command", IS_RUNNING)), barrier_at)
        self.assertLess(barrier_at, run.events.index(("transcript-hash", entries)))
        self.assertLess(barrier_at, run.events.index(("flushed", FINAL)))
        manifest = run.files[FINAL].value
        self.assertEqual([item["path"] for item in manifest["evidence"]], ["commands.jsonl", "pre-shutdown.json", "shutdown.json"])
        self.assertEqual([item["path"] for item in manifest["ancillaryEvidence"]], ["ancillary.json"])
        self.assertEqual(run.record.value["state"], "stopped-released")
        self.assertEqual(run.slot.value["state"], "released")

    def test_transcript_barrier_failure_leaves_no_manifest_or_release_and_records_blocker(self):
        run = ShutdownFixture(slot={"state": "released"}, fail_transcript_barrier=True)
        with self.assertRaisesRegex(OSError, "synthetic transcript barrier refused"):
            run.shutdown()

        self.assertLess(run.events.index(("command", IS_RUNNING)), run.events.index(("barrier-failed", run.transcript)))
        for event in run.events:
            if event[0] in ("write", "write-failed"):
                _, name, value = event
                self.assertNotEqual(name, FINAL, "Authoritative manifest must not even be attempted")
                self.assertFalse(value.get("usageReleased"))
                if name == "slot":
                    self.assertNotEqual(value["state"], "released")
                if name == "record":
                    self.assertEqual(value["owner"], runner.OWNER)
        self.assertEqual(run.record.value["state"], "shutdown_failed")
        self.assertEqual(run.record.value["shutdown_processes"], ORIGINALS)
        self.assertEqual(run.record.value["reservedKiB"], RESERVED_KIB)
        self.assertEqual(run.slot.value["state"], "released-with-unresolved-shutdown")
        self.assertFalse(run.lock.held)

    def test_uncertain_release_restores_reservation_and_keeps_manifest(self):
        run = ShutdownFixture(slot={"state": "released"}, fail_release=True)
        with self.assertRaisesRegex(OSError, "slot release acknowledgement lost"):
            run.shutdown()
        self.assertEqual(run.record.value["owner"], runner.OWNER)
        self.assertEqual(run.record.value["state"], "shutdown_failed")
        self.assertEqual(run.record.value["reservedKiB"], RESERVED_KIB)
        self.assertEqual(run.record.value["shutdown_processes"], ORIGINALS)
        self.assertEqual(run.slot.value["state"], "released-with-unresolved-shutdown")
        self.assertFalse(run.files["shutdown.json"].value["usageReleased"])
        self.assertEqual(run.files[FINAL].value["stage"], "verified-before-release")
        self.assertFalse(run.lock.held)

    def test_uncertain_release_does_not_overwrite_a_replaced_slot_or_record(self):
        for replace_record, replace_slot in ((True, False), (False, True)):
            with self.subTest(replace_record=replace_record, replace_slot=replace_slot):
                run = ShutdownFixture(slot={"state": "released"}, fail_release=True,
                                      replace_record=replace_record, replace_slot=replace_slot)
                with self.assertRaisesRegex(RuntimeError, "reconciliation") as error:
                    run.shutdown()
                self.assertIsInstance(error.exception.__cause__, OSError)
                if replace_record:
                    self.assertEqual(run.record.value["owner"], "new-owner")
                    self.assertEqual(run.slot.value["state"], "released-with-unresolved-shutdown")
                else:
                    self.assertEqual(run.slot.value["owner"], "new-owner")
                    self.assertEqual(run.record.value["state"], "shutdown_failed")
                    self.assertEqual(run.record.value["reservedKiB"], RESERVED_KIB)
                self.assertFalse(run.lock.held)

    def test_failed_blocker_publications_retain_the_allocation_lock(self):
        run = ShutdownFixture(slot={"state": "released"}, fail_final=True, fail_blockers=True)
        with self.assertRaisesRegex(RuntimeError, "allocation lock retained: True") as error:
            run.shutdown()
        self.assertRegex(str(error.exception.__cause__), "synthetic final manifest")
        self.assertEqual([event[1] for event in run.events if event[0] == "write-failed"],
                         [FINAL, "slot", "record"])
        self.assertEqual(run.slot.value["state"], "held")
        self.assertEqual(run.record.value["owner"], runner.OWNER)
        self.assertEqual(run.record.value["reservedKiB"], RESERVED_KIB)
        self.assertTrue(run.lock.held)
