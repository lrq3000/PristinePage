package app.pristinepage.image.export

import app.pristinepage.data.repository.NamedAlbumExports
import java.io.File
import java.io.OutputStream

/** One synchronous writer; release its native ownership before the journal reopens the closed bytes. */
internal class PlatformPdfArtifact(
    writer: AutoCloseable,
    evidence: AutoCloseable,
    write: (OutputStream, () -> Unit) -> Unit,
    readback: (File, () -> Unit) -> Unit,
) : NamedAlbumExports.PreparedArtifact {
    private var writerOwner: AutoCloseable? = writer
    private var evidenceOwner: AutoCloseable? = evidence
    private var writeOnce: ((OutputStream, () -> Unit) -> Unit)? = write
    private var verifyClosed: ((File, () -> Unit) -> Unit)? = readback
    private var written = false

    override fun write(sink: OutputStream, checkpoint: () -> Unit) {
        val action = checkNotNull(writeOnce)
        val owner = checkNotNull(writerOwner)
        writeOnce = null
        writerOwner = null
        owner.use {
            checkpoint()
            action(sink, checkpoint)
            checkpoint()
        }
        written = true
    }

    override fun verify(closed: File, checkpoint: () -> Unit) {
        check(written)
        checkNotNull(verifyClosed)(closed, checkpoint)
    }

    override fun close() {
        val writer = writerOwner
        val evidence = evidenceOwner
        writerOwner = null
        evidenceOwner = null
        writeOnce = null
        verifyClosed = null
        evidence.use { writer?.close() }
    }
}
