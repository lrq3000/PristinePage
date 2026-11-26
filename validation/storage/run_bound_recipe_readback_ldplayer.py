"""Read the one exact spent bound-edit witness, with no picker, app launch, install or write."""

import json
import os
from pathlib import Path
import uuid

import run_library_folder_picker_ldplayer as checks


WRITER = "picker-adapter-library-tree-df84a23cdb96"
WRITER_SHA256 = "b07ea7c1fe50c8d3a81a813e02deb716db192780560d34a2bedf7ff2b8e98e1f"
DETACHED = "picker-adapter-library-tree-5e7909463a3e"


def readback(run, adb, ns, files, attempt, catalog_identity, *, advanced=False):
    operation, folder = attempt["operation"], attempt["folder"]
    left, right = (f"/sdcard/Documents/{folder}/{name}" for name in checks.independent_fixture_children(folder))
    marker_leaf, catalog_leaf = f"generations/{operation}.complete", f"catalogs/{operation}.sqlite"
    source_marker, = (leaf for leaf in files if leaf.startswith("generations/"))
    read = checks.read_selected_history_record

    def shell(*args):
        return adb("shell", *args)

    def private(*args):
        return shell("run-as", checks.APP, *args)

    checks.recheck_target(run, ns)
    predecessor = read(shell, f"{left}/{source_marker}", 256)
    successor = read(shell, f"{left}/{marker_leaf}", 256)
    prior, next_marker = predecessor.splitlines(), successor.splitlines()
    marker_digest = checks.sha256(successor.encode("ascii")).hexdigest()
    length, digest = catalog_identity
    checks.need(len(prior) == len(next_marker) == 7 and
                next_marker == [prior[0], prior[1], operation, prior[2], prior[4], str(length), digest],
                "The exact bound successor catalog changed")
    staging = f"merge-staging-{operation}"
    checks.verify_unchanged_settings_destination(adb, right, files)
    checks.verify_unchanged_settings_destination(adb, left, files | {marker_leaf: marker_digest, catalog_leaf: digest}, backup_name=staging)
    checks.need(not shell("ls", "-A", "-1", f"{left}/{staging}").strip(), "The bound recipe stage changed")
    checks.need(set(attempt["before"]) == set(files) | {"objects", "catalogs", "generations"}, "Unexpected fixture inventory")
    for leaf, identity in attempt["before"].items():
        actual = shell("stat", "-c", "%d:%i:%s", f"{left}/{leaf}").strip()
        checks.need((actual if "/" in leaf else actual.rsplit(":", 1)[0]) == identity, "A retained bound fixture node changed")
    scope = f"files/library-selected-editor-{operation}"
    stage = f"{scope}/catalog-recipe-attempts-v1/{operation}"
    receipt = checks.read_bound_recipe_evidence(adb, scope, stage, left, predecessor, successor, digest, advanced=advanced)
    spend = read(private, f"{scope}/catalog-revision-spends/{operation}.spent", 1024)
    checks.need(spend == "pristinepage-catalog-revision-spent-v1\n" + predecessor + successor, "The exact bound revision spend changed")
    names = set(private("ls", "-A", "-1", stage).splitlines())
    fixed = {"catalog.sqlite", "recipe-edit.intent", "recipe-edit.complete", "recipe-edit.catalog-owner"}
    moves = checks.verify_library_move_spends(adb, stage, names - fixed,
        {catalog_leaf: (length, digest), marker_leaf: (len(successor), marker_digest)})
    return dict(catalogSha256=digest, catalogBytes=length, moveSpends=moves, receiptSha256=receipt)


