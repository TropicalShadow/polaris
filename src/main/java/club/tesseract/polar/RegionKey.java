package club.tesseract.polar;

/**
 * Identifies a polar region file by its grid coordinates.
 *
 * <p>Region coordinates are derived from chunk coordinates via floor division:
 * <pre>
 *   regionX = Math.floorDiv(chunkX, regionSize)
 *   regionZ = Math.floorDiv(chunkZ, regionSize)
 * </pre>
 *
 * <p>Using {@code floorDiv} rather than {@code /} is critical for correct behaviour
 * with negative chunk coordinates — integer division truncates toward zero, which
 * would place chunk (-1, 0) in region (0, 0) rather than region (-1, 0).
 */
public record RegionKey(int x, int z) {

    public static RegionKey fromChunk(int chunkX, int chunkZ, int regionSize) {
        return new RegionKey(
                Math.floorDiv(chunkX, regionSize),
                Math.floorDiv(chunkZ, regionSize)
        );
    }

    @Override
    public String toString() {
        return "region(" + x + ", " + z + ")";
    }

}
