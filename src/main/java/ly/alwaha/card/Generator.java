package ly.alwaha.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Config cfg;
    private final CardRenderer renderer;
    private final AuditLog audit;
    private final Quarantine quarantine;

    public Generator(Config cfg, CardRenderer renderer, AuditLog audit, Quarantine quarantine) {
        this.cfg = cfg;
        this.renderer = renderer;
        this.audit = audit;
        this.quarantine = quarantine;
    }

    public int accountDigits() {
        return renderer.layout().accountNumberBoxes().size();
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
     * Checks both images (metadata first), renders the card with the request UUID as its fingerprint (visible
     * digital print + JPEG metadata), and queues it. A suspicious image is encrypted into quarantine and our copy deleted.
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

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        CardRenderer.DigitalPrint print = new CardRenderer.DigitalPrint(requestId, now.atZone(ZoneOffset.UTC));
        byte[] jpg = JpegWriter.write(renderer.render(data, selfie, signature, print), cfg.jpegQuality(),
                renderer.layout().dpi(), requestId);
        String sha = AuditLog.sha256(jpg);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("accountNumber", account);
        meta.put("requestId", requestId.toString());
        meta.put("sha256", sha);
        meta.put("templateId", renderer.layout().templateId());
        meta.put("createdAt", now.toString());

        // Outbox first, then archive: if we stop in between, the next attempt redoes both safely.
        writeAtomically(cfg.outbox().resolve(account + ".jpg"), jpg);
        writeAtomically(cfg.archive().resolve(account + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
        writeAtomically(cfg.archive().resolve(account + ".jpg"), jpg);
        audit.log("GENERATED", account, "request=" + requestId + " template=" + renderer.layout().templateId()
                + " bytes=" + jpg.length + " sha256=" + sha);
        return new Result(account, requestId.toString(), sha, false);
    }

    public void recordSuspicious(String id, SuspiciousFileException e) {
        audit.log("SUSPICIOUS", null, "request=" + id + " reason=\"" + e.getMessage()
                + "\" action=encrypted+deleted(our copy) quarantine=" + e.quarantinedTo().getFileName());
    }

    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
