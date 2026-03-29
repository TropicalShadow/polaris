package club.tesseract.polar.region;

import club.tesseract.polar.RegionKey;
import club.tesseract.polar.TestPolarHelper;
import club.tesseract.polar.source.InMemoryRegionSource;
import net.minestom.server.instance.Chunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegionManagerDirtyTrackingTest {

    private InMemoryRegionSource source;
    private RegionManager manager;

    private static final long EVICT_TTL_MS = 100;
    private static final int REGION_SIZE = 32;

    @BeforeEach
    void setUp() {
        source = new InMemoryRegionSource();
        manager = new RegionManager(source, EVICT_TTL_MS, true);
    }

    private Chunk mockChunk(int chunkX, int chunkZ) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getChunkX()).thenReturn(chunkX);
        when(chunk.getChunkZ()).thenReturn(chunkZ);
        return chunk;
    }

    @Test
    void hasDirtyRegions_initiallyFalse() {
        assertFalse(manager.hasDirtyRegions());
    }

    @Test
    void markDirty_makesDirty() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        Chunk chunk = mockChunk(0, 0);
        manager.markDirty(chunk, REGION_SIZE);

        assertTrue(manager.hasDirtyRegions());
        List<RegionKey> dirty = manager.getDirtyRegions();
        assertEquals(1, dirty.size());
        assertTrue(dirty.contains(key));
    }

    @Test
    void markDirty_multipleChunksSameRegion_oneRegionDirty() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        // Mark multiple chunks in same region as dirty
        manager.markDirty(mockChunk(0, 0), REGION_SIZE);
        manager.markDirty(mockChunk(1, 1), REGION_SIZE);
        manager.markDirty(mockChunk(31, 31), REGION_SIZE);

        List<RegionKey> dirty = manager.getDirtyRegions();
        assertEquals(1, dirty.size());

        // Check the entry has all 3 dirty chunks
        RegionManager.RegionEntry entry = manager.getEntry(key);
        assertNotNull(entry);
        assertEquals(3, entry.getDirtyChunks().size());
    }

    @Test
    void markDirty_multipleRegions() throws ExecutionException, InterruptedException {
        RegionKey key1 = new RegionKey(0, 0);
        RegionKey key2 = new RegionKey(1, 0);
        source.put(key1, TestPolarHelper.emptyWorldBytes());
        source.put(key2, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key1).get();
        manager.getOrLoad(key2).get();

        manager.markDirty(mockChunk(0, 0), REGION_SIZE);       // region (0, 0)
        manager.markDirty(mockChunk(32, 0), REGION_SIZE);      // region (1, 0)

        List<RegionKey> dirty = manager.getDirtyRegions();
        assertEquals(2, dirty.size());
        assertTrue(dirty.contains(key1));
        assertTrue(dirty.contains(key2));
    }

    @Test
    void tickEviction_withSaveOnEvict_savesThenEvictsDirtyRegion() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        // Set serializer so save-on-evict can work
        AtomicInteger saveCount = new AtomicInteger(0);
        manager.setSerializer(entry -> {
            saveCount.incrementAndGet();
            return TestPolarHelper.emptyWorldBytes();
        });

        // Mark as dirty
        manager.markDirty(mockChunk(0, 0), REGION_SIZE);

        // Wait for TTL to expire
        Thread.sleep(EVICT_TTL_MS + 50);

        // Evict - should save then evict the dirty region
        int evicted = manager.tickEviction();
        assertEquals(1, evicted);
        assertEquals(0, manager.getLoadedRegionCount());
        assertEquals(1, saveCount.get()); // Verify save was called
    }

    @Test
    void tickEviction_withoutSaveOnEvict_discardsDirtyRegion() throws ExecutionException, InterruptedException {
        // Create manager with saveOnEvict=false
        RegionManager noSaveManager = new RegionManager(source, EVICT_TTL_MS, false);

        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        noSaveManager.getOrLoad(key).get();

        // Mark as dirty
        noSaveManager.markDirty(mockChunk(0, 0), REGION_SIZE);
        assertTrue(noSaveManager.hasDirtyRegions());

        // Wait for TTL to expire
        Thread.sleep(EVICT_TTL_MS + 50);

        // Evict - should discard changes and evict
        int evicted = noSaveManager.tickEviction();
        assertEquals(1, evicted);
        assertEquals(0, noSaveManager.getLoadedRegionCount());
    }

    @Test
    void flushRegion_clearsDirtyFlag() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        manager.markDirty(mockChunk(0, 0), REGION_SIZE);
        assertTrue(manager.hasDirtyRegions());

        // Flush the region
        AtomicInteger serializerCalls = new AtomicInteger(0);
        manager.flushRegion(key, entry -> {
            serializerCalls.incrementAndGet();
            return TestPolarHelper.emptyWorldBytes();
        }).get();

        assertEquals(1, serializerCalls.get());
        assertFalse(manager.hasDirtyRegions());
    }

    @Test
    void flushAll_flushesAllDirtyRegions() throws ExecutionException, InterruptedException {
        RegionKey key1 = new RegionKey(0, 0);
        RegionKey key2 = new RegionKey(1, 1);
        source.put(key1, TestPolarHelper.emptyWorldBytes());
        source.put(key2, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key1).get();
        manager.getOrLoad(key2).get();

        manager.markDirty(mockChunk(0, 0), REGION_SIZE);
        manager.markDirty(mockChunk(32, 32), REGION_SIZE);

        AtomicInteger serializerCalls = new AtomicInteger(0);
        manager.flushAll(entry -> {
            serializerCalls.incrementAndGet();
            return TestPolarHelper.emptyWorldBytes();
        }).get();

        assertEquals(2, serializerCalls.get());
        assertFalse(manager.hasDirtyRegions());
    }

    @Test
    void flushRegion_writesToSource() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        manager.markDirty(mockChunk(0, 0), REGION_SIZE);

        byte[] newData = "new data".getBytes();
        manager.flushRegion(key, entry -> newData).get();

        // Check data was written to source
        assertArrayEquals(newData, source.get(key));
    }

    @Test
    void flushRegion_notDirty_doesNothing() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        AtomicInteger serializerCalls = new AtomicInteger(0);
        manager.flushRegion(key, entry -> {
            serializerCalls.incrementAndGet();
            return TestPolarHelper.emptyWorldBytes();
        }).get();

        assertEquals(0, serializerCalls.get());
    }

    @Test
    void afterFlush_regionCanBeEvicted() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        manager.markDirty(mockChunk(0, 0), REGION_SIZE);

        // Flush
        manager.flushRegion(key, entry -> TestPolarHelper.emptyWorldBytes()).get();

        // Wait for TTL
        Thread.sleep(EVICT_TTL_MS + 50);

        // Now it should be evictable
        int evicted = manager.tickEviction();
        assertEquals(1, evicted);
        assertEquals(0, manager.getLoadedRegionCount());
    }
}
