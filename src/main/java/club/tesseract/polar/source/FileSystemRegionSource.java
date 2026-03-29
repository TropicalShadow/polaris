package club.tesseract.polar.source;

import club.tesseract.polar.RegionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class FileSystemRegionSource implements RegionSource {
    private static final Logger log = LoggerFactory.getLogger(FileSystemRegionSource.class);


    private final Path root;
    private final Executor executor;

    public FileSystemRegionSource(Path root) {
        this(root, Executors.newVirtualThreadPerTaskExecutor());
    }

    public FileSystemRegionSource(Path root, Executor executor) {
        this.root = root;
        this.executor = executor;
    }


    private Path pathFor(RegionKey k) {
        return root.resolve("region_" + k.x() + "_" + k.z() + ".polar");
    }

    @Override
    public CompletableFuture<Optional<byte[]>> read(RegionKey k) {
        return CompletableFuture.supplyAsync(() -> {
            Path p = pathFor(k);
            if (!Files.exists(p)) return Optional.empty();
            try {
                return Optional.of(Files.readAllBytes(p));
            } catch (IOException e) {
                throw new RegionSourceException("Failed to read region " + k, e);
            }
        }, this.executor);
    }

    @Override
    public CompletableFuture<Void> write(RegionKey key, byte[] data) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(root);
                Path target = pathFor(key);
                Path temp = root.resolve("region_" + key.x() + "_" + key.z() + ".polar.tmp");

                Files.write(temp, data);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }

                log.debug("Wrote region {} ({} bytes)", key, data.length);
            } catch (IOException e) {
                throw new RegionSourceException("Failed to write region " + key, e);
            }
        }, executor);
    }

    @Override
    public boolean supportsWrite() {
        return true;
    }

    @Override
    public CompletableFuture<List<RegionKey>> listAll() {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isDirectory(root)) {
                log.warn("Region root {} does not exist, no regions to list", root);
                return List.of();
            }
            try (var stream = Files.list(root)) {
                return stream
                        .filter(p -> p.getFileName().toString().endsWith(".polar"))
                        .flatMap(p -> parseKey(p).stream())
                        .toList();
            } catch (IOException e) {
                throw new RegionSourceException("Failed to list regions in " + root, e);
            }
        }, executor);
    }

    private Optional<RegionKey> parseKey(Path path) {
        String name = path.getFileName().toString();
        try {
            String coords = name.substring("region_".length(), name.length() - ".polar".length());
            int lastUnderscore = coords.lastIndexOf('_');
            int x = Integer.parseInt(coords.substring(0, lastUnderscore));
            int z = Integer.parseInt(coords.substring(lastUnderscore + 1));
            return Optional.of(new RegionKey(x, z));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            log.warn("Skipping unrecognised file in region directory: {}", name);
            return Optional.empty();
        }
    }
}