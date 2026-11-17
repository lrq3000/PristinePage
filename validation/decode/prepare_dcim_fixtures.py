"""P06-A: exactly two generated images in a physically verified guest DCIM child. No alias repair, install, broad query/grant, UI or deletion. Stop before B if A does not register."""

if not __debug__:
    raise RuntimeError("The operational evidence guards require assertions enabled")

import json
import re
import shlex
import uuid

from media_fixtures import SyntheticMediaFixtures
from observe_media_scan import ObservedMediaScan
from run_picker_adapter import ADB, ROOT, sha


class DcimFixtureRun(ObservedMediaScan):
    prefix = "picker-adapter-p06-dcim-fixtures"

    def __init__(self):
        super().__init__()
        for name in ("prepare_dcim_fixtures.py", "test_dcim_fixture_scope.py", "test_observed_media_scan.py"):
            path = "validation/decode/" + name
            self.input["sources"][path] = sha(ROOT / path)
        self.input["purpose"] = "P06-A two bounded fixtures under a proven non-aliased guest DCIM parent; same existing Q APK pair"
        self.save(self.path("artifacts.json"), self.input)

    def prove_parent(self):
        parent = "/storage/emulated/0/DCIM"
        assert str(uuid.UUID(self.generation)) == self.generation
        args = [ADB, "-s", self.serial, "shell"]
        physical = self.command(args + ["sh", "-c", shlex.quote("cd " + parent + " && pwd -P")]).strip()
        assert physical == parent, {"expectedGuestParent": parent, "observedPhysicalParent": physical}
        for path in ("/storage", "/storage/emulated", "/storage/emulated/0", parent):
            self.command(args + ["test", "-L", path], allowed=(1,))
        return parent + "/PristinePage-QA-" + self.generation

    def install_and_test(self):
        # The inherited previous and current digests are identical. install_pair refuses any different installed bytes before its install branch, so it verifies metadata/ownership without reinstalling.
        self.install_pair()
        directory = self.prove_parent()
        args = [ADB, "-s", self.serial]
        self.command(args + ["shell", "test", "!", "-e", directory])
        host = self.path("host-fixtures")
        fixtures = SyntheticMediaFixtures.write_fresh(host)
        assert sum(item["bytes"] for item in fixtures["sources"]) <= 1024**2
        self.save(self.path("fixture-setup.json"), {"generation": self.generation, "guestDirectory": directory, "hostDirectory": str(host), "manifest": fixtures})
        self.command(args + ["shell", "mkdir", directory])
        registered = []
        for item in fixtures["sources"]:
            assert item["label"] in ("A", "B")
            self.fixture = directory + "/" + item["filename"]
            self.fixture_hash = item["encoded_sha256"]
            self.observation_name = "fixture-" + item["label"].lower() + "-observation.json"
            self.command(args + ["push", host / item["filename"], self.fixture], timeout=30)
            # Same complete exact-file observation as R: one broadcast at most, bounded exact-path registration queries and real process/tag witness; never a root scan.
            super().install_and_test()
            observation = json.loads(self.path(self.observation_name).read_text(encoding="utf-8"))
            rows = observation.get("after", [observation["before"]])[-1]["images/media"]
            assert "Row:" in rows and "mime_type=image/png" in rows and "width=256" in rows and "height=192" in rows, rows
            match = re.search(r"(?:^|[ ,])_id=(\d+)", rows)
            assert match and self.fixture in rows
            registered.append({**item, "guestPath": self.fixture, "uri": "content://media/external/images/media/" + match.group(1), "observation": self.evidence(self.path(self.observation_name))})
            self.save(self.path("registered-fixtures.json"), {"generation": self.generation, "guestDirectory": directory, "registered": registered, "realPickerSelection": False, "temporaryGrantVerified": False})
        print(json.dumps({"registeredFixtures": len(registered), "guestDirectory": directory, "payloadBytes": sum(item["bytes"] for item in registered), "noPickerSelection": True}), flush=True)


if __name__ == "__main__":
    DcimFixtureRun().run()
