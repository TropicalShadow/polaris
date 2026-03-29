package club.tesseract.polar.source;

import club.tesseract.polar.RegionKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemRegionSourceTest {

    @TempDir
    Path tempDir;

    private FileSystemRegionSource source;

    @BeforeEach
    void setUp() {
        source = new FileSystemRegionSource(tempDir);
    }

    @Test
    void read_existingFile_returnsBytes() throws IOException, ExecutionException, InterruptedException {
        // Create a test region file
        byte[] testData = "test polar data".getBytes();
        Path regionFile = tempDir.resolve("region_0_0.polar");
        Files.write(regionFile, testData);

        // Read it back
        Optional<byte[]> result = source.read(new RegionKey(0, 0)).get();

        assertTrue(result.isPresent());
        assertArrayEquals(testData, result.get());
    }

    @Test
    void read_nonExistentFile_returnsEmpty() throws ExecutionException, InterruptedException {
        Optional<byte[]> result = source.read(new RegionKey(99, 99)).get();
        assertTrue(result.isEmpty());
    }

    @Test
    void read_negativeCoordinates_usesCorrectFilename() throws IOException, ExecutionException, InterruptedException {
        byte[] testData = "negative region data".getBytes();
        Path regionFile = tempDir.resolve("region_-1_-2.polar");
        Files.write(regionFile, testData);

        Optional<byte[]> result = source.read(new RegionKey(-1, -2)).get();

        assertTrue(result.isPresent());
        assertArrayEquals(testData, result.get());
    }

    @Test
    void listAll_emptyDirectory_returnsEmptyList() throws ExecutionException, InterruptedException {
        List<RegionKey> keys = source.listAll().get();
        assertTrue(keys.isEmpty());
    }

    @Test
    void listAll_withRegionFiles_returnsAllKeys() throws IOException, ExecutionException, InterruptedException {
        // Create several region files
        Files.createFile(tempDir.resolve("region_0_0.polar"));
        Files.createFile(tempDir.resolve("region_1_0.polar"));
        Files.createFile(tempDir.resolve("region_-1_-1.polar"));
        Files.createFile(tempDir.resolve("region_5_10.polar"));

        List<RegionKey> keys = source.listAll().get();

        assertEquals(4, keys.size());
        assertTrue(keys.contains(new RegionKey(0, 0)));
        assertTrue(keys.contains(new RegionKey(1, 0)));
        assertTrue(keys.contains(new RegionKey(-1, -1)));
        assertTrue(keys.contains(new RegionKey(5, 10)));
    }

    @Test
    void listAll_ignoresNonPolarFiles() throws IOException, ExecutionException, InterruptedException {
        Files.createFile(tempDir.resolve("region_0_0.polar"));
        Files.createFile(tempDir.resolve("region_1_1.txt"));       // wrong extension
        Files.createFile(tempDir.resolve("something_else.polar")); // wrong prefix
        Files.createFile(tempDir.resolve("readme.md"));

        List<RegionKey> keys = source.listAll().get();

        assertEquals(1, keys.size());
        assertTrue(keys.contains(new RegionKey(0, 0)));
    }

    @Test
    void listAll_handlesMalformedFilenames() throws IOException, ExecutionException, InterruptedException {
        Files.createFile(tempDir.resolve("region_0_0.polar"));      // valid
        Files.createFile(tempDir.resolve("region_abc_0.polar"));    // invalid - not a number
        Files.createFile(tempDir.resolve("region_0.polar"));        // invalid - missing z
        Files.createFile(tempDir.resolve("region_.polar"));         // invalid - no coords

        List<RegionKey> keys = source.listAll().get();

        assertEquals(1, keys.size());
        assertTrue(keys.contains(new RegionKey(0, 0)));
    }

    @Test
    void listAll_nonExistentDirectory_returnsEmptyList() throws ExecutionException, InterruptedException {
        Path nonExistent = tempDir.resolve("does_not_exist");
        FileSystemRegionSource badSource = new FileSystemRegionSource(nonExistent);

        List<RegionKey> keys = badSource.listAll().get();
        assertTrue(keys.isEmpty());
    }

    @Test
    void read_largeCoordinates_worksCorrectly() throws IOException, ExecutionException, InterruptedException {
        byte[] testData = "large coords".getBytes();
        Path regionFile = tempDir.resolve("region_1000_-2000.polar");
        Files.write(regionFile, testData);

        Optional<byte[]> result = source.read(new RegionKey(1000, -2000)).get();

        assertTrue(result.isPresent());
        assertArrayEquals(testData, result.get());
    }

    @Test
    void read_emptyFile_returnsEmptyBytes() throws IOException, ExecutionException, InterruptedException {
        Path regionFile = tempDir.resolve("region_0_0.polar");
        Files.createFile(regionFile);

        Optional<byte[]> result = source.read(new RegionKey(0, 0)).get();

        assertTrue(result.isPresent());
        assertEquals(0, result.get().length);
    }
}
