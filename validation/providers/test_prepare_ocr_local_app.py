from hashlib import sha256
from io import BytesIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from prepare_ocr_local_app import prepare_from_publisher, require_exact_tree, verify_ocr_members, verify_standard_manifest


class OcrLocalAppIsolationTest(unittest.TestCase):
    def test_bootstrap_from_verified_input_files_without_a_probe_receipt(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            inputs = root / "publisher-inputs"
            inputs.mkdir()
            stage = root / "ocr-app-standard"
            aar = inputs / "tesseract4android.aar"
            with ZipFile(aar, "w") as wrapper:
                wrapper.writestr("classes.jar", b"jar")
            notice = inputs / "clang-notice"
            notice.write_bytes(b"clang")
            license_file = inputs / "wrapper-license.txt"
            license_file.write_bytes(b"license")
            pack = inputs / "eng.traineddata"
            pack.write_bytes(b"bad!")

            def pin(data):
                return len(data), sha256(data).hexdigest()

            with patch("prepare_ocr_local_app.RAW", root), \
                    patch("prepare_ocr_local_app.AAR", (aar.name, *pin(aar.read_bytes()))), \
                    patch("prepare_ocr_local_app.CLANG_NOTICE", pin(b"clang")), \
                    patch("prepare_ocr_local_app.NOTICES", {license_file.name: (*pin(b"license"), "tesseract4android.txt")}), \
                    patch("prepare_ocr_local_app.pack_pins", return_value={"eng": pin(b"pack")}):
                with self.assertRaises(ValueError):
                    prepare_from_publisher(stage, inputs, notice)
                self.assertFalse(stage.exists())
                pack.write_bytes(b"pack")
                prepare_from_publisher(stage, inputs, notice)
                self.assertEqual(b"pack", (stage / "assets/ocr-local/tessdata/eng.traineddata").read_bytes())
                self.assertEqual(b"jar", (stage / "classes.jar").read_bytes())
                self.assertEqual(b"license", (stage / "assets/ocr-local/notices/tesseract4android.txt").read_bytes())
                self.assertNotIn("ocr-probe", {p.name for p in stage.rglob("*")})

    def test_accepts_sdk_xmltree_application_attribute_not_a_nested_component(self):
        manifest = """N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    E: application (line=14)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.pristinepage.PristinePageApplication" (Raw: "app.pristinepage.PristinePageApplication")
      E: provider (line=20)
        E: meta-data (line=21)
          A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.work.WorkManagerInitializer"
"""
        package = "package: name='app.pristinepage.debug' versionCode='8' versionName='0.5.0-debug'"
        verify_standard_manifest(package, manifest)
        nested_only = manifest.replace("    E: application (line=14)", "    E: application (line=14)\n      E: activity (line=15)").replace(
            "      A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"app.pristinepage", "        A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"app.pristinepage",
        )
        with self.assertRaises(ValueError):
            verify_standard_manifest(package, nested_only)

    def test_accepts_the_standard_application_and_rejects_old_opt_in_or_network(self):
        standard = '''E: application (line=14)
  A: android:name(0x01010003)="app.pristinepage.PristinePageApplication" (Raw: "app.pristinepage.PristinePageApplication")
  E: provider (line=20)
    A: android:name(0x01010003)="androidx.startup.InitializationProvider"
    E: meta-data (line=21)
      A: android:name(0x01010003)="androidx.work.WorkManagerInitializer"
'''
        self.assertIsNone(verify_standard_manifest("package: name='app.pristinepage.debug' versionName='0.5.0-debug'", standard))
        self.assertIsNone(verify_standard_manifest("package: name='app.pristinepage' versionName='0.5.0'", standard))
        for metadata, manifest in (
            ("package: name='app.pristinepage.debug' versionName='0.5.0-debug-ocr-local'", standard),
            ("package: name='app.pristinepage.debug' versionName='0.5.0-debug'", standard.replace("PristinePageApplication", "LocalOcrApplication")),
            ("package: name='app.pristinepage.debug' versionName='0.5.0-debug'", standard + 'A: android:name="android.permission.INTERNET"'),
        ):
            with self.subTest(metadata=metadata, manifest=manifest), self.assertRaises(ValueError):
                verify_standard_manifest(metadata, manifest)

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
