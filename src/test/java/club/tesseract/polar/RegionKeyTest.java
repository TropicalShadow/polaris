package club.tesseract.polar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class RegionKeyTest {

    @Test
    void fromChunk_positiveCoordinates_returnsCorrectRegion() {
        // Chunk (0,0) through (31,31) should map to region (0,0) with regionSize=32
        assertEquals(new RegionKey(0, 0), RegionKey.fromChunk(0, 0, 32));
        assertEquals(new RegionKey(0, 0), RegionKey.fromChunk(31, 31, 32));
        assertEquals(new RegionKey(0, 0), RegionKey.fromChunk(15, 20, 32));
    }

    @Test
    void fromChunk_boundaryPositive_returnsNextRegion() {
        // Chunk 32 should be in region 1
        assertEquals(new RegionKey(1, 0), RegionKey.fromChunk(32, 0, 32));
        assertEquals(new RegionKey(0, 1), RegionKey.fromChunk(0, 32, 32));
        assertEquals(new RegionKey(1, 1), RegionKey.fromChunk(32, 32, 32));
    }

    @Test
    void fromChunk_negativeCoordinates_usesFloorDivision() {
        // Chunk (-1, 0) should be in region (-1, 0), NOT region (0, 0)
        // This is the critical test - integer division truncates toward zero,
        // but floorDiv truncates toward negative infinity
        assertEquals(new RegionKey(-1, 0), RegionKey.fromChunk(-1, 0, 32));
        assertEquals(new RegionKey(0, -1), RegionKey.fromChunk(0, -1, 32));
        assertEquals(new RegionKey(-1, -1), RegionKey.fromChunk(-1, -1, 32));
    }

    @Test
    void fromChunk_negativeEdge_returnsCorrectRegion() {
        // Chunk (-32, -32) should be in region (-1, -1)
        assertEquals(new RegionKey(-1, -1), RegionKey.fromChunk(-32, -32, 32));
        // Chunk (-33, -33) should be in region (-2, -2)
        assertEquals(new RegionKey(-2, -2), RegionKey.fromChunk(-33, -33, 32));
    }

    @ParameterizedTest
    @CsvSource({
            // chunkX, chunkZ, regionSize, expectedRegionX, expectedRegionZ
            "0, 0, 32, 0, 0",
            "31, 31, 32, 0, 0",
            "32, 32, 32, 1, 1",
            "-1, -1, 32, -1, -1",
            "-32, -32, 32, -1, -1",
            "-33, -33, 32, -2, -2",
            "100, 200, 32, 3, 6",
            "-100, -200, 32, -4, -7",
            // Different region sizes
            "15, 15, 16, 0, 0",
            "16, 16, 16, 1, 1",
            "-1, -1, 16, -1, -1",
            "63, 63, 64, 0, 0",
            "64, 64, 64, 1, 1"
    })
    void fromChunk_variousCoordinates_returnsExpectedRegion(
            int chunkX, int chunkZ, int regionSize, int expectedX, int expectedZ) {
        assertEquals(new RegionKey(expectedX, expectedZ),
                RegionKey.fromChunk(chunkX, chunkZ, regionSize));
    }

    @Test
    void toString_formatsCorrectly() {
        assertEquals("region(0, 0)", new RegionKey(0, 0).toString());
        assertEquals("region(5, -3)", new RegionKey(5, -3).toString());
        assertEquals("region(-10, 20)", new RegionKey(-10, 20).toString());
    }

    @Test
    void equality_sameCoordinates_areEqual() {
        RegionKey key1 = new RegionKey(5, 10);
        RegionKey key2 = new RegionKey(5, 10);
        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    void equality_differentCoordinates_areNotEqual() {
        RegionKey key1 = new RegionKey(5, 10);
        RegionKey key2 = new RegionKey(10, 5);
        assertNotEquals(key1, key2);
    }

    @Test
    void fromChunk_regionSizeOne_chunkEqualsRegion() {
        // With region size 1, each chunk is its own region
        assertEquals(new RegionKey(5, 10), RegionKey.fromChunk(5, 10, 1));
        assertEquals(new RegionKey(-3, -7), RegionKey.fromChunk(-3, -7, 1));
    }
}
