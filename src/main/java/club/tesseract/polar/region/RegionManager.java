package club.tesseract.polar.region;

import club.tesseract.polar.ChunkSerializer;
import club.tesseract.polar.MultiPolarWorld;
import club.tesseract.polar.RegionKey;
import club.tesseract.polar.source.RegionSource;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarReader;
import net.hollowcube.polar.PolarWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Manages the lifecycle of loaded {@link PolarWorld} regions.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load regions on demand from a {@link RegionSource}, deduplicating concurrent requests.</li>
 *   <li>Track how many chunks are currently active per region via a ref count.</li>
 *   <li>Track dirty chunks and flush them to the source on demand.</li>
 *   <li>Evict regions that have been idle longer than the configured TTL (only if not dirty).</li>
 * </ul>
 *
 * <p>Thread safety: all public methods are safe to call from multiple threads.
 * {@link #tickEviction()} is expected to be called from a single background thread
 * but is safe if called concurrently.
 */
public class RegionManager {

    private static final Logger log = LoggerFactory.getLogger(RegionManager.class);

    private final ConcurrentHashMap<RegionKey, RegionEntry> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegionKey, CompletableFuture<RegionEntry>> loadingFutures = new ConcurrentHashMap<>();
    private final RegionSource source;
    private final long evictTtlMs;
    private final boolean saveOnEvict;
    private volatile @Nullable Function<RegionEntry, byte[]> serializer;

    public RegionManager(@NotNull RegionSource source, long evictTtlMs, boolean saveOnEvict) {
        this.source = source;
        this.evictTtlMs = evictTtlMs;
        this.saveOnEvict = saveOnEvict;
    }

    /**
     * Sets the serializer used for save-on-evict. Must be called before eviction
     * tick if saveOnEvict is enabled and write is supported.
     */
    public void setSerializer(@Nullable Function<RegionEntry, byte[]> serializer) {
        this.serializer = serializer;
    }

    /**
     * Returns the loaded {@link PolarWorld} for the given region key, or {@code null}
     * if the region is not currently in a READY state (still loading, or not loaded).
     *
     * <p>Prefer {@link #getOrLoad(RegionKey)} for the full async load path.
     * This method is useful for fast-path lookups from the chunk loader when
     * the region is expected to already be resident.
     */
    public @Nullable PolarWorld getIfReady(@NotNull RegionKey key) {
        RegionEntry entry = registry.get(key);
        if (entry == null || entry.state != RegionState.READY) return null;
        entry.lastAccessMs = System.currentTimeMillis();
        return entry.world;
    }

    /**
     * Returns a future that completes with the {@link PolarWorld} for the given region,
     * loading it from the source if necessary.
     *
     * <p>Concurrent calls for the same key are deduplicated — only one source read
     * is issued regardless of how many callers request the same region simultaneously.
     *
     * <p>If the region does not exist in the source, the future completes with an
     * empty {@link PolarWorld} (representing an unvisited, all-air region).
     *
     * <p>If the source read fails, the future completes exceptionally with a
     * {@link RegionLoadException}, and the registry entry is cleaned up so that
     * the next request will retry.
     */
    public @NotNull CompletableFuture<PolarWorld> getOrLoad(@NotNull RegionKey key) {
        RegionEntry existing = registry.get(key);
        if (existing != null && existing.state == RegionState.READY) {
            existing.lastAccessMs = System.currentTimeMillis();
            return CompletableFuture.completedFuture(existing.world);
        }

        return loadingFutures.computeIfAbsent(key, this::startLoad)
                .thenApply(entry -> entry.world);
    }

    /**
     * Increments the ref count for the region containing the given chunk coordinates.
     * Must be called when Minestom loads a chunk from this region (ChunkLoadEvent).
     *
     * <p>If the region entry is not present this is a no-op — the chunk load event
     * may fire before the registry entry is fully established in some edge cases,
     * but the load future's thenApply sets refs before returning so this should
     * not normally happen.
     */
    public void retainChunk(int chunkX, int chunkZ, int regionSize) {
        RegionKey key = RegionKey.fromChunk(chunkX, chunkZ, regionSize);
        RegionEntry entry = registry.get(key);
        if (entry != null) {
            entry.refs.incrementAndGet();
        }
    }

    /**
     * Decrements the ref count for the region containing the given chunk coordinates
     * and records the current time as the last-unload timestamp.
     * Must be called when Minestom unloads a chunk (ChunkUnloadEvent).
     */
    public void releaseChunk(int chunkX, int chunkZ, int regionSize) {
        RegionKey key = RegionKey.fromChunk(chunkX, chunkZ, regionSize);
        RegionEntry entry = registry.get(key);
        if (entry != null) {
            int remaining = entry.refs.decrementAndGet();
            if (remaining == 0) {
                entry.lastUnloadMs = System.currentTimeMillis();
            }
            if (remaining < 0) {
                log.error("Ref count underflow for region {} — retain/release calls are unbalanced", key);
                entry.refs.set(0);
            }
        }
    }

    /**
     * Scans all loaded regions and evicts those that satisfy all eviction conditions:
     * <ol>
     *   <li>No chunks are currently loaded from the region (refs == 0).</li>
     *   <li>The region is in READY state (not still loading).</li>
     *   <li>The region has been idle for longer than the configured TTL.</li>
     * </ol>
     *
     * <p>Dirty region handling depends on the {@code saveOnEvict} setting:
     * <ul>
     *   <li>If {@code saveOnEvict = true}: dirty regions are saved before eviction</li>
     *   <li>If {@code saveOnEvict = false}: dirty regions are discarded and evicted without saving</li>
     * </ul>
     *
     * <p>This method is intended to be called periodically by a background scheduler.
     *
     * @return the number of regions evicted in this tick
     */
    public int tickEviction() {
        int evicted = 0;
        long now = System.currentTimeMillis();

        for (var it = registry.entrySet().iterator(); it.hasNext(); ) {
            var mapEntry = it.next();
            RegionKey key = mapEntry.getKey();
            RegionEntry entry = mapEntry.getValue();

            if (canEvict(entry, now)) {
                if (entry.isDirty()) {
                    if (saveOnEvict && source.supportsWrite() && serializer != null) {
                        try {
                            byte[] data = serializer.apply(entry);
                            source.write(key, data).join();
                            entry.clearDirty();
                            log.debug("Saved dirty region {} before eviction ({} bytes)", key, data.length);
                        } catch (Exception e) {
                            log.error("Failed to save region {} on eviction, discarding changes: {}",
                                    key, e.getMessage());
                        }
                    } else {
                        log.debug("Discarding {} dirty chunks from region {} on eviction",
                                entry.getDirtyChunks().size(), key);
                    }
                }

                it.remove();
                evicted++;
                long idleSince = entry.lastUnloadMs > 0 ? entry.lastUnloadMs : entry.lastAccessMs;
                log.debug("Evicted region {} (idle {}ms)", key, now - idleSince);
            }
        }

        return evicted;
    }

    /**
     * Returns a snapshot of all currently loaded region keys.
     * Includes regions that are still in the LOADING state.
     */
    public @NotNull List<RegionKey> getLoadedRegions() {
        return new ArrayList<>(registry.keySet());
    }

    /**
     * Returns a snapshot of all region entries with their keys.
     * Useful for debugging and monitoring.
     */
    public @NotNull Map<RegionKey, RegionEntry> getAllEntries() {
        return new ConcurrentHashMap<>(registry);
    }

    /**
     * Returns the configured eviction TTL in milliseconds.
     */
    public long getEvictTtlMs() {
        return evictTtlMs;
    }

    /**
     * Returns the number of regions currently in the registry,
     * including those still loading.
     */
    public int getLoadedRegionCount() {
        return registry.size();
    }

    /**
     * Returns the underlying {@link RegionSource}. Used by {@link MultiPolarWorld}
     * to forward {@code listAll()} calls for preloading.
     */
    public @NotNull RegionSource source() {
        return source;
    }

    /**
     * Immediately removes the given region from the registry regardless of ref count
     * or idle time. In read-only mode this is always safe — nothing is lost.
     *
     * <p>If the region is currently in the LOADING state the entry is still removed;
     * any in-flight future will complete and clean itself up via the whenComplete handler.
     */
    public void forceEvict(@NotNull RegionKey key) {
        RegionEntry removed = registry.remove(key);
        if (removed != null) {
            log.debug("Force-evicted region {}", key);
        }
    }

    /**
     * Immediately removes all regions from the registry regardless of ref count or state.
     * Intended for use during shutdown only.
     *
     * <p><b>Warning:</b> This does NOT flush dirty regions. Call {@link #flushAll} first
     * if you need to persist changes.
     */
    public void closeAll() {
        int count = registry.size();
        registry.clear();
        loadingFutures.clear();
        if (count > 0) {
            log.debug("RegionManager closed — cleared {} region entries", count);
        }
    }

    /**
     * Marks a chunk as dirty by coordinates. The actual chunk data will be
     * serialized at flush time.
     *
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @param regionSize the region size (chunks per side)
     */
    public void markChunkDirty(int chunkX, int chunkZ, int regionSize) {
        RegionKey key = RegionKey.fromChunk(chunkX, chunkZ, regionSize);
        RegionEntry entry = registry.get(key);
        if (entry != null && entry.state == RegionState.READY) {
            entry.markDirtyCoords(chunkX, chunkZ);
            log.trace("Marked chunk ({}, {}) as dirty in region {}", chunkX, chunkZ, key);
        }
    }

    /**
     * Marks a chunk as dirty and immediately snapshots its data.
     * Use this when you have the Chunk object available.
     *
     * @param chunk the Minestom chunk that was modified
     * @param regionSize the region size (chunks per side)
     */
    public void markDirty(@NotNull Chunk chunk, int regionSize) {
        RegionKey key = RegionKey.fromChunk(chunk.getChunkX(), chunk.getChunkZ(), regionSize);
        RegionEntry entry = registry.get(key);
        if (entry != null && entry.state == RegionState.READY) {
            entry.snapshotChunk(chunk);
            log.trace("Snapshot chunk ({}, {}) in region {}",
                    chunk.getChunkX(), chunk.getChunkZ(), key);
        }
    }

    /**
     * Snapshots all dirty chunks from the instance before flushing.
     * Call this before flush to ensure all pending changes are captured.
     *
     * @param instance the instance to get chunks from
     * @param regionSize the region size
     */
    public void snapshotDirtyChunks(@NotNull Instance instance, int regionSize) {
        for (var mapEntry : registry.entrySet()) {
            RegionEntry entry = mapEntry.getValue();
            if (entry.state != RegionState.READY) continue;

            Set<Long> coords = entry.getDirtyCoords();
            if (coords.isEmpty()) continue;

            for (long packed : new ArrayList<>(coords)) {
                int chunkX = RegionEntry.unpackX(packed);
                int chunkZ = RegionEntry.unpackZ(packed);
                Chunk chunk = instance.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    entry.snapshotChunk(chunk);
                }
            }
        }
    }

    /**
     * Checks if a chunk exists in the polar data for its region.
     * Returns false if the region is not loaded or the chunk doesn't exist in the polar file.
     *
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @param regionSize the region size (chunks per side)
     * @return true if the chunk exists in polar data
     */
    public boolean hasChunkInPolar(int chunkX, int chunkZ, int regionSize) {
        RegionKey key = RegionKey.fromChunk(chunkX, chunkZ, regionSize);
        RegionEntry entry = registry.get(key);
        return entry != null && entry.hasChunkInPolar(chunkX, chunkZ);
    }

    /**
     * Returns {@code true} if any region has dirty chunks that need saving.
     */
    public boolean hasDirtyRegions() {
        return registry.values().stream().anyMatch(RegionEntry::isDirty);
    }

    /**
     * Returns a list of region keys that have dirty chunks.
     */
    public @NotNull List<RegionKey> getDirtyRegions() {
        List<RegionKey> dirty = new ArrayList<>();
        for (var entry : registry.entrySet()) {
            if (entry.getValue().isDirty()) {
                dirty.add(entry.getKey());
            }
        }
        return dirty;
    }

    /**
     * Flushes a single dirty region to the source.
     *
     * @param key the region to flush
     * @param serializer function to convert dirty chunks + existing world to polar bytes
     * @return a future that completes when the region is saved, or completes immediately
     *         if the region is not dirty or not loaded
     */
    public @NotNull CompletableFuture<Void> flushRegion(
            @NotNull RegionKey key,
            @NotNull Function<RegionEntry, byte[]> serializer) {

        if (!source.supportsWrite()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("RegionSource does not support writing"));
        }

        RegionEntry entry = registry.get(key);
        if (entry == null || !entry.isDirty()) {
            return CompletableFuture.completedFuture(null);
        }

        byte[] data;
        try {
            data = serializer.apply(entry);
        } catch (Exception e) {
            log.error("Failed to serialize region {} with {} dirty chunks: {}",
                    key, entry.getDirtyChunks().size(), e.getMessage(), e);
            return CompletableFuture.failedFuture(
                    new RegionSaveException("Failed to serialize region " + key, e));
        }

        return source.write(key, data)
                .thenRun(() -> {
                    entry.clearDirty();
                    log.debug("Flushed region {} ({} bytes)", key, data.length);
                })
                .exceptionally(ex -> {
                    log.error("Failed to flush region {}: {}", key, ex.getMessage());
                    throw new RegionSaveException("Failed to write region " + key, ex);
                });
    }

    /**
     * Flushes all dirty regions to the source.
     *
     * @param serializer function to convert dirty chunks + existing world to polar bytes
     * @return a future that completes when all regions are saved
     */
    public @NotNull CompletableFuture<Void> flushAll(
            @NotNull Function<RegionEntry, byte[]> serializer) {

        List<RegionKey> dirtyKeys = getDirtyRegions();
        if (dirtyKeys.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        log.debug("Flushing {} dirty regions", dirtyKeys.size());

        CompletableFuture<?>[] futures = dirtyKeys.stream()
                .map(key -> flushRegion(key, serializer))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }

    /**
     * Returns the RegionEntry for a given key, or null if not loaded.
     * Exposed for serialization during flush.
     */
    public @Nullable RegionEntry getEntry(@NotNull RegionKey key) {
        return registry.get(key);
    }

    private @NotNull CompletableFuture<RegionEntry> startLoad(@NotNull RegionKey key) {
        RegionEntry entry = new RegionEntry();
        registry.put(key, entry);
        log.debug("Loading region {}", key);

        return source.read(key)
                .thenApply(maybeBytes -> onLoadSuccess(key, entry, maybeBytes))
                .exceptionally(ex -> onLoadFailure(key, ex))
                .whenCompleteAsync((r, ex) -> loadingFutures.remove(key));
    }

    private @NotNull RegionEntry onLoadSuccess(
            @NotNull RegionKey key,
            @NotNull RegionEntry entry,
            @NotNull Optional<byte[]> maybeBytes) {

        PolarWorld world = maybeBytes
                .map(bytes -> {
                    try {
                        return PolarReader.read(bytes);
                    } catch (Exception e) {
                        throw new RegionLoadException("Corrupt polar data for region " + key, e);
                    }
                })
                .orElseGet(() -> {
                    log.debug("Region {} not found in source — returning empty world", key);
                    return new PolarWorld();
                });

        entry.world = world;
        entry.state = RegionState.READY;
        entry.lastAccessMs = System.currentTimeMillis();

        log.debug("Region {} loaded ({} chunks)", key,
                world.chunks() != null ? world.chunks().size() : 0);

        return entry;
    }

    private @Nullable RegionEntry onLoadFailure(@NotNull RegionKey key, @NotNull Throwable ex) {
        registry.remove(key);
        Throwable cause = unwrapCompletionException(ex);
        log.error("Failed to load region {}: {}", key, cause.getMessage(), cause);

        if (cause instanceof RegionLoadException rle) {
            throw rle;
        }
        throw new RegionLoadException("Failed to load region " + key, cause);
    }

    private boolean canEvict(@NotNull RegionEntry entry, long now) {
        if (entry.state != RegionState.READY || entry.refs.get() != 0) {
            return false;
        }
        long idleSince = entry.lastUnloadMs > 0 ? entry.lastUnloadMs : entry.lastAccessMs;
        return (now - idleSince) > evictTtlMs;
    }

    private static @NotNull Throwable unwrapCompletionException(@NotNull Throwable ex) {
        return (ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null)
                ? ex.getCause() : ex;
    }

    public enum RegionState {
        /** Source read is in progress. The entry exists in the registry but world is null. */
        LOADING,
        /** World is fully parsed and available for chunk lookups. */
        READY
    }

    public static final class RegionEntry {
        volatile RegionState state = RegionState.LOADING;
        volatile PolarWorld world;
        final AtomicInteger refs = new AtomicInteger(0);
        volatile long lastAccessMs = System.currentTimeMillis();
        volatile long lastUnloadMs = 0;

        private final Set<Long> dirtyCoords = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<Long, PolarChunk> snapshotChunks = new ConcurrentHashMap<>();

        public boolean isDirty() {
            return !dirtyCoords.isEmpty() || !snapshotChunks.isEmpty();
        }

        public @Nullable PolarWorld getWorld() {
            return world;
        }

        public @NotNull Map<Long, PolarChunk> getDirtyChunks() {
            return snapshotChunks;
        }

        public @NotNull Set<Long> getDirtyCoords() {
            return dirtyCoords;
        }

        public @NotNull RegionState getState() {
            return state;
        }

        public int getRefCount() {
            return refs.get();
        }

        public long getLastAccessMs() {
            return lastAccessMs;
        }

        public long getLastUnloadMs() {
            return lastUnloadMs;
        }

        /**
         * Returns the time in ms since this region was last used (for eviction calculation).
         */
        public long getIdleTimeMs() {
            long idleSince = lastUnloadMs > 0 ? lastUnloadMs : lastAccessMs;
            return System.currentTimeMillis() - idleSince;
        }

        /**
         * Returns the number of chunks stored in the polar data.
         */
        public int getChunkCount() {
            return world != null ? world.chunks().size() : 0;
        }

        /**
         * Checks if this region contains a chunk at the given coordinates in its polar data.
         * Returns false if the region is still loading or if the chunk doesn't exist.
         */
        public boolean hasChunkInPolar(int chunkX, int chunkZ) {
            if (state != RegionState.READY || world == null) {
                return false;
            }
            for (var chunk : world.chunks()) {
                if (chunk.x() == chunkX && chunk.z() == chunkZ) {
                    return true;
                }
            }
            return false;
        }

        void markDirtyCoords(int chunkX, int chunkZ) {
            dirtyCoords.add(packChunkCoords(chunkX, chunkZ));
        }

        void snapshotChunk(Chunk chunk) {
            long key = packChunkCoords(chunk.getChunkX(), chunk.getChunkZ());
            dirtyCoords.remove(key);
            snapshotChunks.put(key, ChunkSerializer.toPolarChunk(chunk));
        }

        Map<Long, PolarChunk> takeSnapshot() {
            Map<Long, PolarChunk> result = new HashMap<>(snapshotChunks);
            snapshotChunks.clear();
            dirtyCoords.clear();
            return result;
        }

        void clearDirty() {
            dirtyCoords.clear();
            snapshotChunks.clear();
        }

        static long packChunkCoords(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        static int unpackX(long packed) {
            return (int) (packed >> 32);
        }

        static int unpackZ(long packed) {
            return (int) packed;
        }
    }
}