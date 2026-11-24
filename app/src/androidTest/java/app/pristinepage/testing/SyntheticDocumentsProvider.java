package app.pristinepage.testing;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** DocumentsUI starts this fixture in the test APK's own process, without the target APK or its Kotlin runtime. Keep it platform-only. */
public final class SyntheticDocumentsProvider extends DocumentsProvider {
    private static final String AUTHORITY = "app.pristinepage.debug.test.documents";
    private static final String ROOT_ID = "generated-fixtures-v1";
    private static final String A = "original-a";
    private static final String B = "original-b";
    private static final String CORRUPT = "corrupt";
    private static final String HDR = "sdr-primary";
    public static final String HDR_NAME = "PristinePage SDR fixture.jpg";
    private static final String[] DOCUMENT_IDS = {A, B, CORRUPT, HDR};
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor rows = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = rows.newRow();
        for (String column : rows.getColumnNames()) {
            row.add(switch (column) {
                case Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> ROOT_ID;
                case Root.COLUMN_TITLE -> "PristinePage QA";
                case Root.COLUMN_SUMMARY -> "Generated local test images";
                case Root.COLUMN_MIME_TYPES -> "image/png\nimage/jpeg";
                case Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY;
                default -> null;
            });
        }
        return rows;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor rows = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        append(rows, documentId);
        return rows;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) throw new FileNotFoundException("Unknown fixture directory");
        MatrixCursor rows = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        for (String id : DOCUMENT_IDS) append(rows, id);
        return rows;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_ID.equals(parentDocumentId) && isFixture(documentId);
    }

    @Override
    public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Fixtures are read-only");
        if (signal != null) signal.throwIfCanceled();
        return openPipeHelper(DocumentsContract.buildDocumentUri(AUTHORITY, id), mime(id), null,
                encoded(id), (descriptor, uri, mime, options, data) -> {
                    try (ParcelFileDescriptor.AutoCloseOutputStream output =
                                 new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                        output.write(data);
                    } catch (IOException closedReader) {
                        // The pipe can close when DocumentsUI cancels. There is no persistent output to retry.
                    }
                });
    }

    private static boolean isFixture(String id) {
        return A.equals(id) || B.equals(id) || CORRUPT.equals(id) || HDR.equals(id);
    }

    private static String mime(String id) {
        return HDR.equals(id) ? "image/jpeg" : "image/png";
    }

    private static void append(MatrixCursor rows, String id) throws FileNotFoundException {
        boolean root = ROOT_ID.equals(id);
        if (!root && !isFixture(id)) throw new FileNotFoundException("Unknown fixture document");
        MatrixCursor.RowBuilder row = rows.newRow();
        for (String column : rows.getColumnNames()) {
            row.add(switch (column) {
                case Document.COLUMN_DOCUMENT_ID -> id;
                case Document.COLUMN_DISPLAY_NAME -> switch (id) {
                    case ROOT_ID -> "Generated fixtures";
                    case A -> "Original A.png";
                    case B -> "Original B.png";
                    case HDR -> HDR_NAME;
                    default -> "Corrupt image.png";
                };
                case Document.COLUMN_MIME_TYPE -> root ? Document.MIME_TYPE_DIR : mime(id);
                case Document.COLUMN_FLAGS -> 0;
                case Document.COLUMN_SIZE -> root ? null : (long) encoded(id).length;
                case Document.COLUMN_LAST_MODIFIED -> 0L;
                default -> null;
            });
        }
    }

    private static byte[] encoded(String id) throws FileNotFoundException {
        if (CORRUPT.equals(id)) return new byte[] {1, 2, 3, 4};
        if (HDR.equals(id)) return generateHdrJpeg();
        if (!A.equals(id) && !B.equals(id)) throw new FileNotFoundException("Unknown fixture document");
        Bitmap bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(A.equals(id) ? 0xff641428 : 0xff1464c8);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Fixture PNG encoding failed");
            }
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    /** Generated in the provider process, with the same independent expectation available to the instrumentation. */
    public static byte[] generateHdrJpeg() {
        byte[] primary = jpeg(48, 32, 0xff5baac8);
        byte[] gain = jpeg(24, 16, 0xff404040);
        byte[] xmp = segment(0xe1, ("http://ns.adobe.com/xap/1.0/\0" +
            "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>" +
            "<rdf:Description xmlns:hdrgm='http://ns.adobe.com/hdr-gain-map/1.0/' " +
            "hdrgm:Version='1.0' hdrgm:BaseRenditionIsHDR='False'/></rdf:RDF>").getBytes(StandardCharsets.UTF_8));
        int primaryEnd = primary.length + xmp.length + 90;
        int gainSize = gain.length + xmp.length;
        ByteBuffer tiff = ByteBuffer.allocate(82).putInt(0x4d4d002a).putInt(8).putShort((short) 3);
        tiff.putShort((short) 0xb000).putShort((short) 7).putInt(4).put(new byte[] {'0', '1', '0', '0'});
        tiff.putShort((short) 0xb001).putShort((short) 4).putInt(1).putInt(2);
        tiff.putShort((short) 0xb002).putShort((short) 7).putInt(32).putInt(50).putInt(0);
        tiff.putInt(0x030000).putInt(primaryEnd).putInt(0).putInt(0);
        tiff.putInt(0).putInt(gainSize).putInt(primaryEnd - 2 - xmp.length - 8).putInt(0);
        byte[] mpf = segment(0xe2, ByteBuffer.allocate(86).putInt(0x4d504600).put(tiff.array()).array());
        return ByteBuffer.allocate(primaryEnd + gainSize)
            .put(primary, 0, 2).put(xmp).put(mpf).put(primary, 2, primary.length - 2)
            .put(gain, 0, 2).put(xmp).put(gain, 2, gain.length - 2).array();
    }

    private static byte[] jpeg(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(color);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) throw new IllegalStateException("Fixture JPEG encoding failed");
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] segment(int marker, byte[] bytes) {
        return ByteBuffer.allocate(bytes.length + 4).put((byte) 0xff).put((byte) marker)
            .putShort((short) (bytes.length + 2)).put(bytes).array();
    }
}
