"""Run the actual portable snapshot refusal query with the complete Room schema."""

import re
import unittest

import test_library_catalog_reader as catalog


class LibrarySnapshotAdmissionSqlTest(unittest.TestCase):
    DOCUMENT = "INSERT INTO Document VALUES ('album', 'Test album', 1, 'ACTIVE', 0, 0, NULL)"

    def blocker(self, state, table, *after_seed):
        if table == "AtelierDraft":
            witness = (
                "INSERT INTO AtelierDraft(draftId,stagingDocumentId,state,generation,step,createdAt) "
                f"VALUES ('draft','album',{state},1,0,0)"
            )
        else:
            witness = (
                "INSERT INTO ImportSession(sessionId,kind,documentId,state,expectedMembershipToken,createdAt,expiresAt) "
                f"VALUES ('session','BATCH','album',{state},'tag',0,1)"
            )
        source = catalog.ROOT / "data/src/main/kotlin/app/pristinepage/data/library/LibrarySnapshotAdmission.kt"
        self.assertTrue(source.exists(), "Missing snapshot admission query")
        query = re.search(r'const val BLOCKED = """(.*?)"""', source.read_text(encoding="utf-8"), re.S)
        self.assertIsNotNone(query)
        rows = catalog.LibraryCatalogReaderSqlTest().sqlite(self.DOCUMENT, witness, *after_seed, query[1])
        return list(rows[0].values())[0]

    def test_pending_draft_or_malformed_state_cannot_disappear_from_an_export(self):
        for state in ("'ACQUIRING'", "'EDITING'", "'FUTURE'", "X'434F4D4D4954544544'"):
            with self.subTest(state=state):
                self.assertEqual(1, self.blocker(state, "AtelierDraft"))

    def test_unresolved_import_blocks_even_after_its_deadline(self):
        for state in ("'RESERVED'", "'INGESTING'", "'FUTURE'"):
            with self.subTest(state=state):
                self.assertEqual(1, self.blocker(state, "ImportSession"))

    def test_terminal_drafts_and_import_history_do_not_by_themselves_block_export(self):
        self.assertEqual(0, self.blocker("'ABANDONED'", "AtelierDraft"))
        for state in ("'COMMITTED'", "'CANCELED'", "'FAILED'", "'QUARANTINED'"):
            with self.subTest(state=state):
                self.assertEqual(0, self.blocker(state, "ImportSession"))

    def test_committed_label_cannot_hide_an_unfinished_draft(self):
        self.assertEqual(1, self.blocker("'COMMITTED'", "AtelierDraft"))

    def test_plausible_terminal_receipt_shapes_pass_the_precheck_for_new_album_and_append(self):
        album = "aaaaaaaa-aaaa-4aaa-8aaa-000000000001"
        target = "aaaaaaaa-aaaa-4aaa-8aaa-000000000002"
        page_a = "aaaaaaaa-aaaa-4aaa-8aaa-000000000003"
        page_b = "aaaaaaaa-aaaa-4aaa-8aaa-000000000004"
        for append in (False, True):
            doc, saved = (target, page_b) if append else (album, page_a)
            target_id, revision = (f"'{target}'", "1") if append else ("NULL", "NULL")
            result = self.blocker(
                "'COMMITTED'", "AtelierDraft",
                f"UPDATE Document SET documentId='{album}'",
                f"INSERT INTO Document VALUES ('{target}','Target',1,'ACTIVE',0,0,NULL)",
                f"UPDATE AtelierDraft SET stagingDocumentId='{album}', committedDocumentId='{doc}',"
                f"targetDocumentId={target_id},targetRevision={revision},generation=2,step=2,"
                f"receiptPageIds='atelier-publish-v1'||char(10)||'1'||char(10)||'{doc}'||char(10)||'{page_a},1,{'a'*64},{saved}'",
            )
            self.assertEqual(0, result)

    def test_an_orphaned_private_journal_counts_as_existing_destination_data(self):
        source = catalog.ROOT / "data/src/main/kotlin/app/pristinepage/data/library/RoomLibrarySavedCatalog.kt"
        query = re.search(r'const val NONEMPTY_STAGE = """(.*?)"""', source.read_text(encoding="utf-8"), re.S)
        self.assertIsNotNone(query, "Fresh destination must check even independent journal rows")
        fixture = catalog.LibraryCatalogReaderSqlTest()
        self.assertEqual([], fixture.sqlite(query[1]))
        self.assertNotEqual([], fixture.sqlite(
            "INSERT INTO FileTransaction(transactionId,kind,ownerId,state,payload,attempt,updatedAt)"
            " VALUES ('synthetic','RENDER','owner','INTENT',X'01',1,0)",
            query[1],
        ))
