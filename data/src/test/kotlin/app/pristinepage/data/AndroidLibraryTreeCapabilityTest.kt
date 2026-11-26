package app.pristinepage.data

import app.pristinepage.data.library.AndroidLibraryTreeRead
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertFalse

class AndroidLibraryTreeCapabilityTest {
    @Test fun javaConsumersCannotConstructAnApparentlyVerifiedTreeWithTheirOwnBlobMap() {
        val constructors = AndroidLibraryTreeRead.Verified::class.java.declaredConstructors
        assertFalse(constructors.any { Modifier.isPublic(it.modifiers) && !it.isSynthetic })
    }
}
