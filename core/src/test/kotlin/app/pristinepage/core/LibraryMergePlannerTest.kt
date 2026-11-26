package app.pristinepage.core

import app.pristinepage.core.library.LibraryMergePlanner
import app.pristinepage.core.library.LibraryMergePlanner.Album
import app.pristinepage.core.library.LibraryMergePlanner.Decision
import app.pristinepage.core.library.LibraryMergePlanner.Original
import app.pristinepage.core.library.LibraryMergePlanner.Page
import org.junit.Assert.*
import org.junit.Test

class LibraryMergePlannerTest {
    @Test fun matchingOriginalKeepsPageIdentityAndTakesIncomingEditAndOcr() {
        val plan = plan(listOf(a("local1", "one.jpg", 'a'), a("local2", "two.jpg", 'b')),
            listOf(a("backup1", "one.jpg", 'a'), a("backup3", "three.jpg", 'c'))) as Decision.Merge
        assertEquals("local", plan.destinationAlbumKey)
        assertEquals(listOf("local1" to "backup1"), plan.useIncomingState.map { it.destinationPageKey to it.incomingPageKey })
        assertEquals(listOf("backup3"), plan.appendPageKeys)
    }

    @Test fun disjointKnownNamesAreAnAddendumNotANameConflict() {
        val plan = plan(listOf(a("local1", "one.jpg", 'a')),
            listOf(a("backup2", "two.jpg", 'b'), a("backup3", "three.jpg", 'c'))) as Decision.Merge
        assertTrue(plan.useIncomingState.isEmpty())
        assertEquals(listOf("backup2", "backup3"), plan.appendPageKeys)
    }

    @Test fun oneDifferentOriginalUnderTheSameNameKeepsTheWholeIncomingAlbumSeparate() {
        val plan = plan(listOf(a("local1", "Résumé.JPG", 'a'), a("local2", "two.jpg", 'c')),
            listOf(a("backup1", "Re\u0301sume\u0301.jpg", 'b'), a("backup2", "two.jpg", 'c'))) as Decision.Add
        assertEquals("Receipts (imported)", plan.title)
        assertEquals(LibraryMergePlanner.Reason.ORIGINAL_CONFLICT, plan.reason)
    }

    @Test fun aStablePageKeyDoesNotAuthorizeDifferentOriginalsOrIncompatibleNormalizedPixels() {
        val local = listOf(a("same-page", "one.jpg", 'a'))
        assertEquals(LibraryMergePlanner.Reason.ORIGINAL_CONFLICT,
            (plan(local, listOf(a("same-page", "renamed.jpg", 'b'))) as Decision.Add).reason)
        val differentlyDecoded = a("different-key", "one.jpg", 'a').copy(sourcePixelSha256 = "d".repeat(64))
        assertEquals(LibraryMergePlanner.Reason.INCOMPATIBLE_SOURCE_BINDING,
            (plan(local, listOf(differentlyDecoded)) as Decision.Add).reason)
        val changedFingerprint = a("different-key", "one.jpg", 'a').copy(conversionFingerprint = "oriented-srgb-v2")
        assertEquals(LibraryMergePlanner.Reason.INCOMPATIBLE_SOURCE_BINDING,
            (plan(local, listOf(changedFingerprint)) as Decision.Add).reason)
        val changedLength = a("different-key", "one.jpg", 'a').copy(original = Original("a".repeat(64), 124))
        assertEquals(LibraryMergePlanner.Reason.ORIGINAL_CONFLICT,
            (plan(local, listOf(changedLength)) as Decision.Add).reason)
    }

    @Test fun matchingIdWithoutMatchingNameDoesNotDropAnIncomingName() {
        val old = listOf(a("same-id", "old.jpg", 'a'))
        val incoming = listOf(a("same-id", "new.jpg", 'a'))
        assertEquals(LibraryMergePlanner.Reason.AMBIGUOUS_PAGES, (plan(old, incoming) as Decision.Add).reason)
    }

    @Test fun sameTitleCannotOverrideAnAlbumIdThatLocatesAnotherAlbum() {
        val old = listOf(Album("same-id", "Travel", emptyList()), Album("other", "Receipts", listOf(a("old", "one", 'a'))))
        val result = LibraryMergePlanner.plan(old, listOf(Album("same-id", "Receipts", listOf(a("new", "one", 'a'))))).single()
        assertEquals(LibraryMergePlanner.Reason.AMBIGUOUS_ALBUM, (result as Decision.Add).reason)
    }

