package ly.alwaha.card;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * Sends every card in outbox/ to T24. A failed send stays in outbox/ for the next cycle.
 * After maxAttempts it moves to failed/. The attempt count is a small sidecar file, so no database is needed.
 */
public final class Dispatcher {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Config cfg;
    private final AuditLog audit;

    public Dispatcher(Config cfg, AuditLog audit) {
        this.cfg = cfg;
        this.audit = audit;
    }

    public void dispatchOutbox() throws IOException {
        List<Path> cards = pending();
        if (cards.isEmpty()) return;
        try (CardTarget up = cfg.t24Local() != null ? LocalFolders.target(cfg.t24Local().dir()) : SftpConnection.connect(cfg.t24Sftp())) {
            for (Path card : cards) send(up, card);
        } catch (IOException | RuntimeException e) {
            // Couldn't connect, or the session dropped: every card still in the outbox counts one attempt.
            for (Path card : pending()) attemptFailed(card, e);
        }
    }

    private void send(CardTarget up, Path card) throws IOException {
        String name = card.getFileName().toString();
        String account = name.substring(0, name.length() - ".jpg".length());
        byte[] data = Files.readAllBytes(card);
        String sha = AuditLog.sha256(data);
        switch (up.upload(data, name)) {
            case DELIVERED, ALREADY_PRESENT -> {
                Files.deleteIfExists(attemptsFile(card));
                Files.delete(card);
                audit.log("DELIVERED", account, "remote=" + up.remotePath(name) + " sha256=" + sha);
            }
            case REMOTE_DIFFERS -> {
                String reason = "a different " + name + " already exists on T24; overwrite refused";
                moveToFailed(card, reason);
                audit.log("REFUSED", account, "reason=\"" + reason + "\"");
            }
        }
    }

    private void attemptFailed(Path card, Exception e) throws IOException {
        String name = card.getFileName().toString();
        String account = name.substring(0, name.length() - ".jpg".length());
        Path counter = attemptsFile(card);
        int attempts = (Files.exists(counter) ? Integer.parseInt(Files.readString(counter).trim()) : 0) + 1;
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (attempts >= cfg.maxAttempts()) {
            moveToFailed(card, "gave up after " + attempts + " attempts; last error " + reason);
            audit.log("FAILED", account, "attempts=" + attempts + " reason=\"" + reason + "\"");
        } else {
            Files.writeString(counter, Integer.toString(attempts));
            audit.log("RETRY", account, "attempt=" + attempts + "/" + cfg.maxAttempts() + " reason=\"" + reason + "\"");
        }
    }

    /** Live delivery state of an account's card: PENDING (outbox), FAILED (gave up), DELIVERED, or NONE. */
    public static String deliveryStatus(Config cfg, String account) throws IOException {
        if (Files.exists(cfg.outbox().resolve(account + ".jpg"))) return "PENDING";
        if (Files.isDirectory(cfg.failed())) {
            try (Stream<Path> s = Files.list(cfg.failed())) {
                if (s.anyMatch(p -> p.getFileName().toString().endsWith("-" + account + ".jpg"))) return "FAILED";
            }
        }
        return Files.exists(cfg.archive().resolve(account + ".jpg")) ? "DELIVERED" : "NONE";
    }

    private List<Path> pending() throws IOException {
        if (!Files.isDirectory(cfg.outbox())) return List.of();
        try (Stream<Path> files = Files.list(cfg.outbox())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jpg")
                    && !p.getFileName().toString().startsWith(".")).sorted().toList();
        }
    }

    private static Path attemptsFile(Path card) {
        return card.resolveSibling(card.getFileName() + ".attempts");
    }

    private void moveToFailed(Path card, String reason) throws IOException {
        Files.deleteIfExists(attemptsFile(card)); // a retry from the dashboard starts counting again
        Files.createDirectories(cfg.failed());
        String stamp = LocalDateTime.now().format(STAMP);
        Path target = cfg.failed().resolve(stamp + "-" + card.getFileName());
        Files.move(card, target, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(target.resolveSibling(target.getFileName() + ".reason.txt"),
                reason + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
