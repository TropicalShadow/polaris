package club.tesseract.polar;

import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWriter;

/**
 * Helper class for creating test polar data.
 */
public final class TestPolarHelper {

    private TestPolarHelper() {}

    /**
     * Creates a minimal empty polar world as bytes.
     */
    public static byte[] emptyWorldBytes() {
        PolarWorld world = new PolarWorld();
        return PolarWriter.write(world);
    }
}
