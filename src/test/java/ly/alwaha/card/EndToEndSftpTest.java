package ly.alwaha.card;

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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases of the full flow through the endpoint: input checks, file safety, and the T24 delivery rules.
 * One embedded SFTP server plays the onboarding server (OCR_Uploads layout) and the T24 repository.
 */
class EndToEndSftpTest {

    @TempDir
    Path tmp;
    SshServer sshd;
    StubOnboardingApi onboardingApi;
    ApiServer api;
    Path t24Repo;
    Path uploads;
    Path customerDir;
    Path knownHosts;
    Path template;
    Config cfg;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("sftp-root"));
        t24Repo = Files.createDirectories(root.resolve("repo"));
        uploads = Files.createDirectories(root.resolve("OCR_Uploads"));
        customerDir = customerFolder(TestSupport.NID, TestSupport.ACCOUNT);

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((user, pass, session) -> "t24".equals(user) && "secret".equals(pass));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
        sshd.start();
        KeyPair hostKey = sshd.getKeyPairProvider().loadKeys(null).iterator().next();
        knownHosts = tmp.resolve("known_hosts");
        Files.writeString(knownHosts, "[127.0.0.1]:" + sshd.getPort() + " " + PublicKeyEntry.toString(hostKey.getPublic()) + "\n");

        template = TestSupport.templateDir(tmp);
        onboardingApi = new StubOnboardingApi();
        onboardingApi.byNationalId.put(TestSupport.NID,
                new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));
        cfg = config(sshd.getPort(), knownHosts);
        startApi(cfg);
    }

    @AfterEach
    void stop() throws Exception {
        api.close();
        sshd.stop(true);
        onboardingApi.close();
    }

    private Config config(int t24Port, Path t24KnownHosts) throws Exception {
        Config base = TestSupport.config(tmp, template, "127.0.0.1", t24Port, t24KnownHosts, "repo",
                onboardingApi.config());
        Config.Source source = new Config.Source(
                new Config.SftpEndpoint("127.0.0.1", sshd.getPort(), "t24", "", knownHosts, null, "secret", null, 10),
                "/OCR_Uploads", Config.DEFAULTS.get("source.folder.pattern"), "Profile", "SignatureCard", null);
        return TestSupport.withSource(base, source);
    }

    private void startApi(Config c) throws Exception {
        if (api != null) api.close();
        api = new ApiServer(new Service(c, null, null), null);
        api.start();
    }

    /** The app's layout: OCR_Uploads/{branch3}/{nationalId}_{accountNumber}_{trace number}/Profile.bmp + SignatureCard.bmp */
    private Path customerFolder(String nid, String account) throws Exception {
        Path dir = Files.createDirectories(uploads.resolve("002").resolve(nid + "_" + account + "_c07a5154"));
        ImageIO.write(TestSupport.selfie(), "bmp", dir.resolve("Profile.bmp").toFile());
        ImageIO.write(TestSupport.signature(), "bmp", dir.resolve("SignatureCard.bmp").toFile());
        return dir;
    }

    private HttpResponse<String> submit(String nid) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + ApiServer.BASE))
                .header("Content-Type", "application/json").header("X-Request-Id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"nationalId\":\"" + nid + "\",\"passportNumber\":\""
                        + TestSupport.PASSPORT + "\",\"phoneNumber\":\"" + TestSupport.PHONE + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void dispatch(Config c) throws Exception {
        new Dispatcher(c, new AuditLog(c.archive().resolve("dispatch.log"))).dispatchOutbox();
    }

    private String log() throws Exception {
        Path f = cfg.archive().resolve("dispatch.log");
        return Files.exists(f) ? Files.readString(f) : "";
    }

    private static long count(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    // ---- input checks -----------------------------------------------------------------------------------------

    @Test
    void identityThatCouldChangeTheUrlIsRejectedBeforeAnyCall() throws Exception {
        HttpResponse<String> r = submit("..%2F..%2Fadmin");
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains("nationalId"));
        assertTrue(onboardingApi.requests.isEmpty(), "no HTTP call made");
    }

    @Test
    void accountNumberWithWrongDigitCountIsRejected() throws Exception {
        onboardingApi.byNationalId.put("120000000001", new StubOnboardingApi.Customer("1234567", TestSupport.NAME, TestSupport.BRANCH_CODE));
        HttpResponse<String> r = submit("120000000001");
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains("15 digits"), r.body());
    }

    @Test
    void branchCodeNotInGetBranchesIsRejected() throws Exception {
        onboardingApi.byNationalId.put("120000000002", new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, "LY9999999"));
        HttpResponse<String> r = submit("120000000002");
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains("branch code LY9999999 is not in the getBranches list"), r.body());
    }

    // ---- file safety: checked from metadata before the image is decoded --------------------------------------

    @Test
    void textFileNamedBmpIsQuarantinedAndTheirsLeftAlone() throws Exception {
        Files.writeString(customerDir.resolve("SignatureCard.bmp"), "<script>not an image</script>");
        HttpResponse<String> r = submit(TestSupport.NID);
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains("SUSPICIOUS") && r.body().contains("not a JPEG, PNG or BMP"), r.body());
        assertTrue(Files.exists(customerDir.resolve("SignatureCard.bmp")), "their file is not deleted");
        assertEquals(1, count(cfg.quarantine()), "our copy encrypted into quarantine");
        assertEquals(0, count(cfg.staging()));
        assertEquals(0, count(cfg.outbox()), "no card made");
    }

    /** A tiny PNG whose header claims 60000x60000 pixels: decoding it would need ~14 GB of memory. */
    @Test
    void decompressionBombIsCaughtFromTheHeaderAlone() throws Exception {
        Files.write(customerDir.resolve("Profile.bmp"), pngHeader(60_000, 60_000));
        HttpResponse<String> r = submit(TestSupport.NID);
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains("60000x60000"), r.body());
        assertEquals(0, count(cfg.outbox()));
    }

    private static byte[] pngHeader(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(ihdr);
        c.writeBytes("IHDR");
        c.writeInt(w);
        c.writeInt(h);
        c.write(new byte[]{8, 2, 0, 0, 0});
        byte[] chunk = ihdr.toByteArray();
        d.writeInt(13);
        d.write(chunk);
        CRC32 crc = new CRC32();
        crc.update(chunk);
        d.writeInt((int) crc.getValue());
        return out.toByteArray();
    }

    // ---- T24 delivery rules ---------------------------------------------------------------------------------

    @Test
    void existingDifferentFileOnT24IsRefusedNotOverwritten() throws Exception {
        Files.write(t24Repo.resolve(TestSupport.ACCOUNT + ".jpg"), new byte[]{1, 2, 3});
        assertEquals(202, submit(TestSupport.NID).statusCode());
        dispatch(cfg);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(t24Repo.resolve(TestSupport.ACCOUNT + ".jpg")));
        assertTrue(log().contains("REFUSED account=" + TestSupport.ACCOUNT));
        assertEquals("FAILED", Dispatcher.deliveryStatus(cfg, TestSupport.ACCOUNT));
    }

    @Test
    void unknownT24HostKeyFailsAndNothingIsUploaded() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        Path wrongHosts = tmp.resolve("wrong_known_hosts");
        Files.writeString(wrongHosts, "[127.0.0.1]:" + sshd.getPort() + " "
                + PublicKeyEntry.toString(gen.generateKeyPair().getPublic()) + "\n");
        Config wrong = config(sshd.getPort(), wrongHosts);
        assertEquals(202, submit(TestSupport.NID).statusCode());

        dispatch(wrong);
        dispatch(wrong);
        dispatch(wrong); // dispatch.max.attempts = 3 in tests
        assertTrue(log().contains("FAILED account=" + TestSupport.ACCOUNT), log());
        assertEquals(0, count(t24Repo), "nothing written to T24");
    }

    @Test
    void t24SftpDownThenUpDeliversOnTheNextCycle() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertEquals(202, submit(TestSupport.NID).statusCode());
        dispatch(config(closedPort, knownHosts));
        assertEquals("PENDING", Dispatcher.deliveryStatus(cfg, TestSupport.ACCOUNT), "card waits in the outbox");
        assertTrue(log().contains("RETRY"));

        dispatch(cfg);
        assertTrue(Files.exists(t24Repo.resolve(TestSupport.ACCOUNT + ".jpg")));
        assertFalse(Files.exists(t24Repo.resolve(TestSupport.ACCOUNT + ".jpg.part")), "no .part left behind");
        assertEquals("DELIVERED", Dispatcher.deliveryStatus(cfg, TestSupport.ACCOUNT));
    }

    @Test
    void checkSftpLogsInAndFindsTheFolderWithoutWriting() throws Exception {
        try (SftpConnection up = SftpConnection.connect(cfg.t24Sftp())) {
            assertTrue(up.describeRemoteDir().endsWith("repo"));
        }
        assertEquals(0, count(t24Repo), "nothing uploaded");
    }
}
