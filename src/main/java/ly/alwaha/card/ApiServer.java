package ly.alwaha.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The endpoint the onboarding app calls (internal network, no key):
 *
 *   POST /api/v1/signature-cards        {"nationalId","passportNumber","phoneNumber"[,"requestId"]}
 *                                       request UUID in the X-Request-Id header or the requestId field
 *   GET  /api/v1/signature-cards/{uuid} the stored result + live delivery status
 *
 * Idempotent: the same UUID always gets the same stored answer, and a customer whose account already has
 * a card gets that card's result. Temporary failures (503) are not stored, so the app can retry with the same UUID.
 * Responses never contain ID numbers or names.
 */
public final class ApiServer implements AutoCloseable {

    static final String BASE = "/api/v1/signature-cards";
    private static final int MAX_BODY = 8 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Service service;
    private final AdminApi admin;
    private HttpServer server;
    private ExecutorService pool;

    /** admin = null: no dashboard. */
    public ApiServer(Service service, AdminApi admin) {
        this.service = service;
        this.admin = admin;
    }

    public void start() throws IOException {
        Config cfg = service.current().cfg();
        server = HttpServer.create(new InetSocketAddress(cfg.server().bind(), cfg.server().port()), 0);
        server.createContext(BASE, this::handle);
        if (admin != null) admin.register(server);
        pool = Executors.newFixedThreadPool(4);
        server.setExecutor(pool);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals(BASE) || path.equals(BASE + "/")) {
                if (!method.equals("POST")) reply(ex, error(405, "METHOD_NOT_ALLOWED", "use POST"), false);
                else submit(ex);
            } else if (path.startsWith(BASE + "/")) {
                if (!method.equals("GET")) reply(ex, error(405, "METHOD_NOT_ALLOWED", "use GET"), false);
                else status(ex, path.substring(BASE.length() + 1));
            } else {
                reply(ex, error(404, "NOT_FOUND", "unknown path"), false);
            }
        } catch (Exception e) {
            service.current().audit().log("ERROR", null, "api reason=\"" + e + "\"");
            reply(ex, error(500, "ERROR", "internal error"), false);
        } finally {
            ex.close();
        }
    }

    private void submit(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            reply(ex, error(413, "BAD_REQUEST", "body too large"), false);
            return;
        }
        JsonNode json;
        try {
            json = JSON.readTree(body);
        } catch (IOException e) {
            json = null;
        }
        if (json == null || !json.isObject()) {
            reply(ex, error(400, "BAD_REQUEST", "body must be a JSON object"), false);
            return;
        }

        UUID requestId;
        try {
            requestId = requestId(ex.getRequestHeaders().getFirst("X-Request-Id"), text(json, "requestId"));
        } catch (IllegalArgumentException e) {
            reply(ex, error(400, "BAD_REQUEST", e.getMessage()), false);
            return;
        }
        CustomerLookup.Identity identity = new CustomerLookup.Identity(
                text(json, "nationalId"), text(json, "passportNumber"), text(json, "phoneNumber"));

        RequestStore.Entry entry;
        boolean replay = false;
        Service.Components c;
        synchronized (service.lock()) {
            c = service.current();
            Optional<RequestStore.Entry> prior = c.store().get(requestId);
            if (prior.isPresent()) {
                entry = prior.get();
                replay = true;
                c.audit().log("REPLAY", entry.accountNumber(), "request=" + requestId + " status=" + entry.status());
            } else {
                entry = process(c, requestId, identity);
                if (entry.httpStatus() != 503) c.store().put(requestId, entry);
            }
        }
        reply(ex, c.cfg(), entry, replay);
    }

    private static RequestStore.Entry process(Service.Components c, UUID requestId, CustomerLookup.Identity identity)
            throws IOException {
        String id = requestId.toString();
        Config cfg = c.cfg();
        CustomerLookup lookup = c.lookup();
        Generator generator = c.generator();
        SourceImages source = c.source();
        AuditLog audit = c.audit();
        if (!lookup.configured()) return entry(id, 503, "NOT_CONFIGURED", null, "onboarding API not configured yet", null);
        if (c.quarantine() == null) return entry(id, 503, "NOT_CONFIGURED", null, "quarantine key not set yet", null);
        try {
            CardData data = lookup.lookup(identity);
            data.validate(generator.accountDigits());
            String account = data.accountNumber();

            Generator.Result existing = generator.existing(account);
            if (existing != null) {
                audit.log("EXISTING", account, "request=" + id + " original_request=" + existing.requestId());
                return entry(id, 200, "EXISTS", account, "this account already has a card", existing);
            }
            if (!source.configured()) {
                return entry(id, 503, "NOT_CONFIGURED", null, "image source not configured yet", null);
            }

            Path staging = cfg.staging().resolve(id);
            try {
                // Identity values were validated by the lookup (letters/digits only), so they're safe in paths.
                SourceImages.Fetched files = source.fetch(id, Map.of(
                        "requestId", id,
                        "nationalId", identity.nationalId().strip(),
                        "passportNumber", identity.passportNumber().strip(),
                        "phoneNumber", identity.phoneNumber().strip(),
                        "accountNumber", account,
                        "cif", account.substring(0, cfg.api().cifDigits())), staging);
                Generator.Result r = generator.generateCard(id, requestId, data, files.selfie(), files.signature());
                return r.existing()
                        ? entry(id, 200, "EXISTS", account, "this account already has a card", r)
                        : entry(id, 202, "QUEUED", account, null, r);
            } finally {
                deleteOurCopies(staging);
            }
        } catch (SuspiciousFileException e) {
            if (e.file() == null) generator.recordSuspicious(id, e); // remote file: quarantined while streaming
            return entry(id, 422, "SUSPICIOUS", null, e.getMessage(), null);
        } catch (RejectedException e) {
            audit.log("REJECTED", null, "request=" + id + " reason=\"" + e.getMessage() + "\"");
            return entry(id, 422, "REJECTED", null, e.getMessage(), null);
        } catch (IOException e) {
            audit.log("ERROR", null, "request=" + id + " reason=\"" + e.getMessage() + "\"");
            return entry(id, 503, "UNAVAILABLE", null, "temporary problem; retry with the same requestId", null);
        }
    }

    private void status(HttpExchange ex, String idText) throws IOException {
        UUID id;
        try {
            id = canonicalUuid(idText);
        } catch (IllegalArgumentException e) {
            reply(ex, error(400, "BAD_REQUEST", "not a UUID"), false);
            return;
        }
        Service.Components c = service.current();
        Optional<RequestStore.Entry> e = c.store().get(id);
        if (e.isEmpty()) reply(ex, error(404, "NOT_FOUND", "unknown requestId"), false);
        else reply(ex, c.cfg(), e.get(), false);
    }

    /** Header and body may both carry the UUID; if both do, they must match. */
    static UUID requestId(String header, String field) {
        if (header == null && field == null) {
            throw new IllegalArgumentException("requestId is required (X-Request-Id header or requestId field)");
        }
        UUID h = header == null ? null : canonicalUuid(header);
        UUID f = field == null ? null : canonicalUuid(field);
        if (h != null && f != null && !h.equals(f)) {
            throw new IllegalArgumentException("X-Request-Id header and requestId field differ");
        }
        return h != null ? h : f;
    }

    static UUID canonicalUuid(String s) {
        String t = s.strip();
        UUID u;
        try {
            u = UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("requestId is not a valid UUID");
        }
        // UUID.fromString accepts odd forms like "1-1-1-1-1"; only the standard 36-character form is allowed.
        if (!u.toString().equalsIgnoreCase(t)) throw new IllegalArgumentException("requestId is not a valid UUID");
        return u;
    }

    private static String text(JsonNode json, String field) {
        JsonNode n = json.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    static RequestStore.Entry entry(String id, int http, String status, String account, String reason,
                                            Generator.Result r) {
        return new RequestStore.Entry(id, http, status, account, reason,
                r == null ? null : r.sha256(),
                r != null && r.existing() ? r.requestId() : null,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    private static RequestStore.Entry error(int http, String status, String reason) {
        return new RequestStore.Entry(null, http, status, null, reason, null, null, null);
    }

    private void reply(HttpExchange ex, RequestStore.Entry e, boolean replay) throws IOException {
        reply(ex, null, e, replay);
    }

    private static void reply(HttpExchange ex, Config cfg, RequestStore.Entry e, boolean replay) throws IOException {
        ObjectNode out = JSON.valueToTree(e);
        out.remove("httpStatus");
        if (cfg != null && e.accountNumber() != null) out.put("delivery", Dispatcher.deliveryStatus(cfg, e.accountNumber()));
        if (replay) ex.getResponseHeaders().add("Idempotent-Replay", "true");
        byte[] bytes = JSON.writeValueAsBytes(out);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(e.httpStatus(), bytes.length);
        ex.getResponseBody().write(bytes);
    }

    /** Our staging copies only; nothing on the onboarding server is ever deleted. */
    private static void deleteOurCopies(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }
}