    @Test fun sameNameCannotOverrideAPageIdThatLocatesAnotherAlbum() {
        val old = listOf(Album("travel", "Travel", listOf(a("shared-id", "one", 'a'))),
            Album("receipts", "Receipts", listOf(a("old", "one", 'a'))))
        val result = LibraryMergePlanner.plan(old, listOf(Album("new", "Receipts", listOf(a("shared-id", "one", 'a'))))).single()
        assertEquals(LibraryMergePlanner.Reason.AMBIGUOUS_PAGES, (result as Decision.Add).reason)
    }

    @Test fun greekFinalSigmaCannotBypassLogicalFilenameComparison() {
        val old = listOf(a("old", "Σ.jpg", 'a'))
        val incoming = listOf(a("new", "ς.jpg", 'b'))
        assertEquals(LibraryMergePlanner.Reason.ORIGINAL_CONFLICT, (plan(old, incoming) as Decision.Add).reason)
    }

    @Test fun deliberatelyRepeatedOriginalsHaveSeparateFilenameSlots() {
        val local = listOf(a("one", "one.jpg", 'a'), a("two", "two.jpg", 'a'))
        val extra = plan(local, listOf(a("three", "three.jpg", 'a'))) as Decision.Merge
        assertEquals(listOf("three"), extra.appendPageKeys)
        assertTrue(extra.useIncomingState.isEmpty())
        val samePages = plan(local, local.reversed()) as Decision.Merge
        assertEquals(listOf("two" to "two", "one" to "one"), samePages.useIncomingState.map { it.destinationPageKey to it.incomingPageKey })
        assertTrue(samePages.appendPageKeys.isEmpty())
    }

    @Test fun duplicateLogicalNamesWithinAnAlbumRefuseTheInvalidInventory() {
        val local = listOf(a("one", "photo.jpg", 'a'), a("two", "PHOTO.JPG", 'a'))
        assertThrows(IllegalArgumentException::class.java) { plan(local, listOf(a("three", "photo.jpg", 'a'))) }
    }

    @Test fun crossedStablePageAndFilenameMatchesKeepTheIncomingAlbumSeparate() {
        val local = listOf(a("one", "one.jpg", 'a'), a("two", "two.jpg", 'a'))
        val result = plan(local, listOf(a("one", "two.jpg", 'a'))) as Decision.Add
        assertEquals(LibraryMergePlanner.Reason.AMBIGUOUS_PAGES, result.reason)
    }

    @Test fun ambiguousAlbumTitlesAndReservedNamesNeverOverwriteOrDependOnAPath() {
        val destination = listOf(Album("left", "Receipts", emptyList()), Album("right", "receipts", emptyList()),
            Album("reserved", "Receipts (imported)", emptyList()))
        val source = listOf(Album("incoming1", "Receipts", emptyList()), Album("incoming2", "Receipts", emptyList()))
        val plan = LibraryMergePlanner.plan(destination, source).map { it as Decision.Add }
        assertEquals(listOf("Receipts (imported 2)", "Receipts (imported 3)"), plan.map { it.title })
        assertTrue(plan.all { it.reason == LibraryMergePlanner.Reason.AMBIGUOUS_ALBUM })
    }

    @Test fun genuinelyNewTitleIsReservedEvenBeforeItsAlbumIsVisited() {
        val local = listOf(Album("local", "Receipts", listOf(a("old", "one", 'a'))))
        val incoming = listOf(Album("collision", "Receipts", listOf(a("replacement", "one", 'b'))),
            Album("literal", "Receipts (imported)", listOf(a("different", "one", 'c'))))
        val choices = LibraryMergePlanner.plan(local, incoming).map { it as Decision.Add }
        assertEquals(listOf("Receipts (imported 2)", "Receipts (imported)"), choices.map { it.title })
    }

    @Test fun renameKeepsTitleWithinExistingCatalogLimitsWithoutSplittingUnicode() {
        val title = "a".repeat(148) + "😀" + "b".repeat(10)
        val old = Album("local", title, listOf(a("l", "same", 'a')))
        val incoming = Album("incoming", title, listOf(a("i", "same", 'b')))
        val result = LibraryMergePlanner.plan(listOf(old), listOf(incoming)).single() as Decision.Add
        assertTrue(result.title.length <= 160)
        assertEquals("a".repeat(148) + " (imported)", result.title)
    }

    private fun plan(destination: List<Page>, incoming: List<Page>) =
        LibraryMergePlanner.plan(listOf(Album("local", "Receipts", destination)), listOf(Album("backup", "Receipts", incoming))).single()

    private fun a(key: String, name: String, bytes: Char) = Page(key, name, Original(bytes.toString().repeat(64), 123),
        bytes.toString().repeat(64), "oriented-srgb-v1")
}
