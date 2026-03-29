package club.tesseract.polar;

import club.tesseract.polar.region.RegionManager;
import club.tesseract.polar.source.RegionSource;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarLoader;
import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWorldAccess;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.trait.InstanceEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.instance.InstanceChunkUnloadEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A Minestom {@link ChunkLoader} that transparently spans a world across multiple
 * polar region files, loading and evicting them on demand.
 *
 * <p>Each region file covers a fixed square of chunks (e.g. 32×32). The file for a
 * given chunk is derived purely from its coordinates — no index file is required:
 * <pre>
 *   regionX = floorDiv(chunkX, regionSize)
 *   regionZ = floorDiv(chunkZ, regionSize)
 *   filename = "region_&lt;regionX&gt;_&lt;regionZ&gt;.polar"
 * </pre>
 *
 * <h2>Write Support</h2>
 * <p>When {@link RegionSource#supportsWrite()} returns {@code true}, chunks are
 * automatically saved:
 * <ul>
 *   <li>Chunks are marked dirty when {@link #saveChunk} is called</li>
 *   <li>Dirty regions are flushed periodically (configurable via {@link Builder#saveInterval})</li>
 *   <li>All dirty regions are flushed on {@link #close()}</li>
 *   <li>Dirty regions are never evicted until they are flushed</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MultiPolarWorld loader = MultiPolarWorld.builder()
 *     .source(new FileSystemRegionSource(Path.of("regions/")))
 *     .regionSize(32)
 *     .evictAfter(Duration.ofMinutes(5))
 *     .saveInterval(Duration.ofMinutes(1))
 *     .build();
 *
 * instance.setChunkLoader(loader);
 * loader.hook(instance); // registers chunk load/unload event listeners
 * }</pre>
 *
 * <p>Call {@link #close()} when the instance is destroyed to flush pending changes,
 * stop schedulers, and release resources.
 */
public class MultiPolarWorld implements ChunkLoader {

    private static final Logger log = LoggerFactory.getLogger(MultiPolarWorld.class);
    private static final short POLAR_WORLD_VERSION = PolarWorld.LATEST_VERSION;
    private static final int POLAR_DATA_VERSION = PolarWorld.MAGIC_NUMBER;

    private final RegionManager regionManager;
    private final int regionSize;
    private final @Nullable PolarWorldAccess worldAccess;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "multipolar-scheduler");
                t.setDaemon(true);
                return t;
            });

    private @Nullable ScheduledFuture<?> evictionTask;
    private @Nullable ScheduledFuture<?> saveTask;
    private final boolean writeEnabled;
    private final boolean saveGeneratedChunks;
    private final boolean saveOnEvict;
    private @Nullable Instance hookedInstance;

    private MultiPolarWorld(Builder builder) {
        this.regionManager = new RegionManager(builder.source, builder.evictTtlMs, builder.saveOnEvict);
        this.regionSize = builder.regionSize;
        this.worldAccess = builder.worldAccess;
        this.writeEnabled = builder.source.supportsWrite();
        this.saveGeneratedChunks = builder.saveGeneratedChunks;
        this.saveOnEvict = builder.saveOnEvict;

        if (writeEnabled) {
            regionManager.setSerializer(entry -> ChunkSerializer.serializeRegion(entry, regionSize));
        }

        evictionTask = scheduler.scheduleAtFixedRate(
                this::runEviction,
                builder.evictCheckIntervalMs,
                builder.evictCheckIntervalMs,
                TimeUnit.MILLISECONDS
        );

        if (writeEnabled && builder.saveIntervalMs > 0) {
            saveTask = scheduler.scheduleAtFixedRate(
                    this::runSave,
                    builder.saveIntervalMs,
                    builder.saveIntervalMs,
                    TimeUnit.MILLISECONDS
            );
            log.info("Write support enabled, save interval: {}ms", builder.saveIntervalMs);
        }
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    @Override
    public @Nullable Chunk loadChunk(@NotNull Instance instance, int chunkX, int chunkZ) {
        RegionKey key = RegionKey.fromChunk(chunkX, chunkZ, regionSize);

        return regionManager.getOrLoad(key)
                .thenApply(polarWorld -> {
                    PolarChunk polarChunk = findChunk(polarWorld, chunkX, chunkZ);
                    if (polarChunk == null) {
                        return null;
                    }
                    return applyToMinestom(instance, chunkX, chunkZ, polarWorld, polarChunk);
                })
                .exceptionally(ex -> {
                    log.error("Failed to load chunk ({}, {}) from region {}: {}",
                            chunkX, chunkZ, key, ex.getMessage(), ex);
                    return null;
                }).join();
    }

    @Override
    public void saveChunk(@NotNull Chunk chunk) {
        if (!writeEnabled) return;
        regionManager.markDirty(chunk, regionSize);
    }

    /**
     * Registers chunk load/unload listeners on the given instance so that
     * region ref counts are kept accurate.
     *
     * <p>Must be called once after attaching this loader to an instance.
     * If not called, regions will never be evicted (ref count stays at zero
     * permanently and the idle TTL will eventually evict them anyway, but
     * ref count correctness is lost).
     */
    public void hook(@NotNull Instance instance) {
        this.hookedInstance = instance;
        EventNode<InstanceEvent> node = instance.eventNode();

        node.addListener(InstanceChunkLoadEvent.class, e -> {
            if (e.getInstance() != instance) return;
            Chunk chunk = e.getChunk();
            regionManager.retainChunk(chunk.getChunkX(), chunk.getChunkZ(), regionSize);

            if (writeEnabled && saveGeneratedChunks) {
                if (!regionManager.hasChunkInPolar(chunk.getChunkX(), chunk.getChunkZ(), regionSize)) {
                    regionManager.markDirty(chunk, regionSize);
                }
            }
        });

        node.addListener(InstanceChunkUnloadEvent.class, e -> {
            if (e.getInstance() != instance) return;
            Chunk chunk = e.getChunk();
            regionManager.releaseChunk(chunk.getChunkX(), chunk.getChunkZ(), regionSize);
        });

        if (writeEnabled) {
            node.addListener(PlayerBlockPlaceEvent.class, e -> {
                if (e.getInstance() != instance) return;
                Chunk chunk = e.getPlayer().getInstance().getChunkAt(e.getBlockPosition());
                if (chunk != null) {
                    regionManager.markChunkDirty(chunk.getChunkX(), chunk.getChunkZ(), regionSize);
                }
            });

            node.addListener(PlayerBlockBreakEvent.class, e -> {
                if (e.getInstance() != instance) return;
                Chunk chunk = e.getPlayer().getInstance().getChunkAt(e.getBlockPosition());
                if (chunk != null) {
                    regionManager.markChunkDirty(chunk.getChunkX(), chunk.getChunkZ(), regionSize);
                }
            });
        }
    }

    /**
     * Preloads the region containing the given chunk coordinates.
     *
     * <p>Returns a future that completes when the region is fully loaded and ready.
     * Useful for warming regions before players arrive, e.g. spawn area on startup.
     */
    public @NotNull CompletableFuture<Void> preloadRegion(int regionX, int regionZ) {
        return regionManager.getOrLoad(new RegionKey(regionX, regionZ))
                .thenApply(__ -> null);
    }

    /**
     * Preloads all regions returned by {@link RegionSource#listAll()}.
     *
     * <p>Returns a future that completes when all known regions are loaded.
     * A failure from {@code listAll()} is non-fatal — this method will log
     * a warning and complete normally with no preloading.
     */
    public @NotNull CompletableFuture<Void> preloadAll() {
        return regionManager.source().listAll()
                .exceptionally(ex -> {
                    log.warn("listAll() failed, skipping preload: {}", ex.getMessage());
                    return List.of();
                })
                .thenCompose(keys -> {
                    if (keys.isEmpty()) return CompletableFuture.completedFuture(null);
                    log.debug("Preloading {} regions", keys.size());
                    CompletableFuture<?>[] futures = keys.stream()
                            .map(k -> regionManager.getOrLoad(k).exceptionally(ex -> {
                                log.warn("Failed to preload region {}: {}", k, ex.getMessage());
                                return null;
                            }))
                            .toArray(CompletableFuture[]::new);
                    return CompletableFuture.allOf(futures);
                });
    }

    /**
     * Immediately evicts the given region from memory regardless of ref count or idle time.
     *
     * <p>In read-only mode this is always safe. Use with care if chunks from this
     * region are still loaded in the instance — subsequent access to those chunks
     * may behave unexpectedly until they are unloaded and reloaded.
     */
    public void unloadRegion(int regionX, int regionZ) {
        regionManager.forceEvict(new RegionKey(regionX, regionZ));
    }

    /**
     * Returns a snapshot of all currently loaded region keys,
     * including regions still in the LOADING state.
     */
    public @NotNull List<RegionKey> getLoadedRegions() {
        return regionManager.getLoadedRegions();
    }

    /**
     * Returns {@code true} if there are any dirty regions waiting to be saved.
     */
    public boolean hasPendingChanges() {
        return regionManager.hasDirtyRegions();
    }

    /**
     * Returns a list of region keys that have unsaved changes.
     */
    public @NotNull List<RegionKey> getDirtyRegions() {
        return regionManager.getDirtyRegions();
    }

    /**
     * Returns all region entries with their keys for debugging and monitoring.
     */
    public @NotNull Map<RegionKey, RegionManager.RegionEntry> getAllRegionEntries() {
        return regionManager.getAllEntries();
    }

    /**
     * Returns the configured eviction TTL in milliseconds.
     */
    public long getEvictTtlMs() {
        return regionManager.getEvictTtlMs();
    }

    /**
     * Returns the configured region size (chunks per side).
     */
    public int getRegionSize() {
        return regionSize;
    }

    /**
     * Returns whether dirty regions are saved before eviction.
     */
    public boolean isSaveOnEvict() {
        return saveOnEvict;
    }

    /**
     * Flushes all dirty regions to the source.
     *
     * <p>This method blocks until all regions are saved. It is automatically called
     * by {@link #close()}, but can be called manually to force a save at any time.
     *
     * @return a future that completes when all dirty regions are saved
     */
    public @NotNull CompletableFuture<Void> flush() {
        if (!writeEnabled) {
            return CompletableFuture.completedFuture(null);
        }
        if (hookedInstance != null) {
            regionManager.snapshotDirtyChunks(hookedInstance, regionSize);
        }
        return regionManager.flushAll(entry -> ChunkSerializer.serializeRegion(entry, regionSize));
    }

    /**
     * Flushes all dirty regions synchronously, blocking until complete.
     *
     * <p>This is a convenience method equivalent to {@code flush().join()}.
     */
    public void flushSync() {
        flush().join();
    }

    /**
     * Stops schedulers, flushes all dirty regions, and clears loaded regions from memory.
     *
     * <p>If write is enabled, this method will block until all dirty regions are saved.
     * Should be called when the associated instance is destroyed.
     */
    public void close() {
        // Stop schedulers first
        if (evictionTask != null) {
            evictionTask.cancel(false);
            evictionTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel(false);
            saveTask = null;
        }
        scheduler.shutdown();

        // Flush any pending changes
        if (writeEnabled && regionManager.hasDirtyRegions()) {
            log.info("Flushing {} dirty regions before close...", regionManager.getDirtyRegions().size());
            try {
                flush().join();
                log.info("All regions flushed successfully");
            } catch (Exception e) {
                log.error("Failed to flush some regions on close", e);
            }
        }

        regionManager.closeAll();
        log.debug("MultiPolarWorld closed");
    }

    private void runEviction() {
        try {
            int evicted = regionManager.tickEviction();
            if (evicted > 0) {
                log.debug("Eviction tick: removed {} idle regions ({} remaining)",
                        evicted, regionManager.getLoadedRegionCount());
            }
        } catch (Exception e) {
            log.error("Unexpected error in eviction tick", e);
        }
    }

    private void runSave() {
        try {
            List<RegionKey> dirty = regionManager.getDirtyRegions();
            if (dirty.isEmpty()) return;

            log.debug("Save tick: flushing {} dirty regions", dirty.size());
            flush().exceptionally(ex -> {
                log.error("Failed to save some regions: {}", ex.getMessage());
                return null;
            });
        } catch (Exception e) {
            log.error("Unexpected error in save tick", e);
        }
    }

    /**
     * Finds the matching {@link PolarChunk} inside a loaded {@link PolarWorld},
     * or {@code null} if this specific chunk was not included when the region was saved.
     */
    private @Nullable PolarChunk findChunk(@NotNull PolarWorld world, int chunkX, int chunkZ) {
        for (PolarChunk chunk : world.chunks()) {
            if (chunk.x() == chunkX && chunk.z() == chunkZ) {
                return chunk;
            }
        }
        return null;
    }

    /**
     * Applies a {@link PolarChunk} to a Minestom {@link Chunk} via a temporary
     * single-chunk {@link PolarLoader}, reusing Polar's existing deserialization logic.
     *
     * <p>This avoids duplicating PolarLoader's block/biome/light application code.
     * The loader is created per-chunk-load and is intentionally short-lived.
     */
    private @Nullable Chunk applyToMinestom(
            @NotNull Instance instance,
            int chunkX, int chunkZ,
            @NotNull PolarWorld polarWorld,
            @NotNull PolarChunk polarChunk) {

        PolarWorld singleChunk = new PolarWorld(
                MultiPolarWorld.POLAR_WORLD_VERSION,
                MinecraftServer.DATA_VERSION,
                polarWorld.compression(),
                polarWorld.minSection(), polarWorld.maxSection(),
                polarWorld.userData(),
                List.of(polarChunk)
        );

        PolarLoader delegate = worldAccess != null
                ? new PolarLoader(singleChunk).setWorldAccess(worldAccess)
                : new PolarLoader(singleChunk);

        return delegate.loadChunk(instance, chunkX, chunkZ);
    }

    public static final class Builder {

        private RegionSource source;
        private int regionSize = 32;
        private long evictTtlMs = Duration.ofMinutes(5).toMillis();
        private long evictCheckIntervalMs = Duration.ofSeconds(30).toMillis();
        private long saveIntervalMs = Duration.ofMinutes(1).toMillis();
        private boolean saveGeneratedChunks = false;
        private boolean saveOnEvict = true;
        private @Nullable PolarWorldAccess worldAccess;

        private Builder() {}

        /**
         * Required. The backend that provides raw polar bytes per region key.
         */
        public @NotNull Builder source(@NotNull RegionSource source) {
            this.source = source;
            return this;
        }

        /**
         * Number of chunks per region side. Default: 32 (32×32 = 1,024 chunks per region).
         *
         * <p>This value must match the size used when the region files were written.
         * Changing it after regions have been saved requires migrating all files.
         * Consider storing this in a world metadata file (future work).
         */
        public @NotNull Builder regionSize(int regionSize) {
            if (regionSize < 1) throw new IllegalArgumentException("regionSize must be >= 1");
            this.regionSize = regionSize;
            return this;
        }

        /**
         * How long a region must be idle (no loaded chunks) before it is eligible
         * for eviction. Default: 5 minutes.
         */
        public @NotNull Builder evictAfter(@NotNull Duration duration) {
            this.evictTtlMs = duration.toMillis();
            return this;
        }

        /**
         * How often the eviction scheduler checks for idle regions. Default: 30 seconds.
         *
         * <p>This does not need to match {@link #evictAfter} — it controls check
         * granularity, not the TTL itself.
         */
        public @NotNull Builder evictCheckInterval(@NotNull Duration duration) {
            this.evictCheckIntervalMs = duration.toMillis();
            return this;
        }

        /**
         * How often dirty regions are automatically saved. Default: 1 minute.
         *
         * <p>Set to {@link Duration#ZERO} to disable automatic saving — dirty regions
         * will only be saved when {@link MultiPolarWorld#flush()} is called explicitly
         * or when {@link MultiPolarWorld#close()} is called.
         */
        public @NotNull Builder saveInterval(@NotNull Duration duration) {
            this.saveIntervalMs = duration.toMillis();
            return this;
        }

        /**
         * Whether to automatically mark generated chunks as dirty so they get saved.
         * Default: false.
         *
         * <p>When enabled, any chunk that is loaded into Minestom but doesn't exist
         * in the polar data (i.e., was generated by Minestom's generator) will be
         * automatically marked dirty. This ensures newly generated chunks are persisted
         * on the next save tick or flush.
         *
         * <p>Requires {@link RegionSource#supportsWrite()} to return true and
         * {@link #hook(Instance)} to be called.
         */
        public @NotNull Builder saveGeneratedChunks(boolean save) {
            this.saveGeneratedChunks = save;
            return this;
        }

        /**
         * Whether to save dirty regions when they are evicted from memory.
         * Default: true.
         *
         * <p>When enabled, regions with unsaved changes will be written to the source
         * before being evicted. This ensures no data loss but may cause brief blocking
         * during eviction.
         *
         * <p>When disabled, dirty regions are discarded on eviction — any unsaved
         * changes are lost. This is useful for read-mostly workloads where losing
         * occasional writes is acceptable in exchange for faster eviction.
         *
         * <p>Requires {@link RegionSource#supportsWrite()} to return true for saves
         * to actually occur.
         */
        public @NotNull Builder saveOnEvict(boolean save) {
            this.saveOnEvict = save;
            return this;
        }

        /**
         * Optional. Forwarded to each {@link PolarLoader} created during chunk loading,
         * allowing custom user data to be read from chunks.
         */
        public @NotNull Builder worldAccess(@NotNull PolarWorldAccess worldAccess) {
            this.worldAccess = worldAccess;
            return this;
        }

        public @NotNull MultiPolarWorld build() {
            if (source == null) throw new IllegalStateException("source must be set");
            return new MultiPolarWorld(this);
        }
    }
}