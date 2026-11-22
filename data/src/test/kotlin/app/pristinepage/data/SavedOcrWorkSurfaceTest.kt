package app.pristinepage.data

import app.pristinepage.data.repository.SavedOcrWork
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedOcrWorkSurfaceTest {
    @Test fun javaCallersCannotAssembleAnOcrStoreFromIndependentOwners() {
        val constructors = SavedOcrWork::class.java.declaredConstructors
        assertTrue(constructors.all { it.isSynthetic || Modifier.isPrivate(it.modifiers) })
        assertTrue(SavedOcrWork.Companion::class.java.declaredMethods.none { it.returnType == SavedOcrWork::class.java && !it.isSynthetic })
    }

    @Test fun javaHitSurfaceDoesNotRevealInternalSearchBindings() {
        val callable = SavedOcrWork.Hit::class.java.declaredMethods
            .filterNot { it.isSynthetic || Modifier.isPrivate(it.modifiers) }
        assertTrue(callable.none { it.name.startsWith("getBinding") || it.name.startsWith("getIssuer") })
        assertTrue(SavedOcrWork.Hit::class.java.declaredConstructors.all { it.isSynthetic || Modifier.isPrivate(it.modifiers) })
    }

    @Test fun continuationsHaveNoCallableRawPosition() {
        val type = SavedOcrWork.Continuation::class.java
        assertTrue(type.declaredConstructors.all { it.isSynthetic || Modifier.isPrivate(it.modifiers) })
        assertTrue(type.declaredMethods.all { it.isSynthetic || Modifier.isPrivate(it.modifiers) })
    }

    @Test fun statusDoesNotExposeOrAllowJavaConstructionOfRerunBindings() {
        val type = SavedOcrWork.PageStatus::class.java
        assertTrue(type.declaredConstructors.all { it.isSynthetic || Modifier.isPrivate(it.modifiers) })
        assertTrue(type.declaredMethods.none {
            !it.isSynthetic && !Modifier.isPrivate(it.modifiers) && it.name !in
                setOf("getPhase", "getAttemptsStarted", "getLastError", "getConfiguration")
        })
    }

    @Test fun textExportObservationExposesNeitherTextNorItsBinding() {
        val type = SavedOcrWork.TextExportObservation::class.java
        assertTrue(type.declaredConstructors.all { it.isSynthetic || Modifier.isPrivate(it.modifiers) })
        assertTrue(type.declaredMethods.all { it.isSynthetic || Modifier.isPrivate(it.modifiers) })
        assertTrue(type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.all { Modifier.isPrivate(it.modifiers) })
    }
}
