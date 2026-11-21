package app.pristinepage.image.provider

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderRegistryTest {
    @get:Rule val temp = TemporaryFolder()
    private val spec = ModelAssetSpec("synthetic", 1, 5, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", null)

    @Test fun knownCandidatesAreDistinctAndMissingFilesHaveNoInstallationSideEffect() {
        val root = temp.root.toPath().resolve("models")
        val entries = ProviderRegistry(ModelAssetStore(root)).states()
        assertEquals(
            listOf("docquad_heatmap_minimal_v1", "paddle_uvdoc_image_source_v1"),
            entries.map { it.manifest.profileId },
        )
        assertEquals(listOf(ProviderRole.DETECTION, ProviderRole.GEOMETRY_IMAGE), entries.map { it.manifest.role })
        assertEquals(listOf(ProviderModelFormat.ORT, ProviderModelFormat.ONNX), entries.map { it.manifest.format })
        assertEquals(listOf(13_404_960L, 31_684_150L), entries.map { it.manifest.asset.byteCount })
        assertEquals(
            listOf(
                "f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a",
                "54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6",
            ),
            entries.map { it.manifest.asset.sha256 },
        )
        assertTrue(entries.all { it.assetStatus == ModelAssetStatus.ABSENT && !it.selectable })
        assertTrue(entries.all { it.manifest.asset.peakInferenceBytes == null })
        assertFalse(entries.last().manifest.supportsPairedProtection)
        assertFalse(Files.exists(root))
    }

    @Test fun verifiedBytesDoNotQualifyTheRuntimeAndCorruptionIsNotAbsence() {
        val root = temp.root.toPath().resolve("models")
        val store = ModelAssetStore(root)
        val registry = registry(store)
        assertEquals(ModelAssetStatus.ABSENT, registry.states().single().assetStatus)
        store.install(spec) { ByteArrayInputStream("hello".encodeToByteArray()) }
        val installed = registry.states().single()
        assertEquals(ModelAssetStatus.VERIFIED, installed.assetStatus)
        assertEquals(ProviderExecutionBlock.ANDROID_RUNTIME_UNQUALIFIED, installed.executionBlock)
        assertFalse(installed.selectable)

        val file = store.acquire(spec).use { it.path }
        Files.write(file, "wrong".encodeToByteArray())
        val corrupt = registry.states().single()
        assertEquals(ModelAssetStatus.INVALID, corrupt.assetStatus)
        assertFalse(corrupt.selectable)
        assertEquals("wrong", Files.readAllBytes(file).decodeToString())
    }

    @Test fun unavailableStorageIsNotReportedAsAnAbsentAsset() {
        val root = temp.newFile("not-a-directory").toPath()
        val state = registry(ModelAssetStore(root)).states().single()
        assertNull(state.assetStatus)
        assertFalse(state.selectable)
        assertTrue(Files.isRegularFile(root))
    }

    private fun registry(store: ModelAssetStore) = ProviderRegistry(
        store,
        listOf(ProviderManifest("synthetic_detector_v1", ProviderRole.DETECTION, ProviderModelFormat.ORT, spec)),
    )
}
