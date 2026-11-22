package app.pristinepage.image;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test-only instrumentation source; the normal Gradle source sets neither compile nor package this class. */
@RunWith(AndroidJUnit4.class)
public final class LocalTesseractProbeTest {
    private static final String ROOT = "ocr-probe/";
    private static final List<String> CODES = Arrays.asList("eng", "fra", "spa", "deu", "ita", "por", "nld", "pol", "tur", "rus", "ukr", "ell",
        "ara", "heb", "hin", "chi_sim", "jpn", "kor", "vie", "ind");

    @Test public void recognizesTheTwentyPinnedLanguagesAndCrossScriptControls() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] permissions = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
        assertTrue(permissions == null || !Arrays.asList(permissions).contains("android.permission.INTERNET"));
        JSONObject manifest = new JSONObject(new String(load(context, ROOT + "manifest.json", 32768), StandardCharsets.UTF_8));
        File directory = new File(context.getFilesDir(), "ocr-probe-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        File tessdata = new File(directory, "tessdata");
        assertTrue(tessdata.mkdir());
        JSONArray packs = manifest.getJSONArray("packs");
        assertEquals(CODES.size(), packs.length());
        for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.getJSONObject(i);
            String code = pack.getString("code");
            assertEquals(CODES.get(i), code);
            stagePack(context, tessdata, code, pack);
        }

        List<String> caseIds = new ArrayList<>(CODES);
        caseIds.addAll(Arrays.asList("eng-fra", "jpn-eng", "ara-eng", "blank"));
        JSONArray cases = manifest.getJSONArray("cases");
        assertEquals(caseIds.size(), cases.length());
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.length(); i++) {
            JSONObject test = cases.getJSONObject(i);
            String id = test.getString("id");
            assertEquals(caseIds.get(i), id);
            String language = id.equals("blank") ? "eng" : id.replace('-', '+');
            assertEquals(language, test.getString("languages"));
            byte[] image = load(context, ROOT + "controls/" + id + ".png", 1_048_576);
            assertEquals(test.getInt("imageBytes"), image.length);
            assertEquals(test.getString("imageSha256"), hex(MessageDigest.getInstance("SHA-256").digest(image)));
            Bitmap bitmap = decodeControl(image, id.equals("blank") ? 128 : id.contains("-") ? 336 : 232);
            long started = System.nanoTime();
            String recognized;
            // This single-line Japanese control has an independently measured sparse-layout witness; this is not an application fallback or default.
            int mode = id.equals("jpn") ? TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT : TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK;
            try { recognized = recognize(directory, language, mode, bitmap); }
            finally { bitmap.recycle(); }
            String expected = test.getString("expected");
            String actual = recognized == null || recognized.length() > 512 ? null : normalize(recognized);
            int edits = actual == null ? -1 : distance(expected, actual);
            int extent = actual == null ? 0 : Math.max(expected.length(), actual.length());
            // One imperfect Arabic/English control is measured rather than reported as exact. Both scripts must survive.
            boolean mixed = id.equals("ara-eng");
            boolean bothScripts = actual != null && actual.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC)
                && actual.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
            boolean pass = id.equals("blank") ? "".equals(recognized) : edits == 0 || (mixed && bothScripts && edits >= 0 && edits * 20 <= extent);
            android.util.Log.i("PristinePageOcrProbe", "case=" + id + " edits=" + edits + " length=" + extent
                + " exact=" + (edits == 0) + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000L + " mode=" + mode);
            if (!pass) failures.add(id + " (edits=" + edits + ", length=" + extent + ")");
        }
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test public void diagnoseJapaneseWithTheSamePixelsAcrossSupportedLayouts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject manifest = new JSONObject(new String(load(context, ROOT + "manifest.json", 32768), StandardCharsets.UTF_8));
        File directory = new File(context.getFilesDir(), "ocr-probe-jpn-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        File tessdata = new File(directory, "tessdata");
        assertTrue(tessdata.mkdir());
        int index = CODES.indexOf("jpn");
        stagePack(context, tessdata, "jpn", manifest.getJSONArray("packs").getJSONObject(index));
        JSONObject control = manifest.getJSONArray("cases").getJSONObject(index);
        assertEquals("jpn", control.getString("id"));
        byte[] image = load(context, ROOT + "controls/jpn.png", 1_048_576);
        assertEquals(control.getString("imageSha256"), hex(MessageDigest.getInstance("SHA-256").digest(image)));
        String expected = control.getString("expected");
        Bitmap bitmap = decodeControl(image, 232);
        try {
            for (int mode : new int[] {TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, TessBaseAPI.PageSegMode.PSM_AUTO, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT}) {
                String actual = recognize(directory, "jpn", mode, bitmap);
                assertNotNull("Japanese control produced no OCR output", actual);
                assertTrue(actual.length() <= 512);
                actual = normalize(actual);
                int edits = distance(expected, actual);
                android.util.Log.i("PristinePageOcrJpn", "mode=" + mode + " edits=" + edits
                    + " punctuationOnly=" + expected.replaceAll("\\p{P}", "").equals(actual.replaceAll("\\p{P}", ""))
                    + " receiptAnchor=" + actual.contains("領収書"));
                if (mode == TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT) {
                    assertEquals(0, edits);
                    assertTrue(actual.contains("領収書"));
                }
            }
        } finally { bitmap.recycle(); }
    }

    static void stagePack(Context context, File tessdata, String code, JSONObject pack) throws Exception {
        assertEquals(code, pack.getString("code"));
        String fileName = code + ".traineddata";
        int expected = pack.getInt("bytes");
        assertTrue(expected > 0 && expected <= 10_000_000);
        File target = new File(tessdata, fileName);
        assertTrue(target.createNewFile());
        int count = 0;
        byte[] buffer = new byte[65536];
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = context.getAssets().open(ROOT + "tessdata/" + fileName);
             FileOutputStream output = new FileOutputStream(target)) {
            int n;
            while ((n = input.read(buffer)) > 0) {
                count += n;
                assertTrue(count <= expected);
                hash.update(buffer, 0, n);
                output.write(buffer, 0, n);
            }
            output.getFD().sync();
        }
        assertEquals(expected, count);
        assertEquals(pack.getString("sha256"), hex(hash.digest()));
        assertEquals(expected, target.length());
        hash.reset();
        count = 0;
        try (InputStream closedFile = new FileInputStream(target)) {
            int n;
            while ((n = closedFile.read(buffer)) > 0) {
                count += n;
                assertTrue(count <= expected);
                hash.update(buffer, 0, n);
            }
        }
        assertEquals(expected, count);
        assertEquals(pack.getString("sha256"), hex(hash.digest()));
    }

    private static String recognize(File directory, String language, int mode, Bitmap bitmap) {
        TessBaseAPI engine = new TessBaseAPI();
        try {
            assertEquals("5.5.1", engine.getVersion());
            if (!engine.init(directory.getAbsolutePath(), language, TessBaseAPI.OEM_LSTM_ONLY)) return null;
            assertTrue(engine.setVariable("user_defined_dpi", "300"));
            engine.setPageSegMode(mode);
            engine.setImage(bitmap);
            return engine.getUTF8Text();
        } finally { engine.recycle(); }
    }

    private static Bitmap decodeControl(byte[] image, int height) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        assertEquals(1500, bounds.outWidth);
        assertEquals(height, bounds.outHeight);
        assertEquals("image/png", bounds.outMimeType);
        Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
        assertNotNull(bitmap);
        assertEquals(1500, bitmap.getWidth());
        assertEquals(height, bitmap.getHeight());
        return bitmap;
    }

    static byte[] load(Context context, String name, int maximum) throws Exception {
        try (InputStream input = context.getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = input.read(buffer)) > 0) {
                assertTrue(output.size() + n <= maximum);
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }

    static String hex(byte[] digest) {
        StringBuilder code = new StringBuilder();
        for (byte value : digest) code.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return code.toString();
    }

    static String normalize(String text) {
        StringBuilder folded = new StringBuilder();
        Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFC).codePoints()
            .filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)).forEach(folded::appendCodePoint);
        return folded.toString();
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int replacement = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(replacement, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
