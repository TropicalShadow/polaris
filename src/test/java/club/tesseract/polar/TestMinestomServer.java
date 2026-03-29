package club.tesseract.polar;

import club.tesseract.polar.commands.DebugCommands;
import club.tesseract.polar.commands.RegionDebugCommand;
import club.tesseract.polar.commands.TeleportCommand;
import club.tesseract.polar.source.FileSystemRegionSource;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * A test Minestom server that demonstrates using MultiPolarWorld to load
 * chunks from multiple polar region files.
 *
 * <p>This server:
 * <ul>
 *   <li>Sets up a MultiPolarWorld loader with FileSystemRegionSource</li>
 *   <li>Uses Minestom's chunk generator for regions that don't exist</li>
 *   <li>Spawns players at (0, 65, 0)</li>
 * </ul>
 *
 * <p>Run this class to start a test server on port 25565.
 * Since we don't have pre-existing polar files, chunks will be generated
 * by Minestom's generator and the MultiPolarWorld will handle the loading logic.
 */
public class TestMinestomServer {

    private static final Logger log = LoggerFactory.getLogger(TestMinestomServer.class);
    private static final int REGION_SIZE = 32;
    private static final int VIEW_DISTANCE = 8;
    private static final Path REGIONS_DIR = Path.of("test-regions");

    public static void main(String[] args) throws IOException {
        log.info("Starting test Minestom server with MultiPolarWorld...");

        Files.createDirectories(REGIONS_DIR);

        MinecraftServer server = MinecraftServer.init();
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();

        InstanceContainer instance = instanceManager.createInstanceContainer();
        instance.setGenerator(TestMinestomServer::generateFlat);

        FileSystemRegionSource source = new FileSystemRegionSource(REGIONS_DIR);
        MultiPolarWorld loader = MultiPolarWorld.builder()
                .source(source)
                .regionSize(REGION_SIZE)
                .evictAfter(Duration.ofMinutes(5))
                .evictCheckInterval(Duration.ofSeconds(30))
                .saveGeneratedChunks(true)
                .build();

        instance.setChunkLoader(loader);
        loader.hook(instance);

        ChunkUnloader chunkUnloader = new ChunkUnloader(
                instance,
                VIEW_DISTANCE,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10)
        );

        registerCommands(loader, chunkUnloader);

        MinecraftServer.getSchedulerManager().buildTask(() -> {
            List<RegionKey> regions = loader.getLoadedRegions();
            log.info("Loaded regions ({}): {}", regions.size(), regions);
        }).delay(Duration.ofSeconds(5)).repeat(Duration.ofSeconds(30)).schedule();

        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 65, 0));
            event.getPlayer().setGameMode(GameMode.CREATIVE);
            log.info("Player {} joining at spawn", event.getPlayer().getUsername());
        });

        server.start("0.0.0.0", 25565);
        log.info("Server started on port 25565!");
        log.info("Connect with a Minecraft client to test chunk loading");
        log.info("Regions directory: {}", REGIONS_DIR.toAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            chunkUnloader.stop();
            loader.close();
            MinecraftServer.stopCleanly();
        }));
    }

    private static void generateFlat(GenerationUnit unit) {
        unit.modifier().fillHeight(0, 1, Block.BEDROCK);
        unit.modifier().fillHeight(1, 63, Block.STONE);
        unit.modifier().fillHeight(63, 64, Block.DIRT);
        unit.modifier().fillHeight(64, 65, Block.GRASS_BLOCK);
    }

    private static void registerCommands(MultiPolarWorld loader, ChunkUnloader chunkUnloader) {
        CommandManager cmd = MinecraftServer.getCommandManager();
        cmd.register(new TeleportCommand());
        cmd.register(new RegionDebugCommand(loader, REGION_SIZE));
        cmd.register(DebugCommands.gamemode());
        cmd.register(DebugCommands.fly());
        cmd.register(DebugCommands.speed());
        cmd.register(DebugCommands.pos());
        cmd.register(DebugCommands.chunks(chunkUnloader));
        cmd.register(DebugCommands.gc());
        cmd.register(DebugCommands.memory());
        cmd.register(DebugCommands.stop(loader));

        log.info("Registered {} commands", cmd.getCommands().size());
    }
}
