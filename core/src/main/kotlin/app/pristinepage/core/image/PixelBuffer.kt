package app.pristinepage.core.image

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A lease retains storage, not a mutable image. Storage becomes unreachable only after its last independently closeable lease ends. */
private class SharedStorage<T : Any>(initial: T) {
    @Volatile private var value: T? = initial
    private val references = AtomicInteger(1)
    @Synchronized fun retain(): SharedStorage<T> { check(value != null); references.incrementAndGet(); return this }
    fun read(): T = checkNotNull(value) { "Buffer storage is closed" }
    @Synchronized fun release() { if (references.decrementAndGet() == 0) value = null }
}

/** Immutable encoded-sRGB RGB8. Public construction/access defensively copies; stride padding never contributes to pixel equality. Extents are checked before copying or allocation. */
class RgbImage private constructor(val width: Int, val height: Int, val rowStride: Int, private val storage: SharedStorage<ByteArray>) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    constructor(width: Int, height: Int, rowStride: Int, pixels: ByteArray) : this(width, height, rowStride, checkedStorage(width, height, rowStride, pixels))

    val pixels: ByteArray get() = read().copyOf()
    private fun read(): ByteArray { check(!closed.get()) { "Image lease is closed" }; return storage.read() }

    fun getUnsigned(x: Int, y: Int, channel: Int): Int {
        if (x !in 0 until width || y !in 0 until height || channel !in 0..2) throw IndexOutOfBoundsException("RGB sample outside image")
        return read()[y * rowStride + x * 3 + channel].toInt() and 255
    }

    fun toPackedByteArray(): ByteArray {
        val input = read()
        val rowBytes = width * 3
        val output = ByteArray(rowBytes * height)
        for (y in 0 until height) input.copyInto(output, y * rowBytes, y * rowStride, y * rowStride + rowBytes)
        return output
    }

    fun retain(): RgbImage { read(); return RgbImage(width, height, rowStride, storage.retain()) }
    override fun close() { if (closed.compareAndSet(false, true)) storage.release() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RgbImage || width != other.width || height != other.height) return false
        val a = read(); val b = other.read()
        for (y in 0 until height) for (x in 0 until width * 3) if (a[y * rowStride + x] != b[y * other.rowStride + x]) return false
        return true
    }

    override fun hashCode(): Int {
        val bytes = read()
        var result = 31 * width + height
        for (y in 0 until height) for (x in 0 until width * 3) result = 31 * result + bytes[y * rowStride + x]
        return result
    }

    companion object {
        private fun checkedStorage(width: Int, height: Int, stride: Int, pixels: ByteArray): SharedStorage<ByteArray> {
            require(width > 0 && height > 0) { "Image dimensions must be positive" }
            val activeRow = width.toLong() * 3
            require(activeRow <= Int.MAX_VALUE && stride.toLong() >= activeRow) { "Invalid RGB row stride" }
            // A final row needs its active bytes, not unused trailing padding. Int dimensions/stride make this product safe in Long.
            val extent = (height.toLong() - 1) * stride + activeRow
            require(extent <= pixels.size.toLong()) { "RGB storage is too short" }
            return SharedStorage(pixels.copyOf())
        }
    }
}

/** Float32 masks retain their scalar precision. Protection and page coverage have distinct runtime types to prevent interchange. */
sealed class FloatMask private constructor(val width: Int, val height: Int, private val storage: SharedStorage<FloatArray>) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    protected constructor(width: Int, height: Int, samples: FloatArray) : this(width, height, checkedStorage(width, height, samples))
    protected constructor(parent: FloatMask) : this(parent.width, parent.height, parent.retainedStorage())
    private fun read(): FloatArray { check(!closed.get()) { "Mask lease is closed" }; return storage.read() }
    private fun retainedStorage(): SharedStorage<FloatArray> { read(); return storage.retain() }
    val samples: FloatArray get() = read().copyOf()
    fun sample(x: Int, y: Int): Float {
        if (x !in 0 until width || y !in 0 until height) throw IndexOutOfBoundsException("Mask sample outside image")
        return read()[y * width + x]
    }
    abstract fun retain(): FloatMask
    override fun close() { if (closed.compareAndSet(false, true)) storage.release() }

    companion object {
        private fun checkedStorage(width: Int, height: Int, samples: FloatArray): SharedStorage<FloatArray> {
            require(width > 0 && height > 0 && width.toLong() * height == samples.size.toLong()) { "Invalid mask extent" }
            require(samples.all { it.isFinite() && it in 0f..1f }) { "Mask values must be finite in0..1" }
            return SharedStorage(samples.copyOf())
        }
    }
}

class ProtectionMask : FloatMask {
    constructor(width: Int, height: Int, samples: FloatArray) : super(width, height, samples)
    private constructor(parent: ProtectionMask) : super(parent)
    override fun retain(): ProtectionMask = ProtectionMask(this)
}

class CoverageMask : FloatMask {
    constructor(width: Int, height: Int, samples: FloatArray) : super(width, height, samples)
    private constructor(parent: CoverageMask) : super(parent)
    override fun retain(): CoverageMask = CoverageMask(this)
}
