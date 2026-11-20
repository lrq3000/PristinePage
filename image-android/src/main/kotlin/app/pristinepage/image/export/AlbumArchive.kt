package app.pristinepage.image.export

import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** ZIP assembly borrows its staged sink, never treats an entry name as a path, and verifies every member in order. */
internal object AlbumArchive {
    data class Entry(val name: String, val size: Long, val sha256: String)

    fun write(sink: OutputStream, names: List<String>, checkpoint: () -> Unit, writePage: (Int, OutputStream) -> Unit): List<Entry> {
        require(names.size in 2..100 && names.toSet().size == names.size)
        names.forEach { name ->
            require(name.endsWith(".jpg") && name.encodeToByteArray().size <= 255 && name.none { it.isISOControl() || it in "/\\:" })
        }
        return ZipOutputStream(object : FilterOutputStream(sink) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = Unit // The journal owns sink validation and close.
        }).use { zip ->
            // JPEG is already compressed. Keep data descriptors without spending CPU recompressing each scan.
            zip.setLevel(Deflater.NO_COMPRESSION)
            names.mapIndexed { i, name ->
                checkpoint()
                zip.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                writePage(i, object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        checkpoint()
                        size = Math.addExact(size, count.toLong())
                        zip.write(bytes, offset, count)
                        digest.update(bytes, offset, count)
                    }
                })
                zip.closeEntry()
                require(size > 0)
                Entry(name, size, digest.hex())
            }
        }
    }

    fun verify(file: File, entries: List<Entry>, checkpoint: () -> Unit) {
        ZipFile(file).use { zip ->
            require(zip.size() == entries.size)
            val actual = zip.entries()
            for (entry in entries) {
                checkpoint()
                val next = actual.nextElement()
                require(!next.isDirectory && next.name == entry.name && next.size == entry.size)
                require(zip.getInputStream(next).use { it.hashExactly(entry.size, checkpoint) } == entry.sha256)
            }
            require(!actual.hasMoreElements())
        }
    }
}

internal fun InputStream.hashExactly(size: Long, checkpoint: () -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65_536)
    var remaining = size
    while (remaining > 0) {
        checkpoint()
        val count = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        require(count > 0)
        digest.update(buffer, 0, count)
        remaining -= count
    }
    require(read() == -1)
    return digest.hex()
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 255) }
