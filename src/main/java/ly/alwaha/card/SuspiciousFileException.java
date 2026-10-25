package ly.alwaha.card;

import java.nio.file.Path;

/**
 * An input file that failed the metadata checks. It's never used: the content is encrypted into
 * quarantine and our copy is deleted. Files on the onboarding server are never touched.
 */
public class SuspiciousFileException extends RejectedException {

    private final transient Path file;
    private transient Path quarantinedTo;

    /** A local file (our copy) that still has to be encrypted into quarantine. */
    public SuspiciousFileException(Path file, String reason) {
        super(file.getFileName() + " is suspicious: " + reason);
        this.file = file;
    }

    /** A remote file whose content was already streamed into quarantine (never stored unencrypted). */
    public SuspiciousFileException(String name, String reason, Path quarantinedTo) {
        super(name + " is suspicious: " + reason);
        this.file = null;
        this.quarantinedTo = quarantinedTo;
    }

    public Path file() {
        return file;
    }

    public Path quarantinedTo() {
        return quarantinedTo;
    }

    void markQuarantined(Path enc) {
        this.quarantinedTo = enc;
    }
}
