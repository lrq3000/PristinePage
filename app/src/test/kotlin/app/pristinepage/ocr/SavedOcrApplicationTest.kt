package app.pristinepage.ocr

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.image.ocr.SavedOcrPreparer
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedOcrApplicationTest {
    private val codes = listOf("fra", "eng")
    private val config = OcrConfiguration("tesseract", 1, OcrLayout.AUTOMATIC,
        OcrLanguagePacks(listOf(OcrLanguagePack("fra", "1".repeat(64)), OcrLanguagePack("eng", "2".repeat(64)))))
    private val target = SavedOcrWork.Target(PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001"), JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002"))
    private val activation = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000003", 2)
    private val wake = ActivationBoundOcrWake(activation, target)
    private val factory = SavedOcrPreparer.SessionFactory { _, _, _ -> error("Test does not open the native engine") }
    private val session = object : SavedOcrScheduling.QualifiedSession {
        override val activation = (this@SavedOcrApplicationTest).activation
        override val canCreateJobs = true
        override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? = null
        override fun pendingPages() = emptyFlow<SavedOcrWork.Target>()
    }

    @Test fun dormantRuntimeDoesNotReadSettingsOrOpenTheLibraryOnEitherPath() = runBlocking {
        val app = SavedOcrApplication({ null }, { error("Must not read Settings") }, { error("Must not open library") }, { activation })
        assertNull(app.reconciliation())
        assertNull(app.executeSavedOcr(wake))
    }

    @Test fun queueUsesOrderedDefaultsButRefusesSilentModelSubstitution() = runBlocking {
        val seen = mutableListOf<OcrConfiguration?>()
        val sparse = config.copy(layout = OcrLayout.SPARSE_TEXT)
        var installed = sparse
        var mutateRequest = false
        val runtime = SavedOcrApplication.Runtime(factory) { requested, layout ->
            assertEquals(codes, requested)
            assertEquals(OcrLayout.SPARSE_TEXT, layout)
            if (mutateRequest) (requested as MutableList<String>).reverse()
            installed
        }
        val graph = object : SavedOcrApplication.Graph {
            override fun reconcile(defaults: OcrConfiguration?): SavedOcrScheduling.QualifiedSession {
                seen += defaults
                return session
            }
            override suspend fun execute(target: SavedOcrWork.Target, sessions: SavedOcrPreparer.SessionFactory): SavedOcrWork.Outcome =
                error("Enrollment must not start recognition")
        }
        val app = SavedOcrApplication({ runtime }, { SavedOcrApplication.Defaults(codes, OcrLayout.SPARSE_TEXT) }, { graph })
        assertSame(session, app.reconciliation())
        installed = sparse.copy(languagePacks = OcrLanguagePacks(checkNotNull(sparse.languagePacks).ordered.reversed()))
        assertSame(session, app.reconciliation())
        mutateRequest = true
        assertSame(session, app.reconciliation())
        mutateRequest = false
        installed = config
        assertSame(session, app.reconciliation())
        assertEquals(listOf(sparse, null, null, null), seen)
    }

    @Test fun workerPassesTheExactTargetAndFactoryWithoutConsultingDefaults() = runBlocking {
        val runtime = SavedOcrApplication.Runtime(factory) { _, _ -> error("Restart must not consult defaults") }
        val expectedTarget = target
        val graph = object : SavedOcrApplication.Graph {
            override val activation = (this@SavedOcrApplicationTest).activation
            override fun reconcile(defaults: OcrConfiguration?): SavedOcrScheduling.QualifiedSession = error("Worker must not discover unrelated pages")
            override suspend fun execute(target: SavedOcrWork.Target, sessions: SavedOcrPreparer.SessionFactory): SavedOcrWork.Outcome {
                assertEquals(expectedTarget, target)
                assertSame(factory, sessions)
                return SavedOcrWork.Outcome.PUBLISHED
            }
        }
        val app = SavedOcrApplication({ runtime }, { error("Worker must not read Settings") }, { graph }, { activation })
        assertEquals(SavedOcrWork.Outcome.PUBLISHED, app.executeSavedOcr(wake))
    }

    @Test fun oldWakeWithIdenticalLibraryPageAndJobDoesNotOpenRuntimeOrGraph() = runBlocking {
        val app = SavedOcrApplication({ error("Stale work touched the runtime") }, { error("Must not read Settings") },
            { error("Stale work opened a graph") }, { activation })
        assertNull(app.executeSavedOcr(wake.copy(activation = activation.copy(number = 1))))
    }

    @Test fun aSwitchDuringRuntimeResolutionDoesNotOpenTheNewGraphForAnOldWake() = runBlocking {
        var recorded = activation
        val runtime = SavedOcrApplication.Runtime(factory) { _, _ -> error("Must not consult defaults") }
        val app = SavedOcrApplication(
            { recorded = activation.copy(number = 3); runtime }, { error("Must not read Settings") },
            { error("The obsolete wake opened the replacement graph") }, { recorded },
        )
        assertNull(app.executeSavedOcr(wake))
    }

    @Test fun anOldSavedPageSignalCannotOpenTheNewGraphOrVerifyPacks() = runBlocking {
        val app = SavedOcrApplication({ error("A stale save touched the runtime") }, { error("Must not read Settings") },
            { error("A stale save opened a graph") }, { activation })
        assertNull(app.reconciliation(activation.copy(number = 1)))
    }

    @Test fun rerunWithSavedSettingsReverifiesExactPacksAndKeepsTheSavedLayout() = runBlocking {
        val saved = config.copy(layout = OcrLayout.SPARSE_TEXT)
        var available = saved
        val runtime = SavedOcrApplication.Runtime(factory) { requested, layout ->
            assertEquals(listOf("fra", "eng"), requested)
            assertEquals(OcrLayout.SPARSE_TEXT, layout)
            available
        }
        val app = SavedOcrApplication({ runtime }, { error("Saved configuration cannot consult current defaults") }, { error("Resolving a rerun must not open the library") })
        assertEquals(saved, app.resolveRerunConfiguration(saved, useDefaults = false))
        available = config
        assertNull(app.resolveRerunConfiguration(saved, useDefaults = false))
        available = saved.copy(engineVersion = 2)
        assertNull(app.resolveRerunConfiguration(saved, useDefaults = false))
        available = saved.copy(languagePacks = OcrLanguagePacks(listOf(
            OcrLanguagePack("fra", "3".repeat(64)), OcrLanguagePack("eng", "2".repeat(64)))))
        assertNull(app.resolveRerunConfiguration(saved, useDefaults = false))
    }

    @Test fun rerunWithDefaultsUsesTheCurrentOrderedConfigurationWithoutOpeningTheLibrary() = runBlocking {
        val reversed = OcrLanguagePacks(checkNotNull(config.languagePacks).ordered.reversed())
        val current = config.copy(layout = OcrLayout.SINGLE_BLOCK, languagePacks = reversed)
        var available = current
        val runtime = SavedOcrApplication.Runtime(factory) { requested, layout ->
            assertEquals(listOf("eng", "fra"), requested)
            assertEquals(OcrLayout.SINGLE_BLOCK, layout)
            available
        }
        val app = SavedOcrApplication({ runtime }, { SavedOcrApplication.Defaults(listOf("eng", "fra"), OcrLayout.SINGLE_BLOCK) },
            { error("Resolving a rerun must not open the library") })
        assertEquals(current, app.resolveRerunConfiguration(config, useDefaults = true))
        available = current.copy(layout = OcrLayout.AUTOMATIC)
        assertNull(app.resolveRerunConfiguration(config, useDefaults = true))
        available = config.copy(layout = OcrLayout.SINGLE_BLOCK)
        assertNull(app.resolveRerunConfiguration(config, useDefaults = true))
    }

    @Test fun dormantRuntimeDoesNotResolveEitherRerunMode() = runBlocking {
        val app = SavedOcrApplication({ null }, { error("Dormant runtime must not read Settings") }, { error("Must not open library") })
        assertNull(app.resolveRerunConfiguration(config, useDefaults = true))
        assertNull(app.resolveRerunConfiguration(config, useDefaults = false))
    }
}
