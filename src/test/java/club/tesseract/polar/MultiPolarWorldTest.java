package club.tesseract.polar;

import club.tesseract.polar.source.InMemoryRegionSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MultiPolarWorld builder and lifecycle methods.
 * Does not test actual chunk loading (requires Minestom instance).
 */
class MultiPolarWorldTest {

    private InMemoryRegionSource source;
    private MultiPolarWorld loader;

    @BeforeEach
    void setUp() {
        source = new InMemoryRegionSource();
    }

    @AfterEach
    void tearDown() {
        if (loader != null) {
            loader.close();
        }
    }

    @Test
    void builder_requiresSource() {
        assertThrows(IllegalStateException.class, () ->
                MultiPolarWorld.builder().build()
        );
    }

    @Test
    void builder_requiresPositiveRegionSize() {
        assertThrows(IllegalArgumentException.class, () ->
                MultiPolarWorld.builder()
                        .source(source)
                        .regionSize(0)
                        .build()
        );

        assertThrows(IllegalArgumentException.class, () ->
                MultiPolarWorld.builder()
                        .source(source)
                        .regionSize(-1)
                        .build()
        );
    }

    @Test
    void builder_createsWithDefaults() {
        loader = MultiPolarWorld.builder()
                .source(source)
                .build();

        assertNotNull(loader);
    }

    @Test
    void builder_acceptsCustomConfiguration() {
        loader = MultiPolarWorld.builder()
                .source(source)
                .regionSize(16)
                .evictAfter(Duration.ofMinutes(10))
                .evictCheckInterval(Duration.ofSeconds(60))
                .build();

        assertNotNull(loader);
    }

    @Test
    void preloadRegion_loadsRegion() throws Exception {
        source.put(new RegionKey(0, 0), TestPolarHelper.emptyWorldBytes());

        loader = MultiPolarWorld.builder()
                .source(source)
                .build();

        loader.preloadRegion(0, 0).get();

        List<RegionKey> loaded = loader.getLoadedRegions();
        assertEquals(1, loaded.size());
        assertTrue(loaded.contains(new RegionKey(0, 0)));
    }

    @Test
    void preloadAll_loadsAllRegions() throws Exception {
        source.put(new RegionKey(0, 0), TestPolarHelper.emptyWorldBytes());
        source.put(new RegionKey(1, 0), TestPolarHelper.emptyWorldBytes());
        source.put(new RegionKey(-1, -1), TestPolarHelper.emptyWorldBytes());

        loader = MultiPolarWorld.builder()
                .source(source)
                .build();

        loader.preloadAll().get();

        List<RegionKey> loaded = loader.getLoadedRegions();
        assertEquals(3, loaded.size());
    }

    @Test
    void unloadRegion_removesFromLoaded() throws Exception {
        source.put(new RegionKey(0, 0), TestPolarHelper.emptyWorldBytes());
        source.put(new RegionKey(1, 1), TestPolarHelper.emptyWorldBytes());

        loader = MultiPolarWorld.builder()
                .source(source)
                .build();

        loader.preloadAll().get();
        assertEquals(2, loader.getLoadedRegions().size());

        loader.unloadRegion(0, 0);

        List<RegionKey> loaded = loader.getLoadedRegions();
        assertEquals(1, loaded.size());
        assertFalse(loaded.contains(new RegionKey(0, 0)));
        assertTrue(loaded.contains(new RegionKey(1, 1)));
    }

    @Test
    void close_stopsEvictionAndClearsRegions() throws Exception {
        source.put(new RegionKey(0, 0), TestPolarHelper.emptyWorldBytes());

        loader = MultiPolarWorld.builder()
                .source(source)
                .build();

        loader.preloadRegion(0, 0).get();
        assertEquals(1, loader.getLoadedRegions().size());

        loader.close();

        assertEquals(0, loader.getLoadedRegions().size());
    }

    @Test
    void getLoadedRegions_returnsSnapshot() throws Exception {
        source.put(new RegionKey(0, 0), TestPolarHelper.emptyWorldBytes());

        loader = MultiPolarWorld.builder()
                .source(source)
                .build();

        loader.preloadRegion(0, 0).get();

        List<RegionKey> snapshot = loader.getLoadedRegions();

        // Unload the region
        loader.unloadRegion(0, 0);

        // Snapshot should still contain the region (it's a snapshot)
        assertEquals(1, snapshot.size());
        // But actual loaded regions should be empty
        assertEquals(0, loader.getLoadedRegions().size());
    }
}
