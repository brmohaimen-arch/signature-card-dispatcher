package ly.alwaha.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The admin dashboard: GET /dashboard (the page) and /admin/... (its JSON API).
 * Login with the DASHBOARD_PASSWORD environment variable. Session cookie: HttpOnly, SameSite=Strict, 30 min idle.
 * 5 wrong passwords from one address lock it out for 5 minutes. Every state-changing call must carry
 * X-Requested-With: dashboard (other sites can't add that header), so links and forms elsewhere can't act here.
 * Every login, config change, card view, download and export goes to the audit log, never with values or PII.
 */
public final class AdminApi {

    /** One editable setting in the Configuration tab. */
    public record Field(String key, String group, String label, boolean restart) {}

    public static final List<Field> FIELDS = List.of(
            new Field("api.base.url", "Onboarding API (account + CIF lookup)", "Base URL (e.g. http://host:port)", false),
            new Field("api.account.path", "Onboarding API (account + CIF lookup)", "Account details path", false),
            new Field("api.account.number.pointer", "Onboarding API (account + CIF lookup)", "Account number field (JSON pointer)", false),
            new Field("api.name.pointer", "Onboarding API (account + CIF lookup)", "Customer name field (JSON pointer; Arabic part is used)", false),
            new Field("api.branch.pointer", "Onboarding API (account + CIF lookup)", "Branch code field (JSON pointer)", false),
            new Field("api.cif.enabled", "Onboarding API (account + CIF lookup)", "Use get CIF for name + branch (true / false)", false),
            new Field("api.cif.path", "Onboarding API (account + CIF lookup)", "Get CIF path ({cif} = first 7 digits), only if enabled", false),
            new Field("api.cif.digits", "Onboarding API (account + CIF lookup)", "CIF digits", false),
            new Field("api.auth.header", "Onboarding API (account + CIF lookup)", "Auth header name (empty = none)", false),
            new Field("api.timeout.seconds", "Onboarding API (account + CIF lookup)", "Timeout (seconds)", false),
            new Field("sftp.host", "T24 SFTP", "Host / IP", false),
            new Field("sftp.port", "T24 SFTP", "Port", false),
            new Field("sftp.user", "T24 SFTP", "User", false),
            new Field("sftp.remote.dir", "T24 SFTP", "Document repository path", false),
            new Field("sftp.known.hosts", "T24 SFTP", "Pinned host key file", false),
            new Field("sftp.key.path", "T24 SFTP", "Private key file (empty = password)", false),
            new Field("sftp.timeout.seconds", "T24 SFTP", "Timeout (seconds)", false),
            new Field("source.sftp.host", "Onboarding SFTP (images, read only)", "Host / IP", false),
            new Field("source.sftp.port", "Onboarding SFTP (images, read only)", "Port", false),
            new Field("source.sftp.user", "Onboarding SFTP (images, read only)", "User", false),
            new Field("source.sftp.known.hosts", "Onboarding SFTP (images, read only)", "Pinned host key file", false),
            new Field("source.sftp.key.path", "Onboarding SFTP (images, read only)", "Private key file (empty = password)", false),
            new Field("source.sftp.timeout.seconds", "Onboarding SFTP (images, read only)", "Timeout (seconds)", false),
            new Field("source.selfie.path", "Onboarding SFTP (images, read only)", "Selfie path, e.g. /onboarding/{requestId}/selfie.jpg", false),
            new Field("source.signature.path", "Onboarding SFTP (images, read only)", "Signature path, e.g. /onboarding/{requestId}/signature.jpg", false),
            new Field("poll.seconds", "Processing", "Delivery cycle (seconds)", false),
            new Field("dispatch.max.attempts", "Processing", "Delivery attempts before giving up", false),
            new Field("input.stable.seconds", "Processing", "Inbox folder settle time (seconds)", false),
            new Field("jpeg.quality", "Processing", "JPEG quality (0.5 - 1.0)", false),
            new Field("image.max.bytes", "File safety", "Max file size (bytes)", false),
            new Field("image.max.side", "File safety", "Max image side (pixels)", false),
            new Field("image.max.pixels", "File safety", "Max image pixels", false),
            new Field("server.bind", "Server (restart needed)", "Listen address", true),
            new Field("server.port", "Server (restart needed)", "Listen port", true));

    private static final String COOKIE = "scd_session";
    private static final Duration IDLE = Duration.ofMinutes(30);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(5);
    private static final int MAX_BODY = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Failures(int count, Instant lockedUntil) {}

    private final Service service;
    private final byte[] passwordHash;
    private final Secrets secrets;
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AdminApi(Service service, String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalStateException("DASHBOARD_PASSWORD must be set (at least 8 characters)");
        }
        this.service = service;
        this.passwordHash = sha256(password);
        this.secrets = service.secrets();
    }

    void register(HttpServer server) {
        server.createContext("/dashboard", this::page);
        server.createContext("/admin", this::api);
    }

    // ---- page ---------------------------------------------------------------------------------------------------

    private void page(HttpExchange ex) throws IOException {
        try (InputStream in = AdminApi.class.getResourceAsStream("/dashboard/index.html")) {
            if (in == null) {
                send(ex, 500, "text/plain", "dashboard page missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            securityHeaders(ex);
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        } finally {
            ex.close();
        }
    }

    // ---- API ----------------------------------------------------------------------------------------------------

    private void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            securityHeaders(ex);
            if (!method.equals("GET") && !"dashboard".equals(ex.getRequestHeaders().getFirst("X-Requested-With"))) {
                json(ex, 403, msg("missing X-Requested-With header"));
                return;
            }
            if (path.equals("/admin/login") && method.equals("POST")) {
                login(ex);
                return;
            }
            String token = session(ex);
            if (token == null) {
                json(ex, 401, msg("login required"));
                return;
            }
            if (path.equals("/admin/logout") && method.equals("POST")) {
                sessions.remove(token);
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict");
                json(ex, 200, msg("logged out"));
                return;
            }
            route(ex, path.substring("/admin".length()), method);
        } catch (IllegalArgumentException e) {
            json(ex, 400, msg(e.getMessage()));
        } catch (Exception e) {
            service.current().audit().log("ERROR", null, "admin reason=\"" + e + "\"");
            json(ex, 500, msg("internal error: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex, String p, String m) throws Exception {
        if (p.equals("/api/overview") && m.equals("GET")) overview(ex);
        else if (p.equals("/api/requests") && m.equals("GET")) requests(ex);
        else if (p.startsWith("/api/requests/") && m.equals("GET")) request(ex, p.substring("/api/requests/".length()));
        else if (p.equals("/api/archive") && m.equals("GET")) archive(ex);
        else if (p.startsWith("/api/archive/") && p.endsWith(".jpg") && m.equals("GET")) card(ex, p.substring("/api/archive/".length(), p.length() - 4));
        else if (p.equals("/api/archive/export") && m.equals("POST")) export(ex);
        else if (p.equals("/api/config") && m.equals("GET")) config(ex);
        else if (p.equals("/api/config") && m.equals("PUT")) saveConfig(ex);
        else if (p.equals("/api/quarantine-key") && m.equals("POST")) quarantineKey(ex);
        else if (p.equals("/api/endpoint") && m.equals("POST")) endpointSwitch(ex);
        else if (p.equals("/api/branches") && m.equals("GET")) branches(ex);
        else if (p.equals("/api/branches") && m.equals("PUT")) saveBranches(ex);
        else if (p.equals("/api/check") && m.equals("POST")) check(ex);
        else if (p.equals("/api/hostkey/fetch") && m.equals("POST")) hostKey(ex, false);
        else if (p.equals("/api/hostkey/pin") && m.equals("POST")) hostKey(ex, true);
        else json(ex, 404, msg("unknown admin path"));
    }

    // ---- login / sessions ---------------------------------------------------------------------------------------

    private void login(HttpExchange ex) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        AuditLog audit = service.current().audit();
        Failures f = failures.get(ip);
        if (f != null && f.lockedUntil() != null && Instant.now().isBefore(f.lockedUntil())) {
            json(ex, 429, msg("too many wrong passwords; try again after " + f.lockedUntil()));
            return;
        }
        JsonNode body = body(ex);
        String given = body.path("password").asText("");
        if (!MessageDigest.isEqual(sha256(given), passwordHash)) {
            int n = (f == null || f.lockedUntil() != null ? 0 : f.count()) + 1;
            boolean lock = n >= MAX_FAILURES;
            failures.put(ip, new Failures(lock ? 0 : n, lock ? Instant.now().plus(LOCKOUT) : null));
            audit.log(lock ? "ADMIN_LOCKED" : "ADMIN_LOGIN_FAILED", null, "ip=" + ip + " attempt=" + n);
            json(ex, 401, msg(lock ? "too many wrong passwords; locked for 5 minutes" : "wrong password"));
            return;
        }
        failures.remove(ip);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, Instant.now());
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        audit.log("ADMIN_LOGIN", null, "ip=" + ip);
        json(ex, 200, msg("ok"));
    }

    private String session(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String c = part.strip();
            if (c.startsWith(COOKIE + "=")) {
                String token = c.substring(COOKIE.length() + 1);
                Instant last = sessions.get(token);
                if (last == null) return null;
                if (last.plus(IDLE).isBefore(Instant.now())) {
                    sessions.remove(token);
                    return null;
                }
                sessions.put(token, Instant.now());
                return token;
            }
        }
        return null;
    }

    // ---- overview / requests / archive ----------------------------------------------------------------------------

    private void overview(HttpExchange ex) throws IOException {
        Service.Components c = service.current();
        Config cfg = c.cfg();
        ObjectNode out = JSON.createObjectNode();
        out.put("templateId", c.renderer().layout().templateId());
        out.put("endpointEnabled", cfg.server().enabled());
        ObjectNode setup = out.putObject("setup");
        setup.put("onboardingApi", c.lookup().configured());
        setup.put("t24Sftp", !cfg.sftpHost().isEmpty());
        setup.put("t24HostKey", Files.isRegularFile(cfg.sftpKnownHosts()));
        setup.put("sourceSftp", cfg.source().configured());
        setup.put("sourceHostKey", Files.isRegularFile(cfg.source().sftp().knownHosts()));
        setup.put("quarantineKey", c.quarantine() != null);
        setup.put("branchTable", !cfg.api().branches().isEmpty());
        setup.put("secretsStore", secrets.storeAvailable());
        ObjectNode counts = out.putObject("counts");
        counts.put("queued", count(cfg.outbox(), ".jpg"));
        counts.put("archived", count(cfg.archive(), ".jpg"));
        counts.put("failedDeliveries", count(cfg.failed(), ".jpg"));
        counts.put("quarantined", count(cfg.quarantine(), ".enc"));
        ObjectNode today = out.putObject("requestsToday");
        Instant midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        for (Path f : list(cfg.requests(), ".json")) {
            if (Files.getLastModifiedTime(f).toInstant().isBefore(midnight)) continue;
            String status = JSON.readTree(f.toFile()).path("status").asText("?");
            today.put(status, today.path(status).asInt(0) + 1);
        }
        ArrayNode events = out.putArray("events");
        for (String line : tail(cfg.archive().resolve("dispatch.log"), 64 * 1024, 80)) events.add(line);
        json(ex, 200, out);
    }

    private void requests(HttpExchange ex) throws IOException {
        Config cfg = service.current().cfg();
        ArrayNode out = JSON.createArrayNode();
        for (Path f : newest(list(cfg.requests(), ".json"), 200)) {
            ObjectNode e = (ObjectNode) JSON.readTree(f.toFile());
            if (e.hasNonNull("accountNumber")) e.put("delivery", Dispatcher.deliveryStatus(cfg, e.get("accountNumber").asText()));
            out.add(e);
        }
        json(ex, 200, out);
    }

    private void request(HttpExchange ex, String idText) throws IOException {
        Config cfg = service.current().cfg();
        String id = ApiServer.canonicalUuid(idText).toString();
        Path f = cfg.requests().resolve(id + ".json");
        if (!Files.exists(f)) {
            json(ex, 404, msg("unknown requestId"));
            return;
        }
        ObjectNode e = (ObjectNode) JSON.readTree(f.toFile());
        if (e.hasNonNull("accountNumber")) e.put("delivery", Dispatcher.deliveryStatus(cfg, e.get("accountNumber").asText()));
        json(ex, 200, e);
    }

    /** Every archived card: one row per account, newest first. ?q= filters by account or request UUID. */
    private void archive(HttpExchange ex) throws IOException {
        Config cfg = service.current().cfg();
        String q = query(ex, "q");
        ArrayNode out = JSON.createArrayNode();
        for (Path card : newest(list(cfg.archive(), ".jpg"), Integer.MAX_VALUE)) {
            String account = card.getFileName().toString().replace(".jpg", "");
            Path metaFile = cfg.archive().resolve(account + ".json");
            ObjectNode row = Files.exists(metaFile) ? (ObjectNode) JSON.readTree(metaFile.toFile()) : JSON.createObjectNode();
            row.put("accountNumber", account);
            row.put("bytes", Files.size(card));
            if (!row.hasNonNull("createdAt")) row.put("createdAt", Files.getLastModifiedTime(card).toInstant().toString());
            if (q != null && !q.isBlank() && !account.contains(q.strip())
                    && !row.path("requestId").asText("").contains(q.strip())) continue;
            row.put("delivery", Dispatcher.deliveryStatus(cfg, account));
            out.add(row);
            if (out.size() >= 500) break;
        }
        json(ex, 200, out);
    }

    private void card(HttpExchange ex, String account) throws IOException {
        if (!account.matches("\\d{1,30}")) throw new IllegalArgumentException("bad account number");
        Service.Components c = service.current();
        Path card = c.cfg().archive().resolve(account + ".jpg");
        if (!Files.exists(card)) {
            json(ex, 404, msg("no archived card for this account"));
            return;
        }
        boolean download = "1".equals(query(ex, "download"));
        if (download) ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + account + ".jpg\"");
        c.audit().log(download ? "CARD_DOWNLOADED" : "CARD_VIEWED", account, "by=admin");
        send(ex, 200, "image/jpeg", Files.readAllBytes(card));
    }

    /** ZIP of the chosen cards (all if none chosen) + manifest.csv with each card's SHA-256, checked on the way out. */
    private void export(HttpExchange ex) throws IOException {
        Service.Components c = service.current();
        Config cfg = c.cfg();
        JsonNode body = body(ex);
        Set<String> wanted = new LinkedHashSet<>();
        body.path("accounts").forEach(n -> {
            if (!n.asText().matches("\\d{1,30}")) throw new IllegalArgumentException("bad account number");
            wanted.add(n.asText());
        });
        List<Path> cards = new ArrayList<>();
        for (Path card : newest(list(cfg.archive(), ".jpg"), Integer.MAX_VALUE)) {
            String account = card.getFileName().toString().replace(".jpg", "");
            if (wanted.isEmpty() || wanted.contains(account)) cards.add(card);
        }
        // Audit before any customer data leaves the server.
        c.audit().log("ARCHIVE_EXPORTED", null, "by=admin cards=" + cards.size());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        ex.getResponseHeaders().add("Content-Type", "application/zip");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"signature-cards-" + stamp + ".zip\"");
        ex.sendResponseHeaders(200, 0);
        StringBuilder manifest = new StringBuilder("accountNumber,requestId,sha256,templateId,createdAt\n");
        try (OutputStream os = ex.getResponseBody(); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Path card : cards) {
                String account = card.getFileName().toString().replace(".jpg", "");
                byte[] bytes = Files.readAllBytes(card);
                Path metaFile = cfg.archive().resolve(account + ".json");
                JsonNode meta = Files.exists(metaFile) ? JSON.readTree(metaFile.toFile()) : JSON.createObjectNode();
                zip.putNextEntry(new ZipEntry(account + ".jpg"));
                zip.write(bytes);
                zip.closeEntry();
                manifest.append(account).append(',')
                        .append(meta.path("requestId").asText("")).append(',')
                        .append(AuditLog.sha256(bytes)).append(',')
                        .append(meta.path("templateId").asText("")).append(',')
                        .append(meta.path("createdAt").asText("")).append('\n');
            }
            zip.putNextEntry(new ZipEntry("manifest.csv"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    // ---- configuration ----------------------------------------------------------------------------------------------

    private void config(HttpExchange ex) throws IOException {
        Properties p = PropertiesFile.read(service.configFile());
        ObjectNode out = JSON.createObjectNode();
        ArrayNode fields = out.putArray("fields");
        for (Field f : FIELDS) {
            ObjectNode n = fields.addObject();
            n.put("key", f.key()).put("group", f.group()).put("label", f.label()).put("restart", f.restart());
            n.put("value", p.getProperty(f.key(), Config.DEFAULTS.getOrDefault(f.key(), ""))); // value in effect
        }
        ObjectNode s = out.putObject("secrets");
        for (String name : Secrets.NAMES) s.put(name, secrets.source(name)); // "env", "store" or "none", never the value
        out.put("secretsStore", secrets.storeAvailable());
        out.put("quarantineFiles", count(service.current().cfg().quarantine(), ".enc"));
        json(ex, 200, out);
    }

    private void saveConfig(HttpExchange ex) throws IOException {
        JsonNode body = body(ex);
        Map<String, String> settings = new TreeMap<>();
        Set<String> allowed = new java.util.HashSet<>();
        for (Field f : FIELDS) allowed.add(f.key());
        body.path("settings").fields().forEachRemaining(e -> {
            if (!allowed.contains(e.getKey())) throw new IllegalArgumentException("setting " + e.getKey() + " can't be changed here");
            settings.put(e.getKey(), e.getValue().asText("").strip());
        });
        Map<String, String> newSecrets = new LinkedHashMap<>();
        body.path("secrets").fields().forEachRemaining(e -> {
            if (!Secrets.NAMES.contains(e.getKey()) || e.getKey().equals("QUARANTINE_KEY")) {
                throw new IllegalArgumentException("secret " + e.getKey() + " can't be set here");
            }
            newSecrets.put(e.getKey(), e.getValue().asText(""));
        });
        if (!newSecrets.isEmpty() && !secrets.storeAvailable()) {
            json(ex, 409, msg("SECRETS_MASTER_KEY is not set on the server, so passwords can't be stored"));
            return;
        }

        // Only changed values are written; validate the result before touching anything.
        Properties current = PropertiesFile.read(service.configFile());
        settings.entrySet().removeIf(e -> e.getValue().equals(current.getProperty(e.getKey(), Config.DEFAULTS.getOrDefault(e.getKey(), ""))));
        String problem = validate(settings);
        if (problem != null) {
            json(ex, 400, msg(problem));
            return;
        }
        Path backup = null;
        if (!settings.isEmpty()) backup = PropertiesFile.update(service.configFile(), settings);
        if (!newSecrets.isEmpty()) secrets.put(newSecrets);
        service.reload();

        AuditLog audit = service.current().audit();
        if (!settings.isEmpty()) audit.log("CONFIG_CHANGED", null, "by=admin keys=" + String.join(",", settings.keySet())
                + " backup=" + backup.getFileName());
        if (!newSecrets.isEmpty()) audit.log("SECRETS_CHANGED", null, "by=admin names=" + String.join(",", newSecrets.keySet()));
        boolean restart = settings.containsKey("server.bind") || settings.containsKey("server.port");
        ObjectNode out = JSON.createObjectNode();
        out.put("message", settings.isEmpty() && newSecrets.isEmpty() ? "nothing changed"
                : "saved and applied" + (restart ? "; restart the service for the new address/port" : ""));
        out.put("changed", settings.size() + newSecrets.size());
        if (backup != null) out.put("backup", backup.getFileName().toString());
        json(ex, 200, out);
    }

    /** Builds the whole service from the would-be config in a temp copy; returns a message if anything is wrong. */
    private String validate(Map<String, String> settings) {
        try {
            for (Map.Entry<String, String> e : settings.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                if (k.endsWith(".port") && !v.isEmpty() && !v.matches("\\d{1,5}")) return k + " must be a port number";
                if (k.equals("api.base.url") && !v.isEmpty() && !v.matches("https?://[^\\s/]+(:\\d+)?/?")) {
                    return "api.base.url must look like http://host:port";
                }
            }
            Path cfgFile = service.configFile();
            Path tmp = Files.createTempFile(cfgFile.toAbsolutePath().getParent(), ".validate-", ".properties");
            try {
                Files.write(tmp, PropertiesFile.apply(Files.readAllLines(cfgFile, StandardCharsets.UTF_8), settings),
                        StandardCharsets.UTF_8);
                Config c = Config.load(tmp, secrets);
                Map<String, String> sample = new HashMap<>();
                for (String v : List.of("requestId", "nationalId", "passportNumber", "phoneNumber", "accountNumber", "cif")) {
                    sample.put(v, "x");
                }
                SourceImages.expand(c.source().selfiePath(), sample);
                SourceImages.expand(c.source().signaturePath(), sample);
                if (c.jpegQuality() < 0.5f || c.jpegQuality() > 1f) return "jpeg.quality must be between 0.5 and 1.0";
                if (c.pollSeconds() < 1 || c.maxAttempts() < 1) return "poll.seconds and dispatch.max.attempts must be at least 1";
                Service.build(c);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return null;
        } catch (NumberFormatException e) {
            return "a number setting has a value that isn't a number: " + e.getMessage();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private void quarantineKey(HttpExchange ex) throws IOException {
        if (!secrets.storeAvailable()) {
            json(ex, 409, msg("SECRETS_MASTER_KEY is not set on the server"));
            return;
        }
        String source = secrets.source("QUARANTINE_KEY");
        if (source.equals("env")) {
            json(ex, 409, msg("the quarantine key is set by an environment variable on the server"));
            return;
        }
        long files = count(service.current().cfg().quarantine(), ".enc");
        if (source.equals("store") && !body(ex).path("replace").asBoolean(false)) {
            json(ex, 409, msg("a quarantine key already exists; replacing it means the " + files
                    + " file(s) already in quarantine can only be opened with the old key"));
            return;
        }
        byte[] k = new byte[32];
        random.nextBytes(k);
        secrets.put(Map.of("QUARANTINE_KEY", Base64.getEncoder().encodeToString(k)));
        service.reload();
        service.current().audit().log("SECRETS_CHANGED", null, "by=admin names=QUARANTINE_KEY action=generated");
        json(ex, 200, msg("quarantine key generated and applied"));
    }

    // ---- endpoint switch and branch table -------------------------------------------------------------------------

    /** Turns OUR endpoint on or off (saved in application.properties, so it survives a restart). */
    private void endpointSwitch(HttpExchange ex) throws IOException {
        JsonNode v = body(ex).path("enabled");
        if (!v.isBoolean()) throw new IllegalArgumentException("enabled must be true or false");
        PropertiesFile.update(service.configFile(), Map.of("endpoint.enabled", Boolean.toString(v.asBoolean())));
        service.reload();
        service.current().audit().log(v.asBoolean() ? "ENDPOINT_ENABLED" : "ENDPOINT_DISABLED", null, "by=admin");
        json(ex, 200, msg(v.asBoolean() ? "endpoint enabled: requests are accepted"
                : "endpoint disabled: requests get 503 DISABLED; queued cards are still delivered"));
    }

    private Path branchesFile() throws IOException {
        Properties p = PropertiesFile.read(service.configFile());
        return Path.of(p.getProperty("branches.file", Config.DEFAULTS.get("branches.file")).strip());
    }

    private void branches(HttpExchange ex) throws IOException {
        ObjectNode out = JSON.createObjectNode();
        ObjectNode table = out.putObject("branches");
        service.current().cfg().api().branches().forEach(table::put);
        out.put("file", branchesFile().toString());
        json(ex, 200, out);
    }

    /** Replaces the whole branch-code → branch-name table (previous file backed up). */
    private void saveBranches(HttpExchange ex) throws IOException {
        Map<String, String> table = new TreeMap<>();
        body(ex).path("branches").fields().forEachRemaining(e -> {
            String code = e.getKey().strip(), name = e.getValue().asText("").strip();
            if (!code.matches("[A-Za-z0-9_-]{1,30}")) throw new IllegalArgumentException("bad branch code: " + code);
            if (name.isEmpty() || name.contains("\n") || name.contains("\r") || name.length() > 100) {
                throw new IllegalArgumentException("branch " + code + " needs a name (one line, up to 100 characters)");
            }
            table.put(code, name);
        });
        Path file = branchesFile();
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        if (Files.exists(file)) {
            Path backups = Files.createDirectories(parent.resolve("backups"));
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Files.copy(file, backups.resolve("branches-" + stamp + ".properties"));
        }
        Properties out = new Properties();
        out.putAll(table);
        java.io.StringWriter w = new java.io.StringWriter();
        out.store(w, "Branch code = branch name (edited from the dashboard)");
        Generator.writeAtomically(file, w.toString().getBytes(StandardCharsets.UTF_8));
        service.reload();
        service.current().audit().log("BRANCHES_CHANGED", null, "by=admin count=" + table.size());
        json(ex, 200, msg("branch table saved (" + table.size() + " branches) and applied"));
    }

    // ---- connection checks and host keys -----------------------------------------------------------------------------

    private void check(HttpExchange ex) throws IOException {
        Config cfg = service.current().cfg();
        String target = body(ex).path("target").asText();
        ObjectNode out = JSON.createObjectNode();
        try {
            switch (target) {
                case "onboarding-api" -> {
                    if (cfg.api().baseUrl().isEmpty()) throw new IOException("api.base.url is not set");
                    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(cfg.api().timeoutSeconds())).build();
                    HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(cfg.api().baseUrl()))
                            .timeout(Duration.ofSeconds(cfg.api().timeoutSeconds())).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    out.put("message", "reachable (HTTP " + r.statusCode() + " on the base URL)");
                }
                case "t24-sftp" -> {
                    try (SftpConnection s = SftpConnection.connect(cfg.t24Sftp())) {
                        out.put("message", "logged in; repository folder " + s.describeRemoteDir() + " (nothing uploaded)");
                    }
                }
                case "source-sftp" -> {
                    try (SftpConnection s = SftpConnection.connect(cfg.source().sftp())) {
                        out.put("message", "logged in to the onboarding server (nothing read, written or deleted)");
                    }
                }
                default -> throw new IllegalArgumentException("unknown target " + target);
            }
            out.put("ok", true);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            out.put("ok", false).put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        json(ex, 200, out);
    }

    private void hostKey(HttpExchange ex, boolean pin) throws IOException {
        Config cfg = service.current().cfg();
        JsonNode body = body(ex);
        String target = body.path("target").asText();
        Config.SftpEndpoint e = switch (target) {
            case "t24-sftp" -> cfg.t24Sftp();
            case "source-sftp" -> cfg.source().sftp();
            default -> throw new IllegalArgumentException("unknown target " + target);
        };
        ObjectNode out = JSON.createObjectNode();
        try {
            HostKeys.Seen seen = pin
                    ? HostKeys.pin(e.host(), e.port(), e.timeoutSeconds(), body.path("fingerprint").asText(), e.knownHosts())
                    : HostKeys.fetch(e.host(), e.port(), e.timeoutSeconds());
            out.put("ok", true).put("fingerprint", seen.fingerprint()).put("host", e.host() + ":" + e.port());
            if (pin) {
                out.put("message", "pinned in " + e.knownHosts());
                service.current().audit().log("HOSTKEY_PINNED", null, "by=admin target=" + target + " fingerprint=" + seen.fingerprint());
            }
        } catch (IOException ioe) {
            out.put("ok", false).put("message", ioe.getMessage());
        }
        json(ex, 200, out);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.add("Cache-Control", "no-store");
        h.add("X-Frame-Options", "DENY");
        h.add("X-Content-Type-Options", "nosniff");
        h.add("Referrer-Policy", "no-referrer");
        h.add("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' blob:; frame-ancestors 'none'");
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        byte[] b;
        try (InputStream in = ex.getRequestBody()) {
            b = in.readNBytes(MAX_BODY + 1);
        }
        if (b.length > MAX_BODY) throw new IllegalArgumentException("body too large");
        if (b.length == 0) return JSON.createObjectNode();
        try {
            return JSON.readTree(b);
        } catch (IOException e) {
            throw new IllegalArgumentException("body must be JSON");
        }
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static ObjectNode msg(String message) {
        return JSON.createObjectNode().put("message", message);
    }

    private static void json(HttpExchange ex, int status, JsonNode body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
    }

    private static List<Path> list(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix) && !p.getFileName().toString().startsWith("."))
                    .toList();
        }
    }

    private static long count(Path dir, String suffix) throws IOException {
        return list(dir, suffix).size();
    }

    private static List<Path> newest(List<Path> files, int limit) {
        return files.stream()
                .sorted(Comparator.comparing((Path p) -> {
                    try {
                        return Files.getLastModifiedTime(p);
                    } catch (IOException e) {
                        return FileTime.fromMillis(0);
                    }
                }).reversed())
                .limit(limit)
                .toList();
    }

    /** The last lines of a file, reading at most maxBytes from its end (the log can grow large). */
    private static List<String> tail(Path file, int maxBytes, int maxLines) throws IOException {
        if (!Files.exists(file)) return List.of();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long start = Math.max(0, raf.length() - maxBytes);
            raf.seek(start);
            byte[] b = new byte[(int) (raf.length() - start)];
            raf.readFully(b);
            List<String> lines = new ArrayList<>(List.of(new String(b, StandardCharsets.UTF_8).split("\\R")));
            if (start > 0 && !lines.isEmpty()) lines.remove(0); // first line may be cut
            List<String> last = lines.subList(Math.max(0, lines.size() - maxLines), lines.size());
            List<String> out = new ArrayList<>(last);
            java.util.Collections.reverse(out); // newest first
            return out;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
