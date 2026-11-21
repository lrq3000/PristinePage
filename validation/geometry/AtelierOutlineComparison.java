import app.pristinepage.core.geometry.AtelierEdgeDetector;
import app.pristinepage.core.pipeline.PointPx;
import app.pristinepage.core.pipeline.QuadPx;
import java.util.Locale;
import kotlin.jvm.functions.Function2;

/** Run this source in separate JVMs with the baseline and current core JARs, plus the pinned Kotlin stdlib. Units are preview pixels, not time or full-source accuracy. */
class AtelierOutlineComparison {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final double MAX_CORNER_ERROR = 4.0;

    private record Scene(String name, double[][] corners, Function2<Integer, Integer, Integer> luminance) {}

    public static void main(String[] args) {
        var scenes = new Scene[] {
            paper("high-contrast", quad(23.5, 19.5, 136.5, 100.5), 44, 175, false),
            paper("close-framed", quad(3.5, 20.5, 155.5, 98.5), 40, 180, false),
            paper("soft-outline", new double[][] {{22, 21}, {142, 21}, {134, 97}, {30, 97}}, 122, 24, true),
            paper("dark-soft-outline", new double[][] {{22, 21}, {142, 21}, {134, 97}, {30, 97}}, 147, -24, true),
            paper("narrow-receipt", quad(57.5, 8.5, 99.5, 110.5), 60, 140, false),
            paper("rotated-page", new double[][] {{53, 14}, {139, 48}, {101, 107}, {15, 73}}, 48, 170, false),
            new Scene("distracting-strips", quad(28.5, 20.5, 129.5, 98.5), (x, y) ->
                x >= 29 && x <= 129 && y >= 21 && y <= 98 ? 162 :
                (x >= 7 && x <= 9) || (x >= 149 && x <= 151) || (y >= 6 && y <= 8) || (y >= 109 && y <= 111) ? 40 : 141),
            new Scene("clipped-with-interior-rules", null, (x, y) ->
                x <= 130 && y >= 21 && y <= 98 ? ((x >= 20 && x <= 22) || (x >= 24 && x <= 25) ? 24 : 162) : 141),
            new Scene("shadow-gradient-no-page", null, (x, y) -> 90 + x / 2 + y / 3),
        };
        for (Scene scene : scenes) {
            QuadPx q = AtelierEdgeDetector.detect(WIDTH, HEIGHT, scene.luminance);
            if (q == null) {
                System.out.printf("%s: %s%n", scene.name, scene.corners == null ? "correct refusal" : "miss");
            } else if (scene.corners == null) {
                System.out.printf("%s: false crop%n", scene.name);
            } else {
                PointPx[] detected = {q.getTl(), q.getTr(), q.getBr(), q.getBl()};
                double error = 0;
                for (int i = 0; i < 4; i++) {
                    error = Math.max(error, Math.hypot(detected[i].getX() * (WIDTH - 1) - scene.corners[i][0],
                        detected[i].getY() * (HEIGHT - 1) - scene.corners[i][1]));
                }
                System.out.printf(Locale.ROOT, "%s: %s (max error %.2f px)%n", scene.name,
                    error <= MAX_CORNER_ERROR ? "accurate" : "wrong crop", error);
            }
        }
    }

    private static double[][] quad(double left, double top, double right, double bottom) {
        return new double[][] {{left, top}, {right, top}, {right, bottom}, {left, bottom}};
    }

    private static Scene paper(String name, double[][] corners, int background, int contrast, boolean diffuse) {
        return new Scene(name, corners, (x, y) -> {
            double distance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                double[] a = corners[i], b = corners[(i + 1) % 4];
                double dx = b[0] - a[0], dy = b[1] - a[1];
                distance = Math.min(distance, (dx * (y - a[1]) - dy * (x - a[0])) / Math.hypot(dx, dy));
            }
            double coverage = diffuse ? Math.max(0, Math.min(1, (distance + 2) / 4)) : (distance >= 0 ? 1 : 0);
            return background + (int) Math.round(contrast * coverage);
        });
    }
}
