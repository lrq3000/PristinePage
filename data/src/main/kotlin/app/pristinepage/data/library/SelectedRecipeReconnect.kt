package app.pristinepage.data.library

/** Only a positively observed tip mismatch can nominate a recipe-recovery attempt. */
internal class SelectedLibraryTipChanged : LibraryTreeUnavailable("Unexpected library tip during catalog admission")

internal suspend fun reconnectRecordedRecipe(
    issuing: LibraryActivation,
    verifyRemembered: suspend () -> Unit,
    recoverVerified: suspend () -> LibraryActivation?,
): LibraryActivation {
    try {
        verifyRemembered()
        return issuing
    } catch (_: SelectedLibraryTipChanged) {
        val recovered = recoverVerified()
        if (recovered == null || recovered.libraryId != issuing.libraryId || recovered.number - 1 != issuing.number) {
            throw LibraryTreeUnavailable("No verified successor to the remembered library")
        }
        return recovered
    }
}
