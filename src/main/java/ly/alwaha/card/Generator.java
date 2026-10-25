package ly.alwaha.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ly.alwaha.card.FolderInput.InputSet;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Customer data + two images → {AccountNumber}.jpg in outbox/ and archive/.
 * One card per account: if the account already has a card, its result is returned and nothing is overwritten.
 */
public final class Generator {

    /** existing = the account already had a card; requestId = the request that created that card. */
    public record Result(String accountNumber, String requestId, String sha256, boolean existing) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Config cfg;
    private final CardRenderer renderer;
    private final AuditLog audit;
    private final CustomerLookup lookup;
    private final Quarantine quarantine;

    public Generator(Config cfg, CardRenderer renderer, AuditLog audit, CustomerLookup lookup, Quarantine quarantine) {
        this.cfg = cfg;
        this.renderer = renderer;
        this.audit = audit;
        this.lookup = lookup;
        this.quarantine = quarantine;
    }

    public int accountDigits() {
        return renderer.layout().accountNumberBoxes().size();
    }

    /** Folder input (work/inbox/): kept for testing and manual drops. */
    public void processInbox() throws IOException {
        for (Path dir : FolderInput.readyFolders(cfg.inbox(), cfg.stableSeconds())) {
            String id = dir.getFileName().toString();
            try {
                generate(FolderInput.read(dir));
                moveAside(dir, cfg.processed(), null);
            } catch (SuspiciousFileException e) {
                moveAside(dir, cfg.failed(), e.getMessage() + " (encrypted to quarantine/"
                        + e.quarantinedTo().getFileName() + ", our copy deleted)");
            } catch (RejectedException e) {
                audit.log("REJECTED", null, "input=" + id + " reason=\"" + e.getMessage() + "\"");
                moveAside(dir, cfg.failed(), e.getMessage());
            } catch (IOException e) {
                // A temporary problem (onboarding API down, disk): leave the set in inbox/ for the next cycle.
                audit.log("ERROR", null, "input=" + id + " reason=\"" + e.getMessage() + "\"");
            }
        }
    }

    /** Folder input: onboarding API lookup first, then the card. */
    public Result generate(InputSet in) throws IOException, RejectedException {
        CardData data = lookup.lookup(in.identity());
        data.validate(accountDigits());
        Result existing = existing(data.accountNumber());
        if (existing != null) {
            audit.log("EXISTING", data.accountNumber(), "input=" + in.id() + " original_request=" + existing.requestId());
            return existing;
        }
        return generateCard(in.id(), null, data, in.selfie(), in.signature());
    }

    /** The card already made for this account, or null. */
    public Result existing(String account) throws IOException {
        Path card = cfg.archive().resolve(account + ".jpg");
        if (!Files.exists(card)) return null;
        Path meta = cfg.archive().resolve(account + ".json");
        String requestId = null;
        if (Files.exists(meta)) {
            JsonNode n = JSON.readTree(meta.toFile()).path("requestId");
            requestId = n.isTextual() ? n.asText() : null;
        }
        return new Result(account, requestId, AuditLog.sha256(Files.readAllBytes(card)), true);
    }

    /**
     * Checks both images (metadata first), renders the card with the request UUID as its fingerprint,
     * and queues it. A suspicious image is encrypted into quarantine and our copy deleted.
     */
    public Result generateCard(String id, UUID requestId, CardData data, Path selfiePath, Path signaturePath)
            throws IOException, RejectedException {
        data.validate(accountDigits());
        BufferedImage selfie;
        BufferedImage signature;
        try {
            selfie = ImageLoader.load(selfiePath, cfg.limits());
            signature = ImageLoader.load(signaturePath, cfg.limits());
        } catch (SuspiciousFileException e) {
            if (e.quarantinedTo() == null) e.markQuarantined(quarantine.encryptAndDelete(e.file(), id));
            recordSuspicious(id, e);
            throw e;
        }
        if (Arrays.equals(Files.readAllBytes(selfiePath), Files.readAllBytes(signaturePath))) {
            throw new RejectedException("selfie and signature are the same file");
        }
        String account = data.accountNumber();
        Result existing = existing(account);
        if (existing != null) return existing;

        byte[] jpg = JpegWriter.write(renderer.render(data, selfie, signature), cfg.jpegQuality(),
                renderer.layout().dpi(), requestId);
        String sha = AuditLog.sha256(jpg);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("accountNumber", account);
        meta.put("requestId", requestId == null ? null : requestId.toString());
        meta.put("sha256", sha);
        meta.put("templateId", renderer.layout().templateId());
        meta.put("createdAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());

        // Outbox first, then archive: if we stop in between, the next attempt redoes both safely.
        writeAtomically(cfg.outbox().resolve(account + ".jpg"), jpg);
        writeAtomically(cfg.archive().resolve(account + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
        writeAtomically(cfg.archive().resolve(account + ".jpg"), jpg);
        audit.log("GENERATED", account, "input=" + id + (requestId == null ? "" : " request=" + requestId)
                + " template=" + renderer.layout().templateId() + " bytes=" + jpg.length + " sha256=" + sha);
        return new Result(account, requestId == null ? null : requestId.toString(), sha, false);
    }

    public void recordSuspicious(String id, SuspiciousFileException e) {
        audit.log("SUSPICIOUS", null, "input=" + id + " reason=\"" + e.getMessage()
                + "\" action=encrypted+deleted(our copy) quarantine=" + e.quarantinedTo().getFileName());
    }

    /** Preview only (render command): no fingerprint, nothing written. */
    public byte[] renderJpeg(CardData data, BufferedImage selfie, BufferedImage signature)
            throws IOException, RejectedException {
        return JpegWriter.write(renderer.render(data, selfie, signature), cfg.jpegQuality(), renderer.layout().dpi());
    }

    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void moveAside(Path dir, Path parent, String reason) throws IOException {
        Files.createDirectories(parent);
        Path target = parent.resolve(dir.getFileName() + "-" + LocalDateTime.now().format(STAMP));
        Files.move(dir, target);
        if (reason != null) {
            Files.writeString(target.resolve("reason.txt"), reason + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }
}