def run_readback():
    root = checks.ROOT
    checks.need(Path.cwd().resolve() == root and not any(os.environ.get(key) for key in checks.LOCAL_ADB_OVERRIDES),
                "Use the admitted worktree and normal scoped transport")
    history = root / "validation/raw/storage"
    writer = history / WRITER
    manifest = writer / f"{WRITER}-final-manifest.json"
    writer_anchor = checks.settings_run_anchors(writer, WRITER_SHA256)
    detached_anchor = checks.settings_run_anchors(history / DETACHED, checks.DETACHED_RECIPE_PASSED)
    artifacts = json.loads(writer_anchor("artifacts.json"))
    claim = json.loads(writer_anchor("claim.json"))
    shutdown = json.loads(writer_anchor("shutdown.json"))
    prior = json.loads(detached_anchor("artifacts.json"))
    guest = artifacts["guest"]
    checks.need(guest == claim["name"] == prior["guest"] and artifacts["selectedBoundRecipe"] is True and
                shutdown["ownedShutdownVerified"] is True and shutdown["isrunning"] == "stop" and
                shutdown["originalProcesses"] == claim["runtime_processes"] and not shutdown["storageDeleted"] and
                checks.read_results(writer_anchor("selected-recipe-instrumentation.txt"), (checks.BOUND_RECIPE_CASE,)) ==
                ([checks.BOUND_RECIPE_CASE], []), "The exact bound test did not complete and stop")
    manifest_bytes = manifest.read_bytes()
    checks.need(checks.sha256(manifest_bytes).hexdigest() == WRITER_SHA256, "The bound writer manifest changed")
    snapshot = json.loads(manifest_bytes)["recordSnapshot"]
    attempt = json.loads(writer_anchor("selected-recipe-attempt.json"))
    folder, operation = attempt["folder"], attempt["operation"]
    checks.independent_fixture_children(folder)
    checks.need(uuid.UUID(operation).__str__() == operation and
                json.loads((history / f"settings-retry-spent-{checks.DETACHED_RECIPE_PASSED}.json").read_bytes()) ==
                {"operation": operation, "destination": folder, "kind": "selected-recipe-edit"}, "The exact bound admission was not spent")
    trace = [json.loads(line) for line in writer_anchor("commands.jsonl").splitlines()]
    require_pair = {package: proof["sha256"] for package, proof in artifacts["proof"].items()}
    checks.need(set(require_pair) == {checks.APP, checks.TEST} and
                all(checks.sha(writer / f"{package}.apk") == digest for package, digest in require_pair.items()),
                "The retained exact APK pair changed")
    checks.require_bound_recovery_writer(trace, claim["serial"], folder, operation)
    files = artifacts["fixture"]
    source_catalog, = (leaf for leaf in files if leaf.startswith("catalogs/"))
    original_catalog = writer / "synthetic-library" / source_catalog
    published_catalog = writer / "selected-recipe-catalog.sqlite"
    checks.need(checks.sha(original_catalog) == files[source_catalog] and
                0 < published_catalog.stat().st_size <= 2 * 1024 * 1024, "The retained catalog fixtures changed")
    recipe = checks.verify_selected_recipe_catalog(published_catalog, original_catalog, checks.synthetic_scan()[0])
    catalog_identity = published_catalog.stat().st_size, checks.sha(published_catalog)
    prefix = "picker-adapter-library-tree-" + uuid.uuid4().hex[:12]
    raw = history / prefix
    raw.mkdir()
    (raw / f"{prefix}-artifacts.json").write_text(json.dumps(artifacts | {"boundWriterManifest": WRITER_SHA256}), encoding="ascii")
    print(f"Read-only bound recipe evidence: {raw.relative_to(root)}", flush=True)
    ns = checks.load_allocator(root, guest, raw, "pristinepage-tree-" + os.environ["OPENCODE_SESSION_ID"])
    base = checks.run_type(ns, purpose="pristinepage-library-app-tree", operation="Read exact retained bound recipe",
        admit_released=lambda record: checks.verify_bound_recipe_release(claim, record, manifest, snapshot))

    class ReadbackRun(base):
        def process(self, pid):
            return checks.inspect_prior_pid(super().process, pid)

    run = ReadbackRun(prefix, None, 0)
    try:
        checks.wait_for_allocator(run, lambda: run.prepare(False, None, False), before_claim=True)
        checks.recheck_target(run, ns)
        checks.need(run.owned()["api"] == 28 and run.owned()["ownership_history"][-1]["generation"] == claim["generation"],
                    "The released bound recipe guest changed")

        def adb(*args, **options):
            return run.command([ns["ADB"], "-s", run.serial, *args], **options)

        def verify_private_baseline():
            checks.require_cold_backup_app(adb)
            checks.need(not checks.read_app_persisted_grants(adb) and "library-settings" not in checks.recipe_private_scopes(adb),
                        "The ordinary selection or persisted grant inventory changed")

        verify_private_baseline()
        checks.need({package: run.installed_hash(package) for package in require_pair} == require_pair, "The installed exact pair changed")
        checks.preserve_detached_recipe(adb, prior["fixture"], json.loads(detached_anchor("selected-recipe-attempt.json")),
                                        json.loads(detached_anchor("selected-recipe-readback.json")))
        result = readback(run, adb, ns, files, attempt, catalog_identity)
        checks.recheck_target(run, ns)
        verify_private_baseline()
        result.update(recipeSha256=recipe, persistedGrants=[], pointerActivation=2, installedSha256=require_pair)
        run.save(run.path("bound-recipe-readback.json"), result, durable=True)
        print(json.dumps(result), flush=True)
    finally:
        if run.claimed:
            checks.close_owned(run)


if __name__ == "__main__":
    run_readback()
