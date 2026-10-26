package ly.alwaha.card;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The endpoint the onboarding app calls. One embedded SFTP server plays both the onboarding server
 * (images, read only) and T24's repository; a stub HTTP server plays the onboarding API.
 */
class ApiServerTest {

    @TempDir
    Path tmp;
    SshServer sshd;
    StubOnboardingApi onboardingApi;
    ApiServer api;
    Config cfg;
    Path sftpRoot;
    Path t24Repo;
    Path sourceDir;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        sftpRoot = Files.createDirectories(tmp.resolve("sftp-root"));
        t24Repo = Files.createDirectories(sftpRoot.resolve("repo"));
        // The app's real layout: OCR_Uploads/{branch3}/{nationalId}_{accountNumber}_{traceNumber}/*.bmp
        sourceDir = customerFolder("002", TestSupport.NID + "_" + TestSupport.ACCOUNT + "_c07a5154");

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((user, pass, session) -> "t24".equals(user) && "secret".equals(pass));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot));
        sshd.start();
        KeyPair hostKey = sshd.getKeyPairProvider().loadKeys(null).iterator().next();
        Path knownHosts = tmp.resolve("known_hosts");
        Files.writeString(knownHosts, "[127.0.0.1]:" + sshd.getPort() + " " + PublicKeyEntry.toString(hostKey.getPublic()) + "\n");

        onboardingApi = new StubOnboardingApi();
        onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));

        Config base = TestSupport.config(tmp, TestSupport.templateDir(tmp), "127.0.0.1", sshd.getPort(), knownHosts, "repo", onboardingApi.config());
        Config.Source source = new Config.Source(
                new Config.SftpEndpoint("127.0.0.1", sshd.getPort(), "t24", "", knownHosts, null, "secret", null, 10),
                "/OCR_Uploads", Config.DEFAULTS.get("source.folder.pattern"), "Profile", "SignatureCard", null);
        cfg = TestSupport.withSource(base, source);
        startApi(cfg);
    }

    private void startApi(Config c) throws Exception {
        if (api != null) api.close();
        api = new ApiServer(new Service(c, null, null), null);
        api.start();
    }

    @AfterEach
    void stop() throws Exception {
        api.close();
        sshd.stop(true);
        onboardingApi.close();
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private HttpResponse<String> post(String body, String headerId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + ApiServer.BASE))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headerId != null) b.header("X-Request-Id", headerId);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> submit(String uuid) throws Exception {
        return post(identityJson(TestSupport.NID, null), uuid);
    }

    private HttpResponse<String> get(String uuid) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + ApiServer.BASE + "/" + uuid))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String identityJson(String nid, String requestId) {
        return "{\"nationalId\":\"" + nid + "\",\"passportNumber\":\"" + TestSupport.PASSPORT
                + "\",\"phoneNumber\":\"" + TestSupport.PHONE + "\""
                + (requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"") + "}";
    }

    private JsonNode body(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body());
    }

    private void dispatch() throws Exception {
        new Dispatcher(cfg, new AuditLog(cfg.archive().resolve("dispatch.log"))).dispatchOutbox();
    }

    private static long count(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    /** A customer folder like the app writes: Profile.bmp, SignatureCard.bmp and a Passport.bmp we must ignore. */
    private Path customerFolder(String branch3, String name) throws Exception {
        Path dir = Files.createDirectories(sftpRoot.resolve("OCR_Uploads").resolve(branch3).resolve(name));
        ImageIO.write(TestSupport.selfie(), "bmp", dir.resolve("Profile.bmp").toFile());
        ImageIO.write(TestSupport.signature(), "bmp", dir.resolve("SignatureCard.bmp").toFile());
        Files.writeString(dir.resolve("Passport.bmp"), "not used");
        return dir;
    }


    // ---- the happy path ---------------------------------------------------------------------------------------

    @Test
    void queuesCardWithUuidFingerprintAndDeliversIt() throws Exception {
        String uuid = UUID.randomUUID().toString();
        HttpResponse<String> r = submit(uuid);

        assertEquals(202, r.statusCode(), r.body());
        assertEquals("QUEUED", body(r).get("status").asText());
        assertEquals(TestSupport.ACCOUNT, body(r).get("accountNumber").asText());
        assertEquals(uuid, body(r).get("requestId").asText());
        assertEquals("PENDING", body(r).get("delivery").asText());

        dispatch();
        Path delivered = t24Repo.resolve(TestSupport.ACCOUNT + ".jpg");
        assertTrue(Files.exists(delivered), "card delivered to the T24 path");
        // The request UUID is the card's digital fingerprint (EXIF ImageUniqueID, 32 hex chars).
        ExifSubIFDDirectory exif = ImageMetadataReader.readMetadata(delivered.toFile())
                .getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertEquals(uuid.replace("-", ""), exif.getString(ExifSubIFDDirectory.TAG_IMAGE_UNIQUE_ID));
        assertNotNull(ImageIO.read(delivered.toFile()), "still a valid JPEG");

        assertEquals("DELIVERED", body(get(uuid)).get("delivery").asText());
        assertTrue(Files.exists(sourceDir.resolve("Profile.bmp")) && Files.exists(sourceDir.resolve("SignatureCard.bmp")),
                "files on the onboarding server are never deleted");
        assertEquals(0, count(cfg.staging()), "our staging copies are cleaned up");
        assertEquals(1, count(t24Repo), "only the card goes to T24, never the raw images");
    }

    @Test
    void responsesAndLogsNeverContainIdNumbersOrName() throws Exception {
        HttpResponse<String> r = submit(UUID.randomUUID().toString());
        String log = Files.readString(cfg.archive().resolve("dispatch.log"));
        for (String secret : new String[]{TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE, TestSupport.NAME}) {
            assertFalse(r.body().contains(secret), "response contains " + secret);
            assertFalse(log.contains(secret), "log contains " + secret);
        }
    }

    // ---- the request UUID -------------------------------------------------------------------------------------

    @Test
    void uuidMayComeInTheBodyInstead() throws Exception {
        String uuid = UUID.randomUUID().toString();
        HttpResponse<String> r = post(identityJson(TestSupport.NID, uuid), null);
        assertEquals(202, r.statusCode(), r.body());
        assertEquals(uuid, body(r).get("requestId").asText());
    }

    @Test
    void headerAndBodyUuidMustMatch() throws Exception {
        HttpResponse<String> r = post(identityJson(TestSupport.NID, UUID.randomUUID().toString()), UUID.randomUUID().toString());
        assertEquals(400, r.statusCode());
        assertTrue(body(r).get("reason").asText().contains("differ"));
        assertTrue(onboardingApi.requests.isEmpty(), "nothing processed");
    }

    @Test
    void missingOrInvalidUuidIsRejected() throws Exception {
        assertEquals(400, post(identityJson(TestSupport.NID, null), null).statusCode());
        assertEquals(400, post(identityJson(TestSupport.NID, null), "not-a-uuid").statusCode());
        assertEquals(400, post(identityJson(TestSupport.NID, null), "1-1-1-1-1").statusCode());
        assertEquals(400, post("not json", UUID.randomUUID().toString()).statusCode());
        assertTrue(onboardingApi.requests.isEmpty());
    }

    // ---- idempotency ------------------------------------------------------------------------------------------

    @Test
    void sameUuidTwiceGetsTheStoredAnswerAndNothingRunsAgain() throws Exception {
        String uuid = UUID.randomUUID().toString();
        HttpResponse<String> first = submit(uuid);
        int callsAfterFirst = onboardingApi.requests.size();

        HttpResponse<String> second = submit(uuid);

        assertEquals(first.statusCode(), second.statusCode());
        assertEquals(body(first).get("status"), body(second).get("status"));
        assertEquals(body(first).get("cardSha256"), body(second).get("cardSha256"));
        assertEquals("true", second.headers().firstValue("Idempotent-Replay").orElse(""));
        assertEquals(callsAfterFirst, onboardingApi.requests.size(), "no onboarding API call on replay");
    }

    @Test
    void sameCustomerWithNewUuidGetsTheExistingCardsResult() throws Exception {
        String first = UUID.randomUUID().toString();
        HttpResponse<String> r1 = submit(first);
        dispatch();
        byte[] onT24 = Files.readAllBytes(t24Repo.resolve(TestSupport.ACCOUNT + ".jpg"));

        HttpResponse<String> r2 = submit(UUID.randomUUID().toString());

        assertEquals(200, r2.statusCode(), r2.body());
        assertEquals("EXISTS", body(r2).get("status").asText());
        assertEquals(first, body(r2).get("originalRequestId").asText());
        assertEquals(body(r1).get("cardSha256"), body(r2).get("cardSha256"));
        dispatch();
        assertArrayEquals(onT24, Files.readAllBytes(t24Repo.resolve(TestSupport.ACCOUNT + ".jpg")), "T24 copy never overwritten");
    }

    // ---- failures ---------------------------------------------------------------------------------------------

    @Test
    void disabledEndpointAnswers503AndStoresNothing() throws Exception {
        Config.Server off = new Config.Server(cfg.server().bind(), cfg.server().port(), false);
        startApi(TestSupport.withServer(cfg, off));
        String uuid = UUID.randomUUID().toString();

        HttpResponse<String> r = submit(uuid);

        assertEquals(503, r.statusCode());
        assertEquals("DISABLED", body(r).get("status").asText());
        assertTrue(onboardingApi.requests.isEmpty(), "nothing processed");
        assertEquals(404, get(uuid).statusCode(), "not stored: the same UUID works once re-enabled");
    }

    // ---- finding the customer's folder in OCR_Uploads -----------------------------------------------------------

    @Test
    void newestMatchingFolderIsUsedWhenThereAreSeveral() throws Exception {
        // An older copy whose selfie is broken: if it were picked, the request would come back SUSPICIOUS.
        Path older = customerFolder("002", TestSupport.NID + "_" + TestSupport.ACCOUNT + "_c07a5154_OpenNewBankAccount (2)");
        Files.writeString(older.resolve("Profile.bmp"), "broken");
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 86_400_000L));
        Files.setLastModifiedTime(sourceDir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

        HttpResponse<String> r = submit(UUID.randomUUID().toString());

        assertEquals(202, r.statusCode(), r.body());
        String log = Files.readString(cfg.archive().resolve("dispatch.log"));
        assertTrue(log.contains("SOURCE_MULTIPLE_FOLDERS") && log.contains("count=2"), log);
        assertFalse(log.contains(TestSupport.NID), "folder names (with the national ID) are never logged");
    }

    @Test
    void theEightCharacterCodeCanBeAnything() throws Exception {
        Files.move(sourceDir, sourceDir.resolveSibling(TestSupport.NID + "_" + TestSupport.ACCOUNT + "_9f3e21ab"));
        assertEquals(202, submit(UUID.randomUUID().toString()).statusCode());
    }

    @Test
    void folderInAnotherBranchIsNotFoundAndIsTemporary() throws Exception {
        Path other = Files.createDirectories(sftpRoot.resolve("OCR_Uploads").resolve("018"));
        Files.move(sourceDir, other.resolve(sourceDir.getFileName()));
        String uuid = UUID.randomUUID().toString();
        HttpResponse<String> r = submit(uuid);
        assertEquals(503, r.statusCode(), r.body());
        assertEquals(404, get(uuid).statusCode(), "not stored: the app can retry once the folder is there");
    }

    @Test
    void notConfiguredSourceAnswers503AndIsNotStored() throws Exception {
        startApi(TestSupport.withSource(cfg, new Config.Source(
                new Config.SftpEndpoint("", 22, "", "", cfg.sftpKnownHosts(), null, null, null, 10), "", "", "", "", null)));
        String uuid = UUID.randomUUID().toString();
        HttpResponse<String> r = submit(uuid);
        assertEquals(503, r.statusCode());
        assertEquals("NOT_CONFIGURED", body(r).get("status").asText());
        assertEquals(404, get(uuid).statusCode(), "temporary answers are not stored");
    }

    @Test
    void suspiciousRemoteFileIsQuarantinedAndTheirsLeftAlone() throws Exception {
        byte[] big = new byte[6_000_000]; // test limit is 5 MB
        big[0] = 7;
        Files.write(sourceDir.resolve("Profile.bmp"), big);
        String uuid = UUID.randomUUID().toString();

        HttpResponse<String> r = submit(uuid);

        assertEquals(422, r.statusCode(), r.body());
        assertEquals("SUSPICIOUS", body(r).get("status").asText());
        Path enc = cfg.quarantine().resolve(uuid + "-selfie.bmp.enc");
        assertTrue(Files.exists(enc), "encrypted into quarantine");
        Path back = tmp.resolve("back");
        new Quarantine(cfg.quarantine(), cfg.quarantineKey()).decrypt(enc, back);
        assertArrayEquals(big, Files.readAllBytes(back));
        assertTrue(Files.exists(sourceDir.resolve("Profile.bmp")), "their file is not deleted");
        assertEquals(0, count(cfg.staging()), "no copy of ours left");
        assertEquals(0, count(cfg.outbox()), "no card made");
        assertEquals("SUSPICIOUS", body(submit(uuid)).get("status").asText(), "stored: replay gives the same answer");
    }

    @Test
    void customerNotFoundIsRejectedAndStored() throws Exception {
        String uuid = UUID.randomUUID().toString();
        HttpResponse<String> r = post(identityJson("999999999999", null), uuid);
        assertEquals(422, r.statusCode());
        assertEquals("REJECTED", body(r).get("status").asText());
        assertEquals("REJECTED", body(get(uuid)).get("status").asText());
    }

    @Test
    void t24DownIs503AndTheSameUuidWorksOnceItIsBack() throws Exception {
        String uuid = UUID.randomUUID().toString();
        onboardingApi.down = true;
        HttpResponse<String> r1 = submit(uuid);
        assertEquals(503, r1.statusCode());
        assertEquals("UNAVAILABLE", body(r1).get("status").asText());

        onboardingApi.down = false;
        HttpResponse<String> r2 = submit(uuid);
        assertEquals(202, r2.statusCode(), r2.body());
    }

    @Test
    void imageNotOnTheServerYetIs503() throws Exception {
        Files.delete(sourceDir.resolve("SignatureCard.bmp"));
        HttpResponse<String> r = submit(UUID.randomUUID().toString());
        assertEquals(503, r.statusCode(), r.body());
    }
}
