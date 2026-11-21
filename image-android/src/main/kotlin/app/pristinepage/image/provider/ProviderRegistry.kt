package app.pristinepage.image.provider

import java.io.IOException

internal enum class ProviderRole { DETECTION, GEOMETRY_IMAGE }

internal enum class ProviderModelFormat { ORT, ONNX }

internal enum class ProviderExecutionBlock { ANDROID_RUNTIME_UNQUALIFIED }

/** Pinned candidate metadata, not the release allowlist or permission to open a runtime session. */
internal data class ProviderManifest(
    val profileId: String,
    val role: ProviderRole,
    val format: ProviderModelFormat,
    val asset: ModelAssetSpec,
) {
    // No paired transform is exposed by either candidate. The DocQuad mask is a detection head, not protection.
    val supportsPairedProtection: Boolean get() = false
}

/** A null integrity status means storage could not be inspected; it is not evidence of an absent file. */
internal data class ProviderCandidateState(
    val manifest: ProviderManifest,
    val assetStatus: ModelAssetStatus?,
) {
    val executionBlock: ProviderExecutionBlock get() = ProviderExecutionBlock.ANDROID_RUNTIME_UNQUALIFIED
    val selectable: Boolean get() = false
}

/** Local-only, read-only enumeration. Call off the UI thread: the store hashes the actual installed bytes. */
internal class ProviderRegistry(
    private val store: ModelAssetStore,
    manifests: List<ProviderManifest> = candidates,
) {
    private val entries = manifests.toList()

    fun states(): List<ProviderCandidateState> = entries.map { manifest ->
        val integrity = try {
            store.status(manifest.asset)
        } catch (_: IOException) {
            null
        }
        ProviderCandidateState(manifest, integrity)
    }

    private companion object {
        val candidates = listOf(
            ProviderManifest(
                "docquad_heatmap_minimal_v1", ProviderRole.DETECTION, ProviderModelFormat.ORT,
                ModelAssetSpec(
                    "docquad", 1, 13_404_960L,
                    "f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a", peakInferenceBytes = null,
                ),
            ),
            ProviderManifest(
                "paddle_uvdoc_image_source_v1", ProviderRole.GEOMETRY_IMAGE, ProviderModelFormat.ONNX,
                ModelAssetSpec(
                    "paddle-uvdoc", 1, 31_684_150L,
                    "54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6", peakInferenceBytes = null,
                ),
            ),
        )
    }
}
