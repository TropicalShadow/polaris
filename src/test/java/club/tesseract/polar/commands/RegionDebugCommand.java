package club.tesseract.polar.commands;

import club.tesseract.polar.MultiPolarWorld;
import club.tesseract.polar.RegionKey;
import club.tesseract.polar.region.RegionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;

import java.util.List;
import java.util.Map;

public class RegionDebugCommand extends Command {

    private final MultiPolarWorld loader;
    private final int regionSize;

    private static Component copyable(String text, NamedTextColor color) {
        return Component.text(text)
                .color(color)
                .decorate(TextDecoration.UNDERLINED)
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy")))
                .clickEvent(ClickEvent.copyToClipboard(text));
    }

    private static Component copyable(String text) {
        return copyable(text, NamedTextColor.AQUA);
    }

    private static Component runCommand(String label, String command, NamedTextColor color) {
        return Component.text("[" + label + "]")
                .color(color)
                .hoverEvent(HoverEvent.showText(Component.text("Click to run: " + command)))
                .clickEvent(ClickEvent.runCommand(command));
    }

    private Component formatRegionKey(RegionKey key) {
        String coords = key.x() + ", " + key.z();
        return Component.text("region(")
                .color(NamedTextColor.GRAY)
                .append(copyable(coords, NamedTextColor.YELLOW))
                .append(Component.text(")").color(NamedTextColor.GRAY));
    }

