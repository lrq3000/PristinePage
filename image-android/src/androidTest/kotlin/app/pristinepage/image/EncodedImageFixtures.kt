package app.pristinepage.image

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Complete local synthetic inputs shared by codec and Room integration tests. No external photograph, font, provider or model is acquired. */
internal object EncodedImageFixtures {
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun be(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    fun chunk(kind: String, data: ByteArray): ByteArray {
        val body = kind.toByteArray() + data; val crc = CRC32().apply { update(body) }
        return be(data.size) + body + be(crc.value.toInt())
    }
    fun png(width: Int, height: Int, depth: Int, color: Int, row: ByteArray, extra: ByteArray = byteArrayOf()): ByteArray {
        val z = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(row) } }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", be(width) + be(height) + byteArrayOf(depth.toByte(), color.toByte(), 0, 0, 0)) + extra + chunk("IDAT", z) + chunk("IEND", byteArrayOf())
    }
    fun rgbaPng(): ByteArray = png(4, 1, 8, 6, byteArrayOf(0, -1, 0, 64, 0, 64, 32, 0, -128, -1, 0, 0, -1, -1, -1, -1, 1))
    fun jpeg(): ByteArray {
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt(), 0xff00ffff.toInt(), 0xffff00ff.toInt())
        val bitmap = Bitmap.createBitmap(colors, 3, 2, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }.toByteArray() } finally { bitmap.recycle() }
    }
    fun exif(jpeg: ByteArray, orientation: Int, type: Int = 3, count: Int = 1, tag: Int = 0x112): ByteArray {
        val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(1).putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putShort(orientation.toShort()).putShort(0).putInt(0).array()
        val payload = "Exif\u0000\u0000".toByteArray() + tiff
        return jpeg.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (payload.size + 2).toByte()) + payload + jpeg.copyOfRange(2, jpeg.size)
    }
}
