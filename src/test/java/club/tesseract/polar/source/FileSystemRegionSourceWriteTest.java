package club.tesseract.polar.source;

import club.tesseract.polar.RegionKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemRegionSourceWriteTest {

    @TempDir
    Path tempDir;

    private FileSystemRegionSource source;

    @BeforeEach
    void setUp() {
        source = new FileSystemRegionSource(tempDir);
    }

    @Test
    void supportsWrite_returnsTrue() {
        assertTrue(source.supportsWrite());
    }

    @Test
    void write_createsNewFile() throws ExecutionException, InterruptedException, IOException {
        byte[] testData = "test polar data".getBytes();
        RegionKey key = new RegionKey(0, 0);

        source.write(key, testData).get();

        Path file = tempDir.resolve("region_0_0.polar");
        assertTrue(Files.exists(file));
        assertArrayEquals(testData, Files.readAllBytes(file));
    }

    @Test
    void write_overwritesExistingFile() throws ExecutionException, InterruptedException, IOException {
        RegionKey key = new RegionKey(1, 2);
        byte[] oldData = "old data".getBytes();
        byte[] newData = "new data".getBytes();

        // Write initial data
        source.write(key, oldData).get();

        // Overwrite
        source.write(key, newData).get();

        Path file = tempDir.resolve("region_1_2.polar");
        assertArrayEquals(newData, Files.readAllBytes(file));
    }

    @Test
    void write_thenRead_returnsData() throws ExecutionException, InterruptedException {
        byte[] testData = "roundtrip test".getBytes();
        RegionKey key = new RegionKey(5, -3);

        source.write(key, testData).get();

        Optional<byte[]> result = source.read(key).get();
        assertTrue(result.isPresent());
        assertArrayEquals(testData, result.get());
    }

    @Test
    void write_negativeCoordinates_createsCorrectFile() throws ExecutionException, InterruptedException, IOException {
        byte[] testData = "negative coords".getBytes();
        RegionKey key = new RegionKey(-10, -20);

        source.write(key, testData).get();

        Path file = tempDir.resolve("region_-10_-20.polar");
        assertTrue(Files.exists(file));
        assertArrayEquals(testData, Files.readAllBytes(file));
    }

    @Test
    void write_createsDirectoryIfMissing() throws ExecutionException, InterruptedException, IOException {
        Path nestedDir = tempDir.resolve("nested/regions");
        FileSystemRegionSource nestedSource = new FileSystemRegionSource(nestedDir);

        byte[] testData = "nested test".getBytes();
        RegionKey key = new RegionKey(0, 0);

        nestedSource.write(key, testData).get();

        Path file = nestedDir.resolve("region_0_0.polar");
        assertTrue(Files.exists(file));
        assertArrayEquals(testData, Files.readAllBytes(file));
    }

    @Test
    void write_emptyData_createsEmptyFile() throws ExecutionException, InterruptedException, IOException {
        RegionKey key = new RegionKey(0, 0);

        source.write(key, new byte[0]).get();

        Path file = tempDir.resolve("region_0_0.polar");
        assertTrue(Files.exists(file));
        assertEquals(0, Files.size(file));
    }

    @Test
    void write_largeData_succeeds() throws ExecutionException, InterruptedException, IOException {
        // 1 MB of data
        byte[] largeData = new byte[1024 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        RegionKey key = new RegionKey(99, 99);

        source.write(key, largeData).get();

        Path file = tempDir.resolve("region_99_99.polar");
        assertTrue(Files.exists(file));
        assertArrayEquals(largeData, Files.readAllBytes(file));
    }
}
