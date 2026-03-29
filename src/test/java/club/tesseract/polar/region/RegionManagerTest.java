package club.tesseract.polar.region;

import club.tesseract.polar.RegionKey;
import club.tesseract.polar.TestPolarHelper;
import club.tesseract.polar.source.InMemoryRegionSource;
import net.hollowcube.polar.PolarWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class RegionManagerTest {

    private InMemoryRegionSource source;
    private RegionManager manager;

    private static final long EVICT_TTL_MS = 100; // Short TTL for testing
    private static final int REGION_SIZE = 32;

    @BeforeEach
    void setUp() {
        source = new InMemoryRegionSource();
        manager = new RegionManager(source, EVICT_TTL_MS, true);
    }

    @Test
    void getOrLoad_existingRegion_returnsWorld() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());

        PolarWorld world = manager.getOrLoad(key).get();

        assertNotNull(world);
    }

    @Test
    void getOrLoad_nonExistentRegion_returnsEmptyWorld() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(99, 99);

        PolarWorld world = manager.getOrLoad(key).get();

        assertNotNull(world);
        assertTrue(world.chunks().isEmpty());
    }

    @Test
    void getOrLoad_deduplicatesConcurrentRequests() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        source.setReadDelay(50); // Simulate slow read

        // Fire off multiple concurrent requests
        CompletableFuture<PolarWorld> future1 = manager.getOrLoad(key);
        CompletableFuture<PolarWorld> future2 = manager.getOrLoad(key);
        CompletableFuture<PolarWorld> future3 = manager.getOrLoad(key);

        PolarWorld world1 = future1.get();
        PolarWorld world2 = future2.get();
        PolarWorld world3 = future3.get();

        // All should return the same world instance
        assertSame(world1, world2);
        assertSame(world2, world3);
    }

    @Test
    void getOrLoad_cachedRegion_returnsCached() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());

        PolarWorld first = manager.getOrLoad(key).get();
        PolarWorld second = manager.getOrLoad(key).get();

        assertSame(first, second);
    }

    @Test
    void getIfReady_loadedRegion_returnsWorld() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());

        // First load the region
        manager.getOrLoad(key).get();

        // Now getIfReady should return it
        PolarWorld world = manager.getIfReady(key);
        assertNotNull(world);
    }

    @Test
    void getIfReady_notLoadedRegion_returnsNull() {
        RegionKey key = new RegionKey(0, 0);
        PolarWorld world = manager.getIfReady(key);
        assertNull(world);
    }

    @Test
    void retainChunk_incrementsRefCount() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        // Retain multiple chunks in the same region
        manager.retainChunk(0, 0, REGION_SIZE);
        manager.retainChunk(1, 1, REGION_SIZE);
        manager.retainChunk(31, 31, REGION_SIZE);

        // Region should not be evictable (refs > 0)
        assertEquals(1, manager.getLoadedRegionCount());
        int evicted = manager.tickEviction();
        assertEquals(0, evicted);
    }

    @Test
    void releaseChunk_decrementsRefCount() throws ExecutionException, InterruptedException, TimeoutException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        manager.retainChunk(0, 0, REGION_SIZE);
        manager.retainChunk(1, 1, REGION_SIZE);

        manager.releaseChunk(0, 0, REGION_SIZE);
        manager.releaseChunk(1, 1, REGION_SIZE);

        // Wait for TTL to expire
        Thread.sleep(EVICT_TTL_MS + 50);

        int evicted = manager.tickEviction();
        assertEquals(1, evicted);
        assertEquals(0, manager.getLoadedRegionCount());
    }

    @Test
    void tickEviction_idleRegion_evictsAfterTtl() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        // Immediately try to evict - should not evict (TTL not reached)
        int evicted = manager.tickEviction();
        assertEquals(0, evicted);

        // Wait for TTL
        Thread.sleep(EVICT_TTL_MS + 50);

        evicted = manager.tickEviction();
        assertEquals(1, evicted);
    }

    @Test
    void tickEviction_activeRegion_doesNotEvict() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        // Retain a chunk (region is active)
        manager.retainChunk(0, 0, REGION_SIZE);

        // Wait for TTL
        Thread.sleep(EVICT_TTL_MS + 50);

        // Should not evict - region still has refs
        int evicted = manager.tickEviction();
        assertEquals(0, evicted);
        assertEquals(1, manager.getLoadedRegionCount());
    }

    @Test
    void forceEvict_removesRegardlessOfState() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        manager.getOrLoad(key).get();

        manager.retainChunk(0, 0, REGION_SIZE); // Active region

        manager.forceEvict(key);

        assertEquals(0, manager.getLoadedRegionCount());
        assertNull(manager.getIfReady(key));
    }

    @Test
    void getLoadedRegions_returnsAllKeys() throws ExecutionException, InterruptedException {
        source.put(new RegionKey(0, 0), TestPolarHelper.emptyWorldBytes());
        source.put(new RegionKey(1, 0), TestPolarHelper.emptyWorldBytes());
        source.put(new RegionKey(0, 1), TestPolarHelper.emptyWorldBytes());

        manager.getOrLoad(new RegionKey(0, 0)).get();
        manager.getOrLoad(new RegionKey(1, 0)).get();
        manager.getOrLoad(new RegionKey(0, 1)).get();

        List<RegionKey> keys = manager.getLoadedRegions();

        assertEquals(3, keys.size());
        assertTrue(keys.contains(new RegionKey(0, 0)));
        assertTrue(keys.contains(new RegionKey(1, 0)));
        assertTrue(keys.contains(new RegionKey(0, 1)));
    }

    @Test
    void closeAll_clearsAllRegions() throws ExecutionException, InterruptedException {
        source.put(new RegionKey(0, 0), TestPolarHelper.emptyWorldBytes());
        source.put(new RegionKey(1, 1), TestPolarHelper.emptyWorldBytes());

        manager.getOrLoad(new RegionKey(0, 0)).get();
        manager.getOrLoad(new RegionKey(1, 1)).get();

        manager.closeAll();

        assertEquals(0, manager.getLoadedRegionCount());
    }

    @Test
    void getOrLoad_corruptData_throwsRegionLoadException() {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, new byte[]{0, 1, 2, 3}); // Invalid polar data

        CompletableFuture<PolarWorld> future = manager.getOrLoad(key);

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof RegionLoadException);
    }

    @Test
    void getOrLoad_readFailure_throwsRegionLoadException() {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());
        source.setFailOnRead(true);

        CompletableFuture<PolarWorld> future = manager.getOrLoad(key);

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof RegionLoadException);
    }

    @Test
    void getOrLoad_afterFailure_retriesOnNextRequest() throws ExecutionException, InterruptedException {
        RegionKey key = new RegionKey(0, 0);
        source.put(key, TestPolarHelper.emptyWorldBytes());

        // First request fails
        source.setFailOnRead(true);
        CompletableFuture<PolarWorld> failFuture = manager.getOrLoad(key);
        assertThrows(ExecutionException.class, failFuture::get);

        // Second request should retry and succeed
        source.setFailOnRead(false);
        PolarWorld world = manager.getOrLoad(key).get();
        assertNotNull(world);
    }

    @Test
    void source_returnsConfiguredSource() {
        assertSame(source, manager.source());
    }
}
