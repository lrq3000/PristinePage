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

/** DocumentsUI starts this fixture in the test APK's own process, without the target APK or its Kotlin runtime. Keep it platform-only. */
public final class SyntheticDocumentsProvider extends DocumentsProvider {
    private static final String AUTHORITY = "app.pristinepage.debug.test.documents";
    private static final String ROOT_ID = "generated-fixtures-v1";
    private static final String A = "original-a";
    private static final String B = "original-b";
    private static final String CORRUPT = "corrupt";
    private static final String[] DOCUMENT_IDS = {A, B, CORRUPT};
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
                case Root.COLUMN_MIME_TYPES -> "image/png";
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
        return openPipeHelper(DocumentsContract.buildDocumentUri(AUTHORITY, id), "image/png", null,
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
        return A.equals(id) || B.equals(id) || CORRUPT.equals(id);
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
                    default -> "Corrupt image.png";
                };
                case Document.COLUMN_MIME_TYPE -> root ? Document.MIME_TYPE_DIR : "image/png";
                case Document.COLUMN_FLAGS -> 0;
                case Document.COLUMN_SIZE -> root ? null : (long) encoded(id).length;
                case Document.COLUMN_LAST_MODIFIED -> 0L;
                default -> null;
            });
        }
    }

    private static byte[] encoded(String id) throws FileNotFoundException {
        if (CORRUPT.equals(id)) return new byte[] {1, 2, 3, 4};
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
}
