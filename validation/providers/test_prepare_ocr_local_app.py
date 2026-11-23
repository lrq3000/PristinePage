from hashlib import sha256
from io import BytesIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile

from prepare_ocr_local_app import require_exact_tree, verify_ocr_members


class OcrLocalAppIsolationTest(unittest.TestCase):
    def test_stage_refuses_extra_files_and_equal_length_tampering(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "selected").write_bytes(b"pin")
            expected = {"selected": (3, sha256(b"pin").hexdigest())}
            require_exact_tree(path, expected)
            (path / "extra").write_bytes(b"not part of the candidate")
            with self.assertRaises(ValueError):
                require_exact_tree(path, expected)
        with TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "selected").write_bytes(b"pun")
            with self.assertRaises(ValueError):
                require_exact_tree(path, expected)

    def test_apk_requires_exact_pack_assets_and_native_libraries_and_no_probe(self):
        pack = "assets/ocr-local/tessdata/eng.traineddata"
        native = "lib/arm64-v8a/libtesseract.so"
        good = {pack: b"pack", native: b"arm64"}
        expected = {name: (len(data), sha256(data).hexdigest()) for name, data in good.items()}
        for files, accepted in (
            (good | {"classes.dex": b"rest of app"}, True),
            ({pack: good[pack]}, False),
            (good | {pack: b"hack"}, False),
            (good | {"lib/x86/libtesseract.so": b"unsupported"}, False),
            (good | {"lib/arm64-v8a/libunexpected.so": b"unreviewed"}, False),
            (good | {"assets/elsewhere/eng.traineddata": b"second pack"}, False),
            (good | {"assets/ocr-probe/controls/eng.png": b"probe"}, False),
        ):
            raw = BytesIO()
            with ZipFile(raw, "w") as writer:
                for name, data in files.items():
                    writer.writestr(name, data)
            with self.subTest(files=list(files)), ZipFile(raw) as apk:
                if accepted:
                    verify_ocr_members(apk, expected)
                else:
                    with self.assertRaises(ValueError):
                        verify_ocr_members(apk, expected)
