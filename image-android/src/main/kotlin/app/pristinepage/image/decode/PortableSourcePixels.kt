package app.pristinepage.image.decode

import app.pristinepage.core.image.RgbImage
import app.pristinepage.data.model.SourceInspection

/** Validate the actual newly normalized RGB under the recorded identity, not under a new build fingerprint. Encoded-byte verification belongs to the caller. */
internal object PortableSourcePixels {
    private val fingerprint = Regex(
        """android-imagedecoder-v2/api([1-9][0-9]{0,5})/[a-f0-9]{16}/srgb-straight-white-exif-v1(/[-a-z0-9]+)?""",
    )

    fun proves(recorded: SourceInspection, observed: SourceInspection, image: RgbImage, checkpoint: () -> Unit = {}): Boolean {
        val recordedPolicy = policy(recorded) ?: return false
        val observedPolicy = policy(observed) ?: return false
        if (recordedPolicy != observedPolicy || recorded.mime != observed.mime || recorded.encodedSize != observed.encodedSize ||
            recorded.normalizedSize != observed.normalizedSize || recorded.exifOrientation != observed.exifOrientation ||
            recorded.profileSha256 != observed.profileSha256 ||
            recorded.normalizedSize.width != image.width || recorded.normalizedSize.height != image.height
        ) return false
        return CanonicalSourcePixels.hash(image, recorded.conversionFingerprint, checkpoint) == recorded.sourcePixelHash
    }

    private fun policy(inspection: SourceInspection): String? {
        val identity = fingerprint.matchEntire(inspection.conversionFingerprint) ?: return null
        if (identity.groupValues[1].toInt() < 28) return null
        val profiled = inspection.profileSha256 != null
        val jpeg = inspection.mime == "image/jpeg"
        val suffix = identity.groupValues[2]
        val known = when (suffix) {
            "" -> !profiled && (jpeg || inspection.mime == "image/png" || inspection.mime == "image/webp")
            SourceDecodePolicy.CAMERA_JPEG -> jpeg && !profiled
            SourceDecodePolicy.HDR_SDR_PRIMARY -> jpeg
            SourceDecodePolicy.PROFILED_SRGB -> profiled && (jpeg || inspection.mime == "image/png")
            else -> false
        }
        return suffix.takeIf { known }
    }
}
