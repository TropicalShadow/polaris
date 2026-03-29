package club.tesseract.polar.source;

import club.tesseract.polar.RegionKey;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory RegionSource for testing purposes.
 */
public class InMemoryRegionSource implements RegionSource {

    private final Map<RegionKey, byte[]> regions = new ConcurrentHashMap<>();
    private volatile boolean failOnRead = false;
    private volatile boolean failOnWrite = false;
    private volatile long readDelayMs = 0;

    public InMemoryRegionSource put(RegionKey key, byte[] data) {
        regions.put(key, data);
        return this;
    }

    public InMemoryRegionSource remove(RegionKey key) {
        regions.remove(key);
        return this;
    }

    public InMemoryRegionSource setFailOnRead(boolean fail) {
        this.failOnRead = fail;
        return this;
    }

    public InMemoryRegionSource setReadDelay(long delayMs) {
        this.readDelayMs = delayMs;
        return this;
    }

    public InMemoryRegionSource setFailOnWrite(boolean fail) {
        this.failOnWrite = fail;
        return this;
    }

    public byte[] get(RegionKey key) {
        return regions.get(key);
    }

    public boolean contains(RegionKey key) {
        return regions.containsKey(key);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> read(RegionKey key) {
        return CompletableFuture.supplyAsync(() -> {
            if (readDelayMs > 0) {
                try {
                    Thread.sleep(readDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failOnRead) {
                throw new RuntimeException("Simulated read failure");
            }
            return Optional.ofNullable(regions.get(key));
        });
    }

    @Override
    public CompletableFuture<List<RegionKey>> listAll() {
        return CompletableFuture.completedFuture(List.copyOf(regions.keySet()));
    }

    @Override
    public CompletableFuture<Void> write(RegionKey key, byte[] data) {
        return CompletableFuture.runAsync(() -> {
            if (failOnWrite) {
                throw new RuntimeException("Simulated write failure");
            }
            regions.put(key, data);
        });
    }

    @Override
    public boolean supportsWrite() {
        return true;
    }
}
