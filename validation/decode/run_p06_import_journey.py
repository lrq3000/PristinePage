"""One real P06 import/grant/editor native consumer using existing exact artifact/ownership/shutdown guards. Select only the authenticated two-fixture album; no grants, settings, media writes, phone or raw-result injection."""

if not __debug__:
    raise RuntimeError("The operational evidence guards require assertions enabled")

import json
from pathlib import Path
import re

from run_application_composition import ApplicationCompositionRun
from run_picker_adapter import ADB, ROOT, sha


class P06ImportRun(ApplicationCompositionRun):
    classes = ("app.pristinepage.P06ImportJourneyTest",)
    case_count = 1

    def __init__(self, prefix):
        registered = ROOT / "validation/raw/decode/picker-adapter-p06-dcim-fixtures-registered-fixtures.json"
        # Bind the actual independently authenticated fixture record, not guessed row IDs or a hard-coded release version.
        manifest = json.loads((registered.parent / "picker-adapter-p06-dcim-fixtures-final-manifest.json").read_text())
        assert sha(registered.parent / "picker-adapter-p06-dcim-fixtures-final-manifest.json") == "97bebaa56df86d015ba9326643e0206c383579000c823af82264aea92ae539be"
        item = next(entry for entry in manifest["evidence"] if Path(entry["path"]).name == registered.name)
        assert sha(registered) == item["sha256"]
        self.fixtures = json.loads(registered.read_text())
        assert len(self.fixtures["registered"]) == 2
        super().__init__("picker-adapter-" + prefix, {"app": "d8d80c5f3e5d3763b49cdf5cd4fffcce2d76a2545545d742877ded84f7f1d3b0", "test": "b868ececa63989f7d3e98138f8c87a54f5f5f13f851f903a13b5f86530ec369e"}, 0)

    def command(self, args, timeout=15, allowed=(0,)):
        if hasattr(self,"serial") and list(map(str,args[:7])) == [str(ADB),"-s",self.serial,"shell","am","instrument","-w"]:
            ids = [item["uri"].rsplit("/",1)[-1] for item in self.fixtures["registered"]]
            assert all(re.fullmatch(r"[1-9][0-9]*", item) for item in ids)
            extra = ["-e","p06_run_id",self.generation,"-e","p06_album",self.fixtures["guestDirectory"].rsplit("/",1)[-1],"-e","p06_media_ids",",".join(ids)]
            args = args[:-1] + extra + args[-1:]
            timeout = 360
        return super().command(args,timeout,allowed)

    def install_and_test(self):
        try:
            super().install_and_test()
        finally:
            if hasattr(self,"serial"):
                result = self.command([ADB,"-s",self.serial,"exec-out","run-as","app.pristinepage.debug","cat",f"files/p06-real-journey/{self.generation}/result.json"],allowed=(0,1))
                with self.path("journey-result.txt").open("x",encoding="utf-8") as output:
                    output.write(result)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefix",required=True)
    P06ImportRun(parser.parse_args().prefix).run()