    public RegionDebugCommand(MultiPolarWorld loader, int regionSize) {
        super("region", "rg");
        this.loader = loader;
        this.regionSize = regionSize;

        setDefaultExecutor((sender, context) -> {
            sender.sendMessage("Usage: /region <list|status|info|dirty|preload|unload|flush>");
        });

                addSubcommand(new Command("list") {{
            setDefaultExecutor((sender, context) -> {
                List<RegionKey> regions = loader.getLoadedRegions();
                sender.sendMessage(Component.text("Loaded regions (" + regions.size() + "):")
                        .color(NamedTextColor.GOLD));
                for (RegionKey key : regions) {
                    sender.sendMessage(Component.text("  ")
                            .append(formatRegionKey(key))
                            .append(Component.text(" "))
                            .append(runCommand("info", "/region status", NamedTextColor.AQUA)));
                }
            });
        }});


        addSubcommand(new Command("status") {{
            setDefaultExecutor((sender, context) -> {
                Map<RegionKey, RegionManager.RegionEntry> entries = loader.getAllRegionEntries();
                long evictTtl = loader.getEvictTtlMs();

                sender.sendMessage(Component.text("=== Region Status (" + entries.size() + " loaded) ===")
                        .color(NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Eviction TTL: ")
                        .color(NamedTextColor.GRAY)
                        .append(copyable(evictTtl + "ms", NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("saveOnEvict: ")
                        .color(NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(loader.isSaveOnEvict()))
                                .color(loader.isSaveOnEvict() ? NamedTextColor.GREEN : NamedTextColor.RED)));
                sender.sendMessage(Component.empty());

                for (var entry : entries.entrySet()) {
                    RegionKey key = entry.getKey();
                    RegionManager.RegionEntry r = entry.getValue();

                    String state = r.getState().name();
                    int refs = r.getRefCount();
                    int dirtyCount = r.getDirtyChunks().size();
                    int chunkCount = r.getChunkCount();
                    long idleMs = r.getIdleTimeMs();

                    Component evictStatus;
                    if (r.getState() != RegionManager.RegionState.READY) {
                        evictStatus = Component.text("LOADING").color(NamedTextColor.YELLOW);
                    } else if (refs > 0) {
                        evictStatus = Component.text("IN_USE (" + refs + " refs)").color(NamedTextColor.GREEN);
                    } else if (dirtyCount > 0) {
                        evictStatus = Component.text("DIRTY (" + dirtyCount + " chunks)").color(NamedTextColor.GOLD);
                    } else if (idleMs < evictTtl) {
                        evictStatus = Component.text("COOLING (" + (evictTtl - idleMs) + "ms left)").color(NamedTextColor.AQUA);
                    } else {
                        evictStatus = Component.text("EVICTABLE").color(NamedTextColor.RED);
                    }

                    int minChunkX = key.x() * regionSize;
                    int minChunkZ = key.z() * regionSize;
                    int minBlockX = minChunkX * 16;
                    int minBlockZ = minChunkZ * 16;
                    int maxBlockX = minBlockX + (regionSize * 16) - 1;
                    int maxBlockZ = minBlockZ + (regionSize * 16) - 1;
                    String blockRange = minBlockX + "," + minBlockZ + " to " + maxBlockX + "," + maxBlockZ;

                    sender.sendMessage(formatRegionKey(key)
                            .append(Component.text(" ").color(NamedTextColor.GRAY))
                            .append(runCommand("unload", "/region unload " + key.x() + " " + key.z(), NamedTextColor.RED))
                            .append(Component.text(" ").color(NamedTextColor.GRAY))
                            .append(runCommand("tp", "/tp " + minBlockX + " 100 " + minBlockZ, NamedTextColor.GREEN)));

                    sender.sendMessage(Component.text("  blocks: ").color(NamedTextColor.GRAY)
                            .append(copyable(blockRange, NamedTextColor.WHITE)));

                    sender.sendMessage(Component.text("  state=").color(NamedTextColor.GRAY)
                            .append(Component.text(state).color(NamedTextColor.WHITE))
                            .append(Component.text(" chunks=").color(NamedTextColor.GRAY))
                            .append(copyable(String.valueOf(chunkCount), NamedTextColor.WHITE))
                            .append(Component.text(" idle=").color(NamedTextColor.GRAY))
                            .append(copyable(idleMs + "ms", NamedTextColor.WHITE)));

                    sender.sendMessage(Component.text("  evict=").color(NamedTextColor.GRAY)
                            .append(evictStatus));
                }
            });
        }});


        addSubcommand(new Command("info") {{
            setDefaultExecutor((sender, context) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command");
                    return;
                }

                Pos pos = player.getPosition();
                int blockX = pos.blockX();
                int blockY = pos.blockY();
                int blockZ = pos.blockZ();
                int chunkX = blockX >> 4;
                int chunkZ = blockZ >> 4;
                RegionKey key = RegionKey.fromChunk(chunkX, chunkZ, regionSize);

                int minChunkX = key.x() * regionSize;
                int minChunkZ = key.z() * regionSize;
                int minBlockX = minChunkX * 16;
                int minBlockZ = minChunkZ * 16;
                int maxBlockX = minBlockX + (regionSize * 16) - 1;
                int maxBlockZ = minBlockZ + (regionSize * 16) - 1;

                sender.sendMessage(Component.text("=== Current Location ===").color(NamedTextColor.GOLD));

                String posStr = blockX + ", " + blockY + ", " + blockZ;
                sender.sendMessage(Component.text("Position: ").color(NamedTextColor.GRAY)
                        .append(copyable(posStr, NamedTextColor.WHITE)));

                String chunkStr = chunkX + ", " + chunkZ;
                sender.sendMessage(Component.text("Chunk: ").color(NamedTextColor.GRAY)
                        .append(copyable(chunkStr, NamedTextColor.WHITE)));

                sender.sendMessage(Component.text("Region: ").color(NamedTextColor.GRAY)
                        .append(formatRegionKey(key))
                        .append(Component.text(" "))
                        .append(runCommand("unload", "/region unload " + key.x() + " " + key.z(), NamedTextColor.RED)));

                String boundsStr = minBlockX + "," + minBlockZ + " to " + maxBlockX + "," + maxBlockZ;
                sender.sendMessage(Component.text("Region bounds: ").color(NamedTextColor.GRAY)
                        .append(copyable(boundsStr, NamedTextColor.WHITE)));

                Map<RegionKey, RegionManager.RegionEntry> entries = loader.getAllRegionEntries();
                RegionManager.RegionEntry entry = entries.get(key);

                sender.sendMessage(Component.empty());

                if (entry == null) {
                    sender.sendMessage(Component.text("Region not loaded").color(NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("=== Region Details ===").color(NamedTextColor.GOLD));

                    sender.sendMessage(Component.text("State: ").color(NamedTextColor.GRAY)
                            .append(Component.text(entry.getState().name())
                                    .color(entry.getState() == RegionManager.RegionState.READY
                                            ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));

                    sender.sendMessage(Component.text("Ref count: ").color(NamedTextColor.GRAY)
                            .append(copyable(String.valueOf(entry.getRefCount()),
                                    entry.getRefCount() > 0 ? NamedTextColor.GREEN : NamedTextColor.WHITE)));

                    sender.sendMessage(Component.text("Chunks in polar: ").color(NamedTextColor.GRAY)
                            .append(copyable(String.valueOf(entry.getChunkCount()), NamedTextColor.WHITE)));

                    int dirtyCount = entry.getDirtyChunks().size();
                    sender.sendMessage(Component.text("Dirty chunks: ").color(NamedTextColor.GRAY)
                            .append(Component.text(String.valueOf(dirtyCount))
                                    .color(dirtyCount > 0 ? NamedTextColor.GOLD : NamedTextColor.WHITE)));

                    sender.sendMessage(Component.text("Idle time: ").color(NamedTextColor.GRAY)
                            .append(copyable(entry.getIdleTimeMs() + "ms", NamedTextColor.WHITE)));

                    boolean existsInPolar = entry.hasChunkInPolar(chunkX, chunkZ);
                    sender.sendMessage(Component.text("Current chunk in polar: ").color(NamedTextColor.GRAY)
                            .append(Component.text(existsInPolar ? "YES" : "NO (generated)")
                                    .color(existsInPolar ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
                }
            });
        }});


        addSubcommand(new Command("dirty") {{
            setDefaultExecutor((sender, context) -> {
                List<RegionKey> dirty = loader.getDirtyRegions();
                if (dirty.isEmpty()) {
                    sender.sendMessage(Component.text("No dirty regions").color(NamedTextColor.GREEN));
                    return;
                }

                sender.sendMessage(Component.text("Dirty regions (" + dirty.size() + "):")
                        .color(NamedTextColor.GOLD));
                Map<RegionKey, RegionManager.RegionEntry> entries = loader.getAllRegionEntries();
                for (RegionKey key : dirty) {
                    RegionManager.RegionEntry entry = entries.get(key);
                    int dirtyCount = entry != null ? entry.getDirtyChunks().size() : 0;
                    sender.sendMessage(Component.text("  ")
                            .append(formatRegionKey(key))
                            .append(Component.text(" (" + dirtyCount + " dirty chunks)").color(NamedTextColor.GOLD))
                            .append(Component.text(" "))
                            .append(runCommand("flush", "/region flush", NamedTextColor.GREEN)));
                }
            });
        }});


        addSubcommand(new Command("flush") {{
            setDefaultExecutor((sender, context) -> {
                List<RegionKey> dirty = loader.getDirtyRegions();
                if (dirty.isEmpty()) {
                    sender.sendMessage("No dirty regions to flush");
                    return;
                }

                sender.sendMessage("Flushing " + dirty.size() + " dirty regions...");
                long start = System.currentTimeMillis();
                loader.flush().thenRun(() -> {
                    long elapsed = System.currentTimeMillis() - start;
                    sender.sendMessage("Flush complete in " + elapsed + "ms");
                }).exceptionally(ex -> {
                    sender.sendMessage("Flush failed: " + ex.getMessage());
                    return null;
                });
            });
        }});


        var regionX = ArgumentType.Integer("regionX");
        var regionZ = ArgumentType.Integer("regionZ");

        addSubcommand(new Command("preload") {{
            addSyntax((sender, context) -> {
                int rx = context.get(regionX);
                int rz = context.get(regionZ);

                sender.sendMessage("Preloading region " + rx + ", " + rz + "...");
                loader.preloadRegion(rx, rz).thenRun(() -> {
                    sender.sendMessage("Region " + rx + ", " + rz + " loaded!");
                }).exceptionally(ex -> {
                    sender.sendMessage("Failed to load region: " + ex.getMessage());
                    return null;
                });
            }, regionX, regionZ);
        }});


        addSubcommand(new Command("unload") {{
            addSyntax((sender, context) -> {
                int rx = context.get(regionX);
                int rz = context.get(regionZ);

                loader.unloadRegion(rx, rz);
                sender.sendMessage("Region " + rx + ", " + rz + " unloaded");
            }, regionX, regionZ);
        }});


        addSubcommand(new Command("preloadall") {{
            setDefaultExecutor((sender, context) -> {
                sender.sendMessage("Preloading all regions...");
                loader.preloadAll().thenRun(() -> {
                    sender.sendMessage("All regions loaded! Total: " + loader.getLoadedRegions().size());
                }).exceptionally(ex -> {
                    sender.sendMessage("Failed: " + ex.getMessage());
                    return null;
                });
            });
        }});


        addSubcommand(new Command("evict") {{
            setDefaultExecutor((sender, context) -> {
                Map<RegionKey, RegionManager.RegionEntry> entries = loader.getAllRegionEntries();
                long evictTtl = loader.getEvictTtlMs();

                int evictable = 0;
                for (var entry : entries.entrySet()) {
                    RegionManager.RegionEntry r = entry.getValue();
                    if (r.getState() == RegionManager.RegionState.READY &&
                        r.getRefCount() == 0 &&
                        !r.isDirty() &&
                        r.getIdleTimeMs() >= evictTtl) {
                        evictable++;
                        sender.sendMessage("  Evictable: " + entry.getKey());
                    }
                }

                if (evictable == 0) {
                    sender.sendMessage("No regions eligible for eviction");
                } else {
                    sender.sendMessage("Found " + evictable + " evictable region(s)");
                    sender.sendMessage("(Eviction will happen automatically on next tick)");
                }
            });
        }});
    }
}
