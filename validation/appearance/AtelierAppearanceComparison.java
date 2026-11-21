import app.pristinepage.core.image.DocumentColor;
import app.pristinepage.core.image.NaturalLightness;
import app.pristinepage.core.image.PaperGain;
import app.pristinepage.core.image.PaperShadow;
import app.pristinepage.core.image.RgbImage;
import app.pristinepage.core.recipe.AppearanceRecipe;
import java.util.Arrays;

/** Deterministic paired synthetic pages. Run with the core JAR, pinned Kotlin stdlib and kotlinx-serialization-core-jvm. */
class AtelierAppearanceComparison {
    private static final int WIDTH = 192;
    private static final int HEIGHT = 112;
    private static final int EDGE = 81;

    private static int[] paper(int x, boolean hard) {
        if (hard) return x < EDGE ? new int[] {152, 142, 133} : new int[] {224, 218, 210};
        return new int[] {165 + x / 4, 151 + x / 4, 139 + x / 4};
    }

    private static RgbImage scene(boolean hard) {
        byte[] bytes = new byte[WIDTH * HEIGHT * 3];
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int[] rgb = paper(x, hard);
            if (x >= 100 && x < 110 && y >= 22 && y < 34) rgb = new int[] {168, 51, 72};
            else if (x >= 26 && x < 142 && y == 52) for (int c = 0; c < 3; c++) rgb[c] -= 11;
            int at = 3 * (y * WIDTH + x);
            for (int c = 0; c < 3; c++) bytes[at + c] = (byte) rgb[c];
        }
        return new RgbImage(WIDTH, HEIGHT, WIDTH * 3, bytes);
    }

    public static void main(String[] args) {
        for (boolean hard : new boolean[] {false, true}) {
            System.out.println(hard ? "Hard shadow" : "Smooth tinted illumination");
            try (RgbImage page = scene(hard);
                 RgbImage paper = PaperGain.apply(page, new AppearanceRecipe.PaperGainV1(), null, () -> kotlin.Unit.INSTANCE);
                 RgbImage natural = NaturalLightness.apply(page, new AppearanceRecipe.NaturalLV1(), null, () -> kotlin.Unit.INSTANCE);
                 RgbImage document = DocumentColor.apply(page, new AppearanceRecipe.DocumentColorV1(), null, () -> kotlin.Unit.INSTANCE);
                 RgbImage shadow = PaperShadow.apply(page, new AppearanceRecipe.PaperShadowV1(), null, () -> kotlin.Unit.INSTANCE)) {
                print("Original", page);
                print("Paper white", paper);
                print("Natural lightness", natural);
                print("Document color", document);
                print("Paper shadow", shadow);
            }
        }
    }

    private static void print(String method, RgbImage image) {
        int low = 255, high = 0, step = 0;
        for (int x = 8; x < WIDTH - 8; x++) {
            int gray = luma(image, x, 12);
            low = Math.min(low, gray);
            high = Math.max(high, gray);
            step = Math.max(step, Math.abs(gray - luma(image, x - 1, 12)));
        }
        int[] shadowPaper = rgb(image, 32, 12), litPaper = rgb(image, 132, 12);
        System.out.printf("%s: paper range=%d, max adjacent step=%d, shadow paper=%s, lit paper=%s, faint stroke dY=%d/%d, color ink=%s%n",
            method, high - low, step, Arrays.toString(shadowPaper), Arrays.toString(litPaper),
            luma(image, 32, 51) - luma(image, 32, 52), luma(image, 132, 51) - luma(image, 132, 52),
            Arrays.toString(rgb(image, 104, 26)));
    }

    private static int luma(RgbImage image, int x, int y) {
        int[] c = rgb(image, x, y);
        return (77 * c[0] + 150 * c[1] + 29 * c[2]) / 256;
    }

    private static int[] rgb(RgbImage image, int x, int y) {
        return new int[] {image.getUnsigned(x, y, 0), image.getUnsigned(x, y, 1), image.getUnsigned(x, y, 2)};
    }
}
