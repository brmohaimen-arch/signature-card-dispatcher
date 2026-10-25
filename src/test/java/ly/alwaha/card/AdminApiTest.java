package ly.alwaha.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The dashboard's admin API: login, config + encrypted secrets, archive view/export, host-key pinning. */
class AdminApiTest {

    static final String PASSWORD = "test-password-123";

    @TempDir
    Path tmp;
    Path configFile;
    Service service;
    ApiServer server;
    SshServer sshd;
    Path sftpRoot;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    String cookie;

    @BeforeEach
    void start() throws Exception {
        sftpRoot = Files.createDirectories(tmp.resolve("sftp-root"));
        Files.createDirectories(sftpRoot.resolve("repo"));
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((user, pass, session) -> "t24".equals(user) && "secret".equals(pass));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot));
        sshd.start();

        Path configDir = Files.createDirectories(tmp.resolve("config"));
        configFile = configDir.resolve("application.properties");
        Path template = TestSupport.templateDir(tmp);
        Files.writeString(configFile, String.join("\n",
                "# Signature Card Dispatcher settings (test)",
                "work.dir=" + fwd(tmp.resolve("work")),
                "template.dir=" + fwd(template),
                "font.regular=" + fwd(TestSupport.project().resolve("fonts/Almarai-Regular.ttf")),
                "font.bold=" + fwd(TestSupport.project().resolve("fonts/Almarai-Bold.ttf")),
                "allow.placeholder.template=true",
                "# T24 SFTP",
                "sftp.host=127.0.0.1",
                "sftp.port=" + sshd.getPort(),
                "sftp.user=t24",
                "sftp.remote.dir=repo",
                "sftp.known.hosts=" + fwd(configDir.resolve("known_hosts")),
                "source.selfie.path=",
                "source.signature.path=",
                "server.bind=127.0.0.1",
                "server.port=0",
                ""), StandardCharsets.UTF_8);
        Secrets secrets = new Secrets(configDir.resolve("secrets.enc"),
                Base64.getEncoder().encodeToString(new byte[32]), name -> null);
        service = new Service(Config.load(configFile, secrets), configFile, secrets);
        server = new ApiServer(service, new AdminApi(service, PASSWORD));
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        sshd.stop(true);
    }

    private static String fwd(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    // ---- HTTP helpers -----------------------------------------------------------------------------------------

    private HttpResponse<byte[]> call(String method, String path, String body, boolean csrfHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (csrfHeader) b.header("X-Requested-With", "dashboard");
        if (body != null) b.header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        r.headers().firstValue("Set-Cookie").ifPresent(c -> cookie = c.split(";")[0]);
        return r;
    }

    private HttpResponse<byte[]> call(String method, String path, String body) throws Exception {
        return call(method, path, body, true);
    }

    private JsonNode body(HttpResponse<byte[]> r) throws Exception {
        return json.readTree(r.body());
    }

    private void login() throws Exception {
        assertEquals(200, call("POST", "/admin/login", "{\"password\":\"" + PASSWORD + "\"}").statusCode());
    }

    private HttpResponse<byte[]> putConfig(String settingsJson, String secretsJson) throws Exception {
        return call("PUT", "/admin/api/config", "{\"settings\":" + settingsJson + ",\"secrets\":" + secretsJson + "}");
    }

    // ---- login ---------------------------------------------------------------------------------------------------

    @Test
    void pageIsServedButTheApiNeedsALogin() throws Exception {
        HttpResponse<byte[]> page = call("GET", "/dashboard", null, false);
        assertEquals(200, page.statusCode());
        assertTrue(new String(page.body(), StandardCharsets.UTF_8).contains("Signature Card Dispatcher"));
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(401, call("GET", "/admin/api/overview", null).statusCode());
        assertEquals(401, call("GET", "/admin/api/config", null).statusCode());
    }

    @Test
    void fiveWrongPasswordsLockTheLogin() throws Exception {
        for (int i = 1; i <= 5; i++) {
            HttpResponse<byte[]> r = call("POST", "/admin/login", "{\"password\":\"wrong-" + i + "\"}");
            assertEquals(401, r.statusCode());
        }
        HttpResponse<byte[]> locked = call("POST", "/admin/login", "{\"password\":\"" + PASSWORD + "\"}");
        assertEquals(429, locked.statusCode(), "even the right password is refused while locked");
        assertTrue(Files.readString(service.current().cfg().archive().resolve("dispatch.log")).contains("ADMIN_LOCKED"));
    }

    @Test
    void stateChangesNeedTheDashboardHeader() throws Exception {
        assertEquals(403, call("POST", "/admin/login", "{\"password\":\"" + PASSWORD + "\"}", false).statusCode());
        login();
        assertEquals(403, call("PUT", "/admin/api/config", "{\"settings\":{}}", false).statusCode());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        login();
        assertEquals(200, call("GET", "/admin/api/overview", null).statusCode());
        String old = cookie;
        call("POST", "/admin/logout", null);
        cookie = old;
        assertEquals(401, call("GET", "/admin/api/overview", null).statusCode());
    }

    // ---- configuration -------------------------------------------------------------------------------------------

    @Test
    void savingConfigKeepsCommentsMakesABackupAndAppliesWithoutRestart() throws Exception {
        login();
        HttpResponse<byte[]> r = putConfig("{\"source.sftp.host\":\"127.0.0.1\","
                + "\"source.selfie.path\":\"/onboarding/{requestId}/selfie.jpg\","
                + "\"source.signature.path\":\"/onboarding/{requestId}/signature.png\"}", "{}");
        assertEquals(200, r.statusCode(), new String(r.body()));

        String file = Files.readString(configFile);
        assertTrue(file.contains("source.selfie.path=/onboarding/{requestId}/selfie.jpg"));
        assertTrue(file.contains("# Signature Card Dispatcher settings (test)"), "comments kept");
        try (Stream<Path> s = Files.list(configFile.getParent().resolve("backups"))) {
            assertEquals(1, s.count(), "previous version backed up");
        }
        assertEquals("/onboarding/{requestId}/selfie.jpg", service.current().cfg().source().selfiePath(), "applied live");
        assertTrue(Files.readString(service.current().cfg().archive().resolve("dispatch.log")).contains("CONFIG_CHANGED"));
    }

    @Test
    void invalidConfigIsRefusedAndNothingIsWritten() throws Exception {
        login();
        byte[] before = Files.readAllBytes(configFile);
        assertEquals(400, putConfig("{\"source.selfie.path\":\"/x/{bogus}/selfie.jpg\"}", "{}").statusCode());
        assertEquals(400, putConfig("{\"sftp.port\":\"abc\"}", "{}").statusCode());
        assertEquals(400, putConfig("{\"api.base.url\":\"not a url\"}", "{}").statusCode());
        assertEquals(400, putConfig("{\"work.dir\":\"/elsewhere\"}", "{}").statusCode(), "not editable from the dashboard");
        assertArrayEquals(before, Files.readAllBytes(configFile));
    }

    @Test
    void secretsAreStoredEncryptedAndNeverComeBackOut() throws Exception {
        login();
        String secret = "Sup3r-Secret!";
        assertEquals(200, putConfig("{}", "{\"SFTP_PASSWORD\":\"" + secret + "\"}").statusCode());

        Path enc = configFile.getParent().resolve("secrets.enc");
        assertTrue(Files.exists(enc));
        assertFalse(new String(Files.readAllBytes(enc), StandardCharsets.ISO_8859_1).contains(secret), "not stored in clear");
        HttpResponse<byte[]> cfg = call("GET", "/admin/api/config", null);
        assertFalse(new String(cfg.body(), StandardCharsets.UTF_8).contains(secret), "never returned");
        assertEquals("store", body(cfg).path("secrets").path("SFTP_PASSWORD").asText());
        assertEquals(secret, service.current().cfg().sftpPassword(), "applied live");
        assertFalse(Files.readString(service.current().cfg().archive().resolve("dispatch.log")).contains(secret));

        assertEquals(200, putConfig("{}", "{\"SFTP_PASSWORD\":\"\"}").statusCode());
        assertEquals("none", body(call("GET", "/admin/api/config", null)).path("secrets").path("SFTP_PASSWORD").asText());
    }

    @Test
    void quarantineKeyIsGeneratedOnceAndReplacingNeedsConfirmation() throws Exception {
        login();
        assertFalse(body(call("GET", "/admin/api/overview", null)).path("setup").path("quarantineKey").asBoolean());
        assertEquals(200, call("POST", "/admin/api/quarantine-key", "{}").statusCode());
        assertTrue(body(call("GET", "/admin/api/overview", null)).path("setup").path("quarantineKey").asBoolean());
        assertEquals(409, call("POST", "/admin/api/quarantine-key", "{}").statusCode());
        assertEquals(200, call("POST", "/admin/api/quarantine-key", "{\"replace\":true}").statusCode());
    }

    // ---- archive ------------------------------------------------------------------------------------------------

    @Test
    void archivedCardsCanBeViewedDownloadedAndExportedWithAManifest() throws Exception {
        Path archive = Files.createDirectories(service.current().cfg().archive());
        byte[] card = "fake-jpeg-bytes".getBytes(StandardCharsets.US_ASCII);
        Files.write(archive.resolve(TestSupport.ACCOUNT + ".jpg"), card);
        Files.writeString(archive.resolve(TestSupport.ACCOUNT + ".json"),
                "{\"accountNumber\":\"" + TestSupport.ACCOUNT + "\",\"requestId\":\"r-1\",\"sha256\":\"" + AuditLog.sha256(card) + "\"}");

        assertEquals(401, call("GET", "/admin/api/archive/" + TestSupport.ACCOUNT + ".jpg", null).statusCode(), "login required");
        login();
        JsonNode rows = body(call("GET", "/admin/api/archive", null));
        assertEquals(1, rows.size());
        assertEquals(TestSupport.ACCOUNT, rows.get(0).path("accountNumber").asText());

        HttpResponse<byte[]> img = call("GET", "/admin/api/archive/" + TestSupport.ACCOUNT + ".jpg?download=1", null);
        assertArrayEquals(card, img.body());
        assertTrue(img.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
        assertEquals("no-store", img.headers().firstValue("Cache-Control").orElse(""));

        HttpResponse<byte[]> zip = call("POST", "/admin/api/archive/export", "{\"accounts\":[]}");
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) entries.put(e.getName(), z.readAllBytes());
        }
        assertArrayEquals(card, entries.get(TestSupport.ACCOUNT + ".jpg"));
        String manifest = new String(entries.get("manifest.csv"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains(TestSupport.ACCOUNT + ",r-1," + AuditLog.sha256(card)), manifest);

        String log = Files.readString(service.current().cfg().archive().resolve("dispatch.log"));
        assertTrue(log.contains("CARD_DOWNLOADED") && log.contains("ARCHIVE_EXPORTED"));
        assertEquals(400, call("GET", "/admin/api/archive/..%2Fsecrets.jpg", null).statusCode());
    }

    // ---- host keys + connection check -----------------------------------------------------------------------------

    @Test
    void hostKeyIsFetchedPinnedOnlyIfConfirmedThenTheConnectionWorks() throws Exception {
        login();
        assertEquals(200, putConfig("{}", "{\"SFTP_PASSWORD\":\"secret\"}").statusCode());
        assertFalse(body(call("POST", "/admin/api/check", "{\"target\":\"t24-sftp\"}")).path("ok").asBoolean(), "not pinned yet");

        JsonNode seen = body(call("POST", "/admin/api/hostkey/fetch", "{\"target\":\"t24-sftp\"}"));
        assertTrue(seen.path("ok").asBoolean(), seen.toString());
        String fp = seen.path("fingerprint").asText();
        assertTrue(fp.startsWith("SHA256:"), fp);

        JsonNode wrong = body(call("POST", "/admin/api/hostkey/pin", "{\"target\":\"t24-sftp\",\"fingerprint\":\"SHA256:nope\"}"));
        assertFalse(wrong.path("ok").asBoolean(), "a fingerprint that doesn't match is never pinned");
        assertFalse(Files.exists(configFile.getParent().resolve("known_hosts")));

        JsonNode pinned = body(call("POST", "/admin/api/hostkey/pin", "{\"target\":\"t24-sftp\",\"fingerprint\":\"" + fp + "\"}"));
        assertTrue(pinned.path("ok").asBoolean(), pinned.toString());
        JsonNode check = body(call("POST", "/admin/api/check", "{\"target\":\"t24-sftp\"}"));
        assertTrue(check.path("ok").asBoolean(), check.toString());
    }
}
