package app.pristinepage.capture

/** Select native camera output before taking the still; an imported file is never resized to pass admission. */
internal object CameraStillSizing {
    fun fits(width: Int, height: Int, capacity: Long): Boolean {
        if (width !in 1..12000 || height !in 1..12000) return false
        val pixels = width.toLong() * height
        // Full decode is 14 bytes/pixel. Reserve two more for a typical JPEG plus preview, parser and other small owners.
        // Ingest still validates actual encoded bytes and current shared allocation; this is not an admission waiver.
        val headroom = (capacity / 3).coerceIn(12L * 1024 * 1024, 24L * 1024 * 1024)
        return pixels <= 12_000_000 && 16L * pixels + 4L * maxOf(width, height) + headroom <= capacity
    }
}
