package club.tesseract.polar.commands;

import club.tesseract.polar.ChunkUnloader;
import club.tesseract.polar.MultiPolarWorld;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.Nullable;

public class DebugCommands {

    public static Command gamemode() {
        return new Command("gamemode", "gm") {{
            var mode = ArgumentType.Enum("mode", GameMode.class);

            setDefaultExecutor((sender, context) -> {
                if (sender instanceof Player player) {
                    sender.sendMessage("Current gamemode: " + player.getGameMode());
                }
                sender.sendMessage("Usage: /gamemode <survival|creative|adventure|spectator>");
            });

            addSyntax((sender, context) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command");
                    return;
                }

                GameMode gm = context.get(mode);
                player.setGameMode(gm);
                player.sendMessage("Gamemode set to " + gm);
            }, mode);
        }};
    }

    public static Command fly() {
        return new Command("fly") {{
            setDefaultExecutor((sender, context) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command");
                    return;
                }

                boolean flying = !player.isAllowFlying();
                player.setAllowFlying(flying);
                player.setFlying(flying);
                player.sendMessage("Flying " + (flying ? "enabled" : "disabled"));
            });
        }};
    }

    public static Command speed() {
        return new Command("speed") {{
            var speedArg = ArgumentType.Float("speed");

            setDefaultExecutor((sender, context) -> {
                if (sender instanceof Player player) {
                    sender.sendMessage("Current fly speed: " + player.getFlyingSpeed());
                }
                sender.sendMessage("Usage: /speed <0.0-1.0>");
            });

            addSyntax((sender, context) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command");
                    return;
                }

                float speed = context.get(speedArg);
                player.setFlyingSpeed(speed);
                player.sendMessage("Fly speed set to " + speed);
            }, speedArg);
        }};
    }

    public static Command pos() {
        return new Command("pos", "position", "where") {{
            setDefaultExecutor((sender, context) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command");
                    return;
                }

                var pos = player.getPosition();
                sender.sendMessage("Position: %.2f, %.2f, %.2f".formatted(pos.x(), pos.y(), pos.z()));
                sender.sendMessage("Chunk: %d, %d".formatted(pos.blockX() >> 4, pos.blockZ() >> 4));
                sender.sendMessage("Yaw: %.1f, Pitch: %.1f".formatted(pos.yaw(), pos.pitch()));
            });
        }};
    }

    public static Command chunks(@Nullable ChunkUnloader unloader) {
        return new Command("chunks") {{
            setDefaultExecutor((sender, context) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command");
                    return;
                }

                Instance instance = player.getInstance();
                if (instance == null) {
                    sender.sendMessage("Not in an instance");
                    return;
                }

                int count = instance.getChunks().size();
                sender.sendMessage("Loaded chunks: " + count);

                if (unloader != null) {
                    int orphaned = unloader.getOrphanedChunkCount();
                    sender.sendMessage("Orphaned chunks (pending unload): " + orphaned);
                }
            });
        }};
    }

    public static Command gc() {
        return new Command("gc") {{
            setDefaultExecutor((sender, context) -> {
                long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                System.gc();
                long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

                sender.sendMessage("Garbage collection triggered");
                sender.sendMessage("Memory: %.2f MB -> %.2f MB".formatted(
                        before / 1024.0 / 1024.0,
                        after / 1024.0 / 1024.0
                ));
            });
        }};
    }

    public static Command memory() {
        return new Command("memory", "mem") {{
            setDefaultExecutor((sender, context) -> {
                Runtime rt = Runtime.getRuntime();
                long used = rt.totalMemory() - rt.freeMemory();
                long max = rt.maxMemory();

                sender.sendMessage("Memory: %.2f / %.2f MB (%.1f%%)".formatted(
                        used / 1024.0 / 1024.0,
                        max / 1024.0 / 1024.0,
                        (used * 100.0) / max
                ));
            });
        }};
    }

    public static Command stop(@Nullable MultiPolarWorld loader) {
        return new Command("stop", "shutdown") {{
            setDefaultExecutor((sender, context) -> {
                sender.sendMessage("Stopping server...");
                if (loader != null && loader.hasPendingChanges()) {
                    sender.sendMessage("Flushing " + loader.getDirtyRegions().size() + " dirty regions...");
                    loader.flushSync();
                    sender.sendMessage("Flush complete.");
                }
                System.exit(0);
            });
        }};
    }
}
