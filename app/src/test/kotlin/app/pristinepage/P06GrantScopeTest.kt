package app.pristinepage

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** Exact fixture authorization precedes every opener, not a post-read hash filter. Synthetic URI strings never reach Android/provider IO in these host regressions. */
class P06GrantScopeTest {
    private val allowed = listOf("content://media/external/images/media/39","content://media/external/images/media/40")
    private fun read(observed: List<String>, permission: (String) -> Boolean = { true }, opens: MutableList<String>): List<String> {
        val kind = try { Class.forName("app.pristinepage.P06GrantScope") } catch (_: ClassNotFoundException) { throw AssertionError("Exact pre-open fixture guard is absent") }
        return try {
            @Suppress("UNCHECKED_CAST")
            (kind.getMethod("read",List::class.java,List::class.java,kotlin.jvm.functions.Function1::class.java,kotlin.jvm.functions.Function1::class.java).invoke(null,allowed,observed,permission,{ uri: String -> opens += uri; "fixture-" + uri.substringAfterLast('/') }) as List<String>)
        } catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    @Test fun exactAuthenticatedPairCanOpenOnlyAfterTheEntirePermissionSetPasses() {
        val opened = mutableListOf<String>(); val checked = mutableListOf<String>()
        assertEquals(listOf("fixture-40","fixture-39"),read(allowed.reversed(),{ uri -> assertTrue(opened.isEmpty()); checked += uri; true },opened))
        assertEquals(allowed.reversed(),opened); assertEquals(allowed.toSet(),checked.toSet())
    }

    @Test fun extraOldWrongMalformedForeignOrDuplicatedUrisCauseZeroOpens() {
        read(allowed,opens = mutableListOf()) // Missing guard is a first-order RED, not accepted as an expected refusal.
        for (observed in listOf(allowed + "content://unrelated.example/private/7",listOf("content://media/external/images/media/7","content://media/external/images/media/8"),listOf(allowed[0],"malformed"),listOf(allowed[0],"content://other-user@media/external/images/media/40"),listOf(allowed[0],allowed[0]),listOf(allowed[0]))) {
            val opened = mutableListOf<String>()
            assertThrows(IllegalArgumentException::class.java) { read(observed,opens = opened) }; assertTrue(opened.isEmpty())
        }
    }

    @Test fun secondMissingReadOrUnexpectedWritePermissionStopsBeforeEitherOpen() {
        read(allowed,opens = mutableListOf())
        val opened = mutableListOf<String>()
        assertThrows(IllegalArgumentException::class.java) { read(allowed,{ it != allowed[1] },opened) }
        assertTrue(opened.isEmpty())
    }
}
