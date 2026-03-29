package club.tesseract.polar;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerChunkLoadEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.trait.InstanceEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatically unloads chunks that have no nearby players for a configured duration.
 *
 * <p>Minestom doesn't have built-in chunk unloading, so this utility provides it
 * for testing the MultiPolarWorld eviction system.
 *
 * <p>The unloader tracks player view distances and unloads chunks that:
 * <ul>
 *   <li>Have no players within view distance</li>
 *   <li>Have been idle for longer than the configured TTL</li>
 * </ul>
 */
public class ChunkUnloader {

    private static final Logger log = LoggerFactory.getLogger(ChunkUnloader.class);

    private final Instance instance;
    private final int viewDistance;
    private final long unloadDelayMs;
    private final ConcurrentHashMap<Long, Long> orphanedChunks = new ConcurrentHashMap<>();
    private Task checkTask;

    /**
     * Creates a new chunk unloader.
     *
     * @param instance the instance to manage
     * @param viewDistance chunks within this radius of any player are kept loaded
     * @param unloadDelay how long a chunk must be orphaned before unloading
     * @param checkInterval how often to check for unloadable chunks
     */
    public ChunkUnloader(Instance instance, int viewDistance, Duration unloadDelay, Duration checkInterval) {
        this.instance = instance;
        this.viewDistance = viewDistance;
        this.unloadDelayMs = unloadDelay.toMillis();

        EventNode<InstanceEvent> node = instance.eventNode();
        node.addListener(PlayerMoveEvent.class, this::onPlayerMove);
        node.addListener(PlayerChunkLoadEvent.class, this::onChunkLoad);

        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> {
            if (e.getInstance() == instance) {
                refreshAllChunks();
            }
        });

        checkTask = MinecraftServer.getSchedulerManager()
                .buildTask(this::tickUnload)
                .delay(checkInterval)
                .repeat(checkInterval)
                .schedule();

        log.info("ChunkUnloader started: viewDistance={}, unloadDelay={}ms, checkInterval={}ms",
                viewDistance, unloadDelayMs, checkInterval.toMillis());
    }

    /**
     * Stops the unloader and cancels the scheduled task.
     */
    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        orphanedChunks.clear();
        log.info("ChunkUnloader stopped");
    }

    /**
     * Returns the number of chunks currently tracked as orphaned.
     */
    public int getOrphanedChunkCount() {
        return orphanedChunks.size();
    }

    private void onPlayerMove(PlayerMoveEvent event) {
        Point from = event.getPlayer().getPosition();
        Point to = event.getNewPosition();

        int fromChunkX = from.blockX() >> 4;
        int fromChunkZ = from.blockZ() >> 4;
        int toChunkX = to.blockX() >> 4;
        int toChunkZ = to.blockZ() >> 4;

        if (fromChunkX != toChunkX || fromChunkZ != toChunkZ) {
            refreshChunksNearPlayer(event.getPlayer());
        }
    }

    private void onChunkLoad(PlayerChunkLoadEvent event) {
        orphanedChunks.remove(packCoords(event.getChunkX(), event.getChunkZ()));
    }

    private void refreshAllChunks() {
        Collection<Chunk> chunks = instance.getChunks();
        Set<Long> activeChunks = getActiveChunks();

        long now = System.currentTimeMillis();
        for (Chunk chunk : chunks) {
            long key = packCoords(chunk.getChunkX(), chunk.getChunkZ());
            if (activeChunks.contains(key)) {
                orphanedChunks.remove(key);
            } else if (!orphanedChunks.containsKey(key)) {
                orphanedChunks.put(key, now);
            }
        }
    }

    private void refreshChunksNearPlayer(Player player) {
        int chunkX = player.getPosition().blockX() >> 4;
        int chunkZ = player.getPosition().blockZ() >> 4;

        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                long key = packCoords(chunkX + dx, chunkZ + dz);
                orphanedChunks.remove(key);
            }
        }
    }

    private Set<Long> getActiveChunks() {
        Set<Long> active = new HashSet<>();
        for (Player player : instance.getPlayers()) {
            int chunkX = player.getPosition().blockX() >> 4;
            int chunkZ = player.getPosition().blockZ() >> 4;

            for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                    active.add(packCoords(chunkX + dx, chunkZ + dz));
                }
            }
        }
        return active;
    }

    private void tickUnload() {
        long now = System.currentTimeMillis();
        Set<Long> activeChunks = getActiveChunks();
        int unloaded = 0;

        for (Chunk chunk : instance.getChunks()) {
            long key = packCoords(chunk.getChunkX(), chunk.getChunkZ());
            if (activeChunks.contains(key)) {
                orphanedChunks.remove(key);
            } else if (!orphanedChunks.containsKey(key)) {
                orphanedChunks.put(key, now);
            }
        }

        var iterator = orphanedChunks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getKey();
            long orphanedAt = entry.getValue();

            if (now - orphanedAt >= unloadDelayMs) {
                int chunkX = unpackX(key);
                int chunkZ = unpackZ(key);

                Chunk chunk = instance.getChunk(chunkX, chunkZ);
                if (chunk != null && !activeChunks.contains(key)) {
                    instance.unloadChunk(chunk);
                    unloaded++;
                    log.debug("Unloaded chunk ({}, {}) after {}ms idle",
                            chunkX, chunkZ, now - orphanedAt);
                }
                iterator.remove();
            }
        }

        if (unloaded > 0) {
            log.info("Unloaded {} idle chunks ({} chunks remaining, {} orphaned)",
                    unloaded, instance.getChunks().size(), orphanedChunks.size());
        }
    }

    private static long packCoords(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }
}
