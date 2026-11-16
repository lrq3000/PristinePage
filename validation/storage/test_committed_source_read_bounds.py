"""Run actual pre-projection source-read SQL on exported Room DDL. Host SQLite is not native Room or codec evidence; no unbounded value is allocated."""

import ctypes
from pathlib import Path
import re
import unittest
from uuid import UUID

import test_room_schema


DAO = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/CatalogDao.kt"


class CommittedSourceBoundsTest(unittest.TestCase):
    def setUp(self):
        fixture = test_room_schema.RoomSchemaTest()
        self.db = fixture.open_schema()
        self.addCleanup(fixture.doCleanups)
        self.source, self.blob, transaction = [str(UUID(int=i)) for i in (1, 2, 3)]
        self.db.execute("INSERT INTO SourceBlob VALUES (?, ?, 4, 'image/png', 3, 2, ?, 'READY', 1, ?, 0)", (self.blob, "a" * 64, f"sources/{self.blob}/original.bin", transaction))
        self.db.execute("INSERT INTO Source VALUES (?, ?, ?, 3, 2, 1, 'srgb-v1', NULL, 0, 'READY', 0)", (self.source, self.blob, "b" * 64))

    def gate(self, source=None):
        match = re.search(r'@Query\("""((?:(?!""").)*)"""\)\s*abstract override fun sourceReadIsBounded\(', DAO.read_text(encoding="utf-8"), re.S)
        self.assertIsNotNone(match, "Raw committed-source bounds query is absent")
        row = self.db.execute(match.group(1), {"id": self.source if source is None else source}).fetchone()
        return None if row is None else row[0]

    def test_absent_source_is_distinct_from_bad_ready_chain(self):
        self.assertEqual(1, self.gate())
        self.assertIsNone(self.gate(str(UUID(int=99))))
        self.db.execute("UPDATE SourceBlob SET state='QUARANTINED'")
        self.assertEqual(0, self.gate())

    def test_raw_integer_aliases_reject_before_room_int_narrowing(self):
        self.assertEqual(3, ctypes.c_int(4294967299).value)
        for table, field, value in [("Source", "width", 3), ("Source", "height", 2), ("Source", "exifOrientation", 1), ("SourceBlob", "encodedWidth", 3), ("SourceBlob", "encodedHeight", 2)]:
            with self.subTest(table=table, field=field):
                self.db.execute(f"UPDATE {table} SET {field}=?", (value + 4294967296,))
                self.assertEqual(0, self.gate())
                self.db.execute(f"UPDATE {table} SET {field}=?", (value,))
        self.assertEqual(1, self.gate())

    def test_wrong_storage_class_and_large_metadata_refuse_without_extraction(self):
        self.db.execute("UPDATE Source SET width=3.5")
        self.assertEqual(0, self.gate())
        self.db.execute("UPDATE Source SET width=3,conversionFingerprint=zeroblob(524288)")
        self.assertEqual(0, self.gate())
        self.db.execute("UPDATE Source SET conversionFingerprint='srgb-v1',profileSha256=zeroblob(524288)")
        self.assertEqual(0, self.gate())

    def test_byte_count_path_and_pixel_product_are_not_trusted(self):
        for table, change, restore in [("SourceBlob", "byteCount=104857601", "byteCount=4"), ("SourceBlob", "relativePath='sources/elsewhere/original.bin'", f"relativePath='sources/{self.blob}/original.bin'"), ("Source", "width=8000,height=6001", "width=3,height=2")]:
            with self.subTest(change=change):
                self.db.execute(f"UPDATE {table} SET {change}")
                self.assertEqual(0, self.gate())
                self.db.execute(f"UPDATE {table} SET {restore}")
        self.assertEqual(1, self.gate())

    def test_nul_padded_text_cannot_hide_large_metadata_behind_character_length(self):
        cases = [("Source", "conversionFingerprint", "srgb-v1"), ("Source", "pixelSha256", "b" * 64), ("Source", "profileSha256", None), ("SourceBlob", "byteSha256", "a" * 64), ("SourceBlob", "ingestTransactionId", str(UUID(int=3)))]
        for table, field, original in cases:
            with self.subTest(table=table, field=field):
                prefix = original if original is not None else "c" * 64
                value = prefix + "\0" + "x" * 524288
                self.db.execute(f"UPDATE {table} SET {field}=?", (value,))
                kind, chars, size = self.db.execute(f"SELECT typeof({field}),length({field}),length(CAST({field} AS BLOB)) FROM {table}").fetchone()
                self.assertEqual(("text", len(prefix), len(prefix) + 524289), (kind, chars, size))
                self.assertEqual(0, self.gate())
                self.db.execute(f"UPDATE {table} SET {field}=?", (original,))
        self.assertEqual(1, self.gate())

    def test_byte_bound_preserves_legitimate_unicode_fingerprint_metadata(self):
        self.db.execute("UPDATE Source SET conversionFingerprint=?", ("é" * 128,))
        self.assertEqual(1, self.gate())


if __name__ == "__main__":
    unittest.main(verbosity=2)
