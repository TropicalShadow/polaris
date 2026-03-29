package club.tesseract.polar.source;

import club.tesseract.polar.RegionKey;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface RegionSource {
    /** Raw polar bytes for this region, or empty if it doesn't exist. */
    CompletableFuture<Optional<byte[]>> read(RegionKey key);

    /**
     * Writes raw polar bytes for this region.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Override this method and {@link #supportsWrite()} to enable write support.
     *
     * @param key the region to write
     * @param data the polar-encoded bytes to write
     * @return a future that completes when the write is finished
     */
    default CompletableFuture<Void> write(RegionKey key, byte[] data) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("This RegionSource does not support writing"));
    }

    /**
     * Returns {@code true} if this source supports writing regions.
     *
     * <p>When this returns {@code false}, calls to {@link #write} will fail.
     * The default implementation returns {@code false}.
     */
    default boolean supportsWrite() {
        return false;
    }

    /** Optional — lets the loader list all regions on startup for preloading. */
    default CompletableFuture<List<RegionKey>> listAll() {
        return CompletableFuture.completedFuture(List.of());
    }
}
