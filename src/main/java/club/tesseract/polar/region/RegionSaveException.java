package club.tesseract.polar.region;

/**
 * Thrown when a region cannot be saved to its source.
 *
 * <p>This may occur due to:
 * <ul>
 *   <li>I/O failure — the target could not be written (disk full, permissions, network error).</li>
 *   <li>Serialization failure — chunks could not be converted to polar format.</li>
 * </ul>
 */
public class RegionSaveException extends RuntimeException {

    public RegionSaveException(String message, Throwable cause) {
        super(message, cause);
    }

    public RegionSaveException(String message) {
        super(message);
    }
}
