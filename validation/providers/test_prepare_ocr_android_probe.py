"""Validate the exact-input admission used by the local instrumentation build."""

from hashlib import sha256
from io import BytesIO
import unittest

from prepare_ocr_android_probe import inventory_from_receipt, read_pins, verified_bytes, verify_stream


class AndroidOcrInputTest(unittest.TestCase):
    def test_stream_refuses_truncation_extension_and_same_length_tampering(self):
        source = b"known exact OCR resource"
        digest = sha256(source).hexdigest()
        verify_stream(BytesIO(source), len(source), digest)
        for data in (source[:-1], source + b"x", source[:-1] + b"x"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                verify_stream(BytesIO(data), len(source), digest)

    def test_pin_inventory_refuses_duplicates_changes_in_order_and_extra_components(self):
        prefix = "code\tbytes\tsha256\n"
        eng = f"eng\t12\t{'a' * 64}\n"
        fra = f"fra\t34\t{'b' * 64}\n"
        self.assertEqual({"eng": (12, "a" * 64), "fra": (34, "b" * 64)},
                         read_pins(prefix + eng + fra, ("eng", "fra")))
        for lines in (eng + eng, fra + eng, eng + fra + eng, eng + fra.replace("fra", "../fra"),
                      eng + fra.replace("\t34\t", "\t-34\t"), eng + fra.replace("b" * 64, "B" * 64)):
            with self.subTest(lines=lines), self.assertRaises(ValueError):
                read_pins(prefix + lines, ("eng", "fra"))

    def test_unverified_bytes_are_not_returned_for_staging(self):
        source = b"verified asset"
        pin = sha256(source).hexdigest()
        self.assertEqual(source, verified_bytes(BytesIO(source), len(source), pin))
        with self.assertRaises(ValueError):
            verified_bytes(BytesIO(b"verified assex"), len(source), pin)

    def test_stage_receipt_refuses_an_altered_or_partial_asset_inventory(self):
        good = b'{"assets":{"ocr-probe/manifest.json":[12,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}}'
        pin = sha256(good).hexdigest()
        expect = {"ocr-probe/manifest.json"}
        self.assertEqual({"ocr-probe/manifest.json": (12, "a" * 64)}, inventory_from_receipt(good, pin, expect, expect))
        for receipt, actual in ((good + b"\n", expect), (good, set()), (good, expect | {"unrelated.txt"})):
            with self.subTest(receipt=receipt, actual=actual), self.assertRaises(ValueError):
                inventory_from_receipt(receipt, pin, expect, actual)
