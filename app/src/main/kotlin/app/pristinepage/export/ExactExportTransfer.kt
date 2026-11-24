package app.pristinepage.export

import app.pristinepage.core.io.transferExactBytes
import java.io.InputStream
import java.io.OutputStream
internal fun transferExactExport(
    input: InputStream, output: OutputStream?, byteCount: Long, sha256: String,
    checkpoint: () -> Unit, wrote: (Int) -> Unit = {},
) = transferExactBytes(input, output, byteCount, sha256, checkpoint, wrote)
