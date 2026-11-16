"""Execute the actual snapshot-read SQL against exported Room DDL in host SQLite. This is not Android Room or an OOM experiment; native overflow/readback cases remain mandatory."""

import ctypes
from pathlib import Path
import re
import unittest
from uuid import UUID

import test_room_schema


DAO = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/ExportSnapshotDao.kt"


class SnapshotReadBoundsTest(unittest.TestCase):
    def setUp(self):
        fixture = test_room_schema.RoomSchemaTest()
        self.db = fixture.open_schema()
        self.addCleanup(fixture.doCleanups)
        self.document, self.blob, self.source, self.snapshot, self.page = [str(UUID(int=i)) for i in range(1, 6)]
        self.db.execute("INSERT INTO Document VALUES (?, 'Synthetic', 1, 'ACTIVE', 0, 0, NULL)", (self.document,))
        self.db.execute("INSERT INTO SourceBlob VALUES (?, ?, 4, 'image/png', 3, 2, ?, 'READY', 1, ?, 0)", (self.blob, "a" * 64, f"sources/{self.blob}/original.bin", str(UUID(int=8))))
        self.db.execute("INSERT INTO Source VALUES (?, ?, ?, 3, 2, 1, 'srgb-v1', NULL, 1, 'READY', 0)", (self.source, self.blob, "b" * 64))
        self.db.execute("INSERT INTO ExportSnapshot VALUES (?, ?, 1, ?, ?, 'FROZEN', 1, 1000)", (self.snapshot, self.document, "c" * 64, b"bounded settings"))
        self.add_page(0)

    def add_page(self, ordinal, masks=b"[]"):
        self.db.execute("INSERT INTO ExportPage VALUES (?, ?, ?, ?, ?, ?, ?, 3, 2, 'srgb-v1', 1, 1, ?, ?, ?, ?, ?, 'renderer-v1', 3, 2, 3, 2, 'FAIL', NULL, NULL)", (self.snapshot, ordinal, str(UUID(int=100 + ordinal)), self.source, self.blob, "a" * 64, "b" * 64, b"{}", "c" * 64, masks, b"[]", "d" * 64))

    def gate(self, method):
        match = re.search(r'@Query\("""((?:(?!""").)*)"""\)\s*abstract override fun ' + method + r'\(', DAO.read_text(encoding="utf-8"), re.S)
        self.assertIsNotNone(match, f"Pre-materialization snapshot SQL is absent: {method}")
        return self.db.execute(match.group(1), {"snapshotId": self.snapshot}).fetchone()[0]

    def test_snapshot_header_bounds_precede_settings_extraction(self):
        self.assertEqual(1, self.gate("snapshotMetadataIsBounded"))
        self.db.execute("UPDATE ExportSnapshot SET settings=zeroblob(524289)")
        self.assertEqual(0, self.gate("snapshotMetadataIsBounded"))

    def test_individual_and_total_page_bytes_are_bounded_without_extracting_blobs(self):
        self.assertEqual(1, self.gate("exportPagesAreBounded"))
        self.db.execute("UPDATE ExportPage SET masks=zeroblob(524289)")
        self.assertEqual(0, self.gate("exportPagesAreBounded"))
        self.db.execute("UPDATE ExportPage SET masks=zeroblob(524288)")
        for ordinal in range(1, 15):
            self.add_page(ordinal, bytes(524288))
        self.assertEqual(1, self.gate("exportPagesAreBounded"))
        self.add_page(15, bytes(524288))
        self.assertEqual(0, self.gate("exportPagesAreBounded"))

    def test_sqlite_long_values_cannot_alias_valid_int_fields(self):
        self.assertEqual(3, ctypes.c_int(4294967299).value)
        for field, original in [("ordinal", 0), ("width", 3), ("height", 2), ("recipeSchemaVersion", 1), ("requestedOutputWidth", 3), ("requestedOutputHeight", 2), ("outputWidth", 3), ("outputHeight", 2)]:
            with self.subTest(field=field):
                self.db.execute(f"UPDATE ExportPage SET {field}=?", (original + 4294967296,))
                self.assertEqual(0, self.gate("exportPagesAreBounded"))
                self.db.execute(f"UPDATE ExportPage SET {field}=?", (original,))
        self.assertEqual(1, self.gate("exportPagesAreBounded"))

    def test_wrong_sqlite_storage_classes_reject_before_projection(self):
        self.db.execute("UPDATE ExportPage SET width=3.5")
        self.assertEqual(0, self.gate("exportPagesAreBounded"))
        self.db.execute("UPDATE ExportPage SET width=3, recipe='not a blob'")
        self.assertEqual(0, self.gate("exportPagesAreBounded"))
        self.db.execute("UPDATE ExportSnapshot SET documentRevision=1.5")
        self.assertEqual(0, self.gate("snapshotMetadataIsBounded"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
