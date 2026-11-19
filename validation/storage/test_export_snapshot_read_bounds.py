"""Execute the actual snapshot-read SQL against exported Room DDL in host SQLite. This is not Android Room or an OOM experiment; native overflow/readback cases remain mandatory."""

import ctypes
from pathlib import Path
import re
import unittest
from uuid import UUID

import test_room_schema


DAO = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/ExportSnapshotDao.kt"
TEXT_SUFFIX = "\x00" + "x" * 513


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

    def assert_text_suffix_rejected(self, table, field, prefix, gate):
        # These identifiers come only from the fixed test cases; mutate exactly the seeded header or its single page.
        where = "WHERE snapshotId=?" if table == "ExportSnapshot" else "WHERE snapshotId=? AND ordinal=0"
        update = f"UPDATE {table} SET {field}=? {where}"
        self.db.execute(update, (prefix + TEXT_SUFFIX, self.snapshot))
        try:
            # Prove the mismatch with scalars only: this is still TEXT, and only its byte extent counts the full suffix.
            extents = self.db.execute(
                f"SELECT typeof({field}), length({field}), length(CAST({field} AS BLOB)) FROM {table} {where}",
                (self.snapshot,),
            ).fetchone()
            self.assertEqual(("text", len(prefix), len(prefix) + len(TEXT_SUFFIX)), extents)
            self.assertEqual(0, self.gate(gate), f"{table}.{field} must be refused before typed projection")
        finally:
            # A failing subtest must not leave its mutation in place for the next field.
            self.db.execute(update, (prefix, self.snapshot))

    def test_snapshot_header_rejects_text_suffixes_but_admits_bounded_unknown_state(self):
        gate = "snapshotMetadataIsBounded"
        self.assertEqual(1, self.gate(gate))
        # The raw guard checks shape, not membership; verifyLoadable owns UNSUPPORTED_VERSION for a bounded unknown state.
        for state in ("FUTURE_" + "X" * 121, "FUTURE_\u754c", "\U0001f642" * 128):
            with self.subTest(state_bytes=len(state.encode("utf-8"))):
                self.db.execute("UPDATE ExportSnapshot SET state=? WHERE snapshotId=?", (state, self.snapshot))
                self.assertEqual(1, self.gate(gate))
        self.db.execute("UPDATE ExportSnapshot SET state='FROZEN' WHERE snapshotId=?", (self.snapshot,))
        for field, prefix in (("snapshotSha256", "c" * 64), ("state", "FROZEN")):
            with self.subTest(field=field):
                self.assert_text_suffix_rejected("ExportSnapshot", field, prefix, gate)

    def test_export_pages_reject_text_suffixes_without_narrowing_unicode_versions(self):
        gate = "exportPagesAreBounded"
        self.assertEqual(1, self.gate(gate))
        # Both version strings fit the typed 128 UTF-16 code-unit policy, but exceed 128 UTF-8 bytes.
        self.db.execute(
            "UPDATE ExportPage SET conversionFingerprint=?, rendererVersion=? WHERE snapshotId=? AND ordinal=0",
            ("\u754c" * 128, "\U0001f642" * 64, self.snapshot),
        )
        self.assertEqual(1, self.gate(gate))
        self.db.execute(
            "UPDATE ExportPage SET conversionFingerprint='srgb-v1', rendererVersion='renderer-v1' WHERE snapshotId=? AND ordinal=0",
            (self.snapshot,),
        )
        fields = (
            ("originalPageId", str(UUID(int=100))),
            ("byteSha256", "a" * 64),
            ("pixelSha256", "b" * 64),
            ("recipeSha256", "c" * 64),
            ("assetSetSha256", "d" * 64),
            ("fallbackPolicy", "FAIL"),
        )
        for field, prefix in fields:
            with self.subTest(field=field):
                self.assert_text_suffix_rejected("ExportPage", field, prefix, gate)

    def test_fallback_text_byte_boundary_with_the_same_character_count(self):
        for suffix, flag in (("", 1), ("\0x", 0)):
            with self.subTest(byte_extent=512 + len(suffix)):
                self.db.execute("UPDATE ExportPage SET fallbackPolicy=?", ("\U0001f642" * 128 + suffix,))
                self.assertEqual((128, 512 + len(suffix)), self.db.execute(
                    "SELECT length(fallbackPolicy), length(CAST(fallbackPolicy AS BLOB)) FROM ExportPage"
                ).fetchone())
                self.assertEqual(flag, self.gate("exportPagesAreBounded"))
        # Change only the raw byte extent from 512 to 513; length(TEXT) still sees 128 characters.
        self.db.execute("UPDATE ExportPage SET fallbackPolicy=?", ("\U0001f642" * 128 + "\0",))
        self.assertEqual((128, 513), self.db.execute(
            "SELECT length(fallbackPolicy), length(CAST(fallbackPolicy AS BLOB)) FROM ExportPage"
        ).fetchone())
        self.assertEqual(0, self.gate("exportPagesAreBounded"))

    def test_nul_suffixed_snapshot_foreign_key_cannot_match_canonical_lookup(self):
        foreign = self.snapshot + "\0x"
        self.db.execute(
            "INSERT INTO ExportSnapshot SELECT ?, documentId, documentRevision, snapshotSha256, settings, state, createdAt, retainedUntil "
            "FROM ExportSnapshot WHERE snapshotId=?", (foreign, self.snapshot),
        )
        self.db.execute("UPDATE ExportPage SET snapshotId=? WHERE snapshotId=?", (foreign, self.snapshot))
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())
        self.assertEqual(1, self.gate("snapshotMetadataIsBounded"))
        self.assertEqual(0, self.gate("exportPagesAreBounded"))
        self.assertEqual([(36, 38)], self.db.execute(
            "SELECT length(snapshotId), length(CAST(snapshotId AS BLOB)) FROM ExportPage WHERE snapshotId=?", (foreign,)
        ).fetchall())

    def test_export_pages_reject_text_suffixes_in_each_accepted_render_field(self):
        gate = "exportPagesAreBounded"
        job_id, render_id = (str(UUID(int=i)) for i in (200, 201))
        page_id, execution_sha256 = str(UUID(int=100)), "e" * 64
        self.db.execute(
            "INSERT INTO Job (jobId, kind, idempotenceKey, canonicalInputSha256, input, state, attempt, leaseGeneration, createdAt, updatedAt) "
            "VALUES (?, 'PAGE_RENDER', 'fixture-render-job', ?, ?, 'SUCCEEDED', 1, 1, 0, 0)",
            (job_id, "f" * 64, b"{}"),
        )
        # The test-only parent with the same malformed key keeps the real FK enforced while only one projected field changes.
        self.db.executemany(
            "INSERT INTO RenderedPage (renderId, jobId, sourceId, pageId, recipeRevision, cacheKey, execution, executionSha256, "
            "relativePath, byteSha256, rasterPixelSha256, byteCount, width, height, renderStatus, state) "
            "VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 4, 3, 2, 'DEGRADED', 'READY')",
            [
                (key, job_id, self.source, page_id, f"fixture-cache-{i}", b"{}", execution_sha256, f"fixture/{i}/page.bin", "a" * 64, "b" * 64)
                for i, key in enumerate((render_id, render_id + TEXT_SUFFIX))
            ],
        )
        self.db.execute(
            "UPDATE ExportPage SET fallbackPolicy='KEEP_STAGE_INPUT', acceptedRenderId=?, acceptedExecutionSha256=? WHERE snapshotId=? AND ordinal=0",
            (render_id, execution_sha256, self.snapshot),
        )
        self.assertEqual(1, self.gate(gate))
        for field, prefix in (("acceptedRenderId", render_id), ("acceptedExecutionSha256", execution_sha256)):
            with self.subTest(field=field):
                self.assert_text_suffix_rejected("ExportPage", field, prefix, gate)

    def test_snapshot_and_page_reject_text_suffixes_in_foreign_ids(self):
        # Copy only the required parents under synthetic keys so the real FK checks cannot mask any raw guard failure.
        self.db.execute(
            "INSERT INTO Document SELECT ?, title, documentRevision, lifecycle, createdAt, updatedAt, deletedAt FROM Document WHERE documentId=?",
            (self.document + TEXT_SUFFIX, self.document),
        )
        self.db.execute(
            "INSERT INTO SourceBlob SELECT ?, byteSha256, byteCount, mime, encodedWidth, encodedHeight, ?, state, sourceCount, "
            "ingestTransactionId, createdAt FROM SourceBlob WHERE sourceBlobId=?",
            (self.blob + TEXT_SUFFIX, "fixture/other-original.bin", self.blob),
        )
        self.db.execute(
            "INSERT INTO Source SELECT ?, ?, pixelSha256, width, height, exifOrientation, conversionFingerprint, profileSha256, "
            "refCount, state, createdAt FROM Source WHERE sourceId=?",
            (self.source + TEXT_SUFFIX, self.blob + TEXT_SUFFIX, self.source),
        )
        self.assertEqual(1, self.gate("snapshotMetadataIsBounded"))
        self.assertEqual(1, self.gate("exportPagesAreBounded"))
        for table, field, prefix, gate in (
            ("ExportSnapshot", "documentId", self.document, "snapshotMetadataIsBounded"),
            ("ExportPage", "sourceId", self.source, "exportPagesAreBounded"),
            ("ExportPage", "sourceBlobId", self.blob, "exportPagesAreBounded"),
        ):
            with self.subTest(field=field):
                self.assert_text_suffix_rejected(table, field, prefix, gate)

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
