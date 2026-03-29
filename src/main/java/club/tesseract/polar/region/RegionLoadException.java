package club.tesseract.polar.region;

import club.tesseract.polar.source.RegionSource;

/**
 * Thrown when a region cannot be loaded from its source.
 *
 * <p>Distinguishes between two root causes:
 * <ul>
 *   <li>I/O failure — the source could not be read (network error, disk error, permissions).</li>
 *   <li>Parse failure — bytes were retrieved but {@code PolarReader.read()} rejected them
 *       as corrupt or incompatible.</li>
 * </ul>
 *
 * <p>Region-not-found is NOT represented by this exception — it is a normal outcome
 * communicated via {@code Optional.empty()} from {@link RegionSource#read}.
 */
public class RegionLoadException extends RuntimeException {
    public RegionLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    public RegionLoadException(String message) {
        super(message);
    }
}
