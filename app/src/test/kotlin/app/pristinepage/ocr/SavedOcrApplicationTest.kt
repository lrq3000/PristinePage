package app.pristinepage.ocr

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
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
    private val factory = SavedOcrPreparer.SessionFactory { _, _ -> error("Test does not open the native engine") }
    private val session = object : SavedOcrScheduling.QualifiedSession {
        override suspend fun reconcilePage(document: DocumentId, page: PageId): SavedOcrWork.Target? = null
        override fun pendingPages() = emptyFlow<SavedOcrWork.Target>()
    }

    @Test fun dormantRuntimeDoesNotReadSettingsOrOpenTheLibraryOnEitherPath() = runBlocking {
        val app = SavedOcrApplication({ null }, { error("Must not read Settings") }, { error("Must not open library") })
        assertNull(app.reconciliation())
        assertNull(app.executeSavedOcr(target))
    }

    @Test fun queueUsesOrderedDefaultsButRefusesSilentModelSubstitution() = runBlocking {
        val seen = mutableListOf<OcrConfiguration?>()
        var installed = config
        var mutateRequest = false
        val runtime = SavedOcrApplication.Runtime(factory) { requested ->
            assertEquals(codes, requested)
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
        val app = SavedOcrApplication({ runtime }, { codes }, { graph })
        assertSame(session, app.reconciliation())
        installed = config.copy(languagePacks = OcrLanguagePacks(checkNotNull(config.languagePacks).ordered.reversed()))
        assertSame(session, app.reconciliation())
        mutateRequest = true
        assertSame(session, app.reconciliation())
        assertEquals(listOf(config, null, null), seen)
    }

    @Test fun workerPassesTheExactTargetAndFactoryWithoutConsultingDefaults() = runBlocking {
        val runtime = SavedOcrApplication.Runtime(factory) { error("Restart must not consult defaults") }
        val graph = object : SavedOcrApplication.Graph {
            override fun reconcile(defaults: OcrConfiguration?): SavedOcrScheduling.QualifiedSession = error("Worker must not discover unrelated pages")
            override suspend fun execute(target: SavedOcrWork.Target, sessions: SavedOcrPreparer.SessionFactory): SavedOcrWork.Outcome {
                assertEquals(this@SavedOcrApplicationTest.target, target)
                assertSame(factory, sessions)
                return SavedOcrWork.Outcome.PUBLISHED
            }
        }
        val app = SavedOcrApplication({ runtime }, { error("Worker must not read Settings") }, { graph })
        assertEquals(SavedOcrWork.Outcome.PUBLISHED, app.executeSavedOcr(target))
    }
}
