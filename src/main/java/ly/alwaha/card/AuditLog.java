package ly.alwaha.card;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * One line per event in archive/dispatch.log, and the same line on the console.
 * Never logs names or image data, only the account number (it's the file name anyway), the hash and the outcome.
 */
public final class AuditLog {

    private final Path file;

    public AuditLog(Path file) {
        this.file = file;
    }

    public synchronized void log(String event, String account, String detail) {
        String line = Instant.now().truncatedTo(ChronoUnit.SECONDS) + " " + event
                + " account=" + (account == null ? "-" : account)
                + (detail == null || detail.isEmpty() ? "" : " " + detail);
        System.out.println(line);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write audit log " + file, e);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
