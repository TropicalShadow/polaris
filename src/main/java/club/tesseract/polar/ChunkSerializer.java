package club.tesseract.polar;

import club.tesseract.polar.region.RegionManager;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarSection;
import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWriter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Minestom {@link Chunk} objects to Polar format for saving.
 *
 * <p>This serializer creates {@link PolarChunk} and {@link PolarSection} objects
 * from Minestom's internal chunk representation, suitable for writing to disk
 * via {@link PolarWriter}.
 */
public final class ChunkSerializer {

    private ChunkSerializer() {}

    /**
     * Serializes a RegionEntry (containing dirty chunks and the original world) to polar bytes.
     *
     * @param entry the region entry containing the PolarWorld and dirty chunks
     * @param regionSize the region size (chunks per side)
     * @return polar-encoded bytes ready to write to disk
     */
    public static byte[] serializeRegion(
            @NotNull RegionManager.RegionEntry entry,
            int regionSize) {

        PolarWorld originalWorld = entry.getWorld();

        Map<Long, PolarChunk> chunkMap = new HashMap<>();
        if (originalWorld != null && originalWorld.chunks() != null) {
            for (PolarChunk chunk : originalWorld.chunks()) {
                chunkMap.put(packCoords(chunk.x(), chunk.z()), chunk);
            }
        }

        for (PolarChunk dirtyChunk : entry.getDirtyChunks().values()) {
            chunkMap.put(packCoords(dirtyChunk.x(), dirtyChunk.z()), dirtyChunk);
        }

        PolarWorld.CompressionType compression = originalWorld != null
                ? originalWorld.compression()
                : PolarWorld.CompressionType.ZSTD;
        byte minSection = originalWorld != null ? originalWorld.minSection() : (byte) -4;
        byte maxSection = originalWorld != null ? originalWorld.maxSection() : (byte) 20;
        byte[] userData = originalWorld != null ? originalWorld.userData() : new byte[0];

        PolarWorld newWorld = new PolarWorld(
                PolarWorld.LATEST_VERSION,
                MinecraftServer.DATA_VERSION,
                compression,
                minSection,
                maxSection,
                userData,
                new ArrayList<>(chunkMap.values())
        );

        return PolarWriter.write(newWorld);
    }

    /**
     * Converts a Minestom {@link Chunk} to a {@link PolarChunk}.
     */
    public static @NotNull PolarChunk toPolarChunk(@NotNull Chunk chunk) {
        int minSection = chunk.getMinSection();
        int maxSection = chunk.getMaxSection();
        int sectionCount = maxSection - minSection;

        PolarSection[] sections = new PolarSection[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            int sectionY = minSection + i;
            sections[i] = toPolarSection(chunk, sectionY);
        }

        return new PolarChunk(
                chunk.getChunkX(),
                chunk.getChunkZ(),
                sections,
                List.of(),
                new int[32][],
                new byte[0]
        );
    }

    /**
     * Converts a single section of a Minestom chunk to a {@link PolarSection}.
     */
    private static @NotNull PolarSection toPolarSection(@NotNull Chunk chunk, int sectionY) {
        int baseY = sectionY * 16;

        List<String> blockPaletteList = new ArrayList<>();
        Map<String, Integer> blockPaletteIndex = new HashMap<>();
        int[] blockData = new int[4096];
        boolean isEmpty = true;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block block = chunk.getBlock(x, baseY + y, z);
                    String blockName = block.name();

                    int paletteIdx = blockPaletteIndex.computeIfAbsent(blockName, name -> {
                        int idx = blockPaletteList.size();
                        blockPaletteList.add(name);
                        return idx;
                    });

                    blockData[y * 256 + z * 16 + x] = paletteIdx;

                    if (!block.isAir()) {
                        isEmpty = false;
                    }
                }
            }
        }

        if (isEmpty && blockPaletteList.size() == 1) {
            return new PolarSection();
        }

        String[] biomePalette = new String[]{"minecraft:plains"};

        return new PolarSection(
                blockPaletteList.toArray(new String[0]),
                blockData,
                biomePalette,
                null,
                PolarSection.LightContent.MISSING,
                null,
                PolarSection.LightContent.MISSING,
                null
        );
    }

    private static long packCoords(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
