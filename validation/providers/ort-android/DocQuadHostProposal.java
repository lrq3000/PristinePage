import app.pristinepage.core.geometry.DocQuadGeometry;
import app.pristinepage.core.pipeline.DetectionProposal;
import app.pristinepage.core.pipeline.PixelSize;
import app.pristinepage.core.pipeline.PointPx;
import app.pristinepage.core.pipeline.QuadPx;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import kotlin.Unit;

/** Applies the exact app-side proposal decoder to the desktop probe's detached synthetic heads. */
public final class DocQuadHostProposal {
    private static final int CELLS = 64 * 64;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Pass the directory of synthetic head fixtures");
        Path root = Paths.get(args[0]);
        for (String label : new String[] {"black", "synthetic_page", "narrow_page"}) {
            DetectionProposal p = load(root.resolve(label + ".head"));
            if (label.equals("narrow_page")) {
                if (!p.getDetected()) throw new IllegalStateException("The positive control was refused: " + p.getReason());
                QuadPx q = p.getQuad();
                close(q.getTl(), 71, 32);
                close(q.getTr(), 192, 32);
                close(q.getBr(), 192, 228);
                close(q.getBl(), 71, 228);
                System.out.printf("%s: accepted, score=%.4f, quad=%s%n", label, p.getScore(), q);
            } else {
                if (p.getDetected() || p.getQuad() != null)
                    throw new IllegalStateException("Unsafe synthetic fixture unexpectedly proposed a quad");
                System.out.printf("%s: refused (%s)%n", label, p.getReason());
            }
        }
    }

    private static DetectionProposal load(Path path) throws IOException {
        byte[] bytes;
        try (java.io.InputStream stream = Files.newInputStream(path)) {
            bytes = stream.readNBytes(5 * CELLS * 4 + 1);
        }
        if (bytes.length != 5 * CELLS * 4) throw new IOException("Wrong synthetic head extent");
        FloatBuffer reader = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] mask = new float[CELLS];
        float[] corners = new float[CELLS * 4];
        reader.get(mask);
        reader.get(corners);
        return DocQuadGeometry.INSTANCE.propose(new PixelSize(256, 256), mask, corners, () -> Unit.INSTANCE);
    }

    private static void close(PointPx point, int x, int y) {
        if (Math.abs(point.getX() - x) > 8 || Math.abs(point.getY() - y) > 8)
            throw new IllegalStateException("Synthetic landmark outside its 8-pixel tolerance: " + point);
    }
}
