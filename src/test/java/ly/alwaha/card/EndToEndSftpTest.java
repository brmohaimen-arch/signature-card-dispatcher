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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full flow: inbox → onboarding API lookups → file checks → card → archive → SFTP.
 * An embedded SFTP server stands in for the T24 repository and a stub HTTP server for the onboarding API.
 */
class EndToEndSftpTest {

    @TempDir
    Path tmp;
    SshServer sshd;
    StubOnboardingApi onboardingApi;
    Path remoteRoot;
    Path knownHosts;
    Path templateDir;

    @BeforeEach
    void start() throws Exception {
        remoteRoot = Files.createDirectories(tmp.resolve("t24-root/repo"));
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((user, pass, session) -> "t24".equals(user) && "secret".equals(pass));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(tmp.resolve("t24-root")));
        sshd.start();
        KeyPair hostKey = sshd.getKeyPairProvider().loadKeys(null).iterator().next();
        knownHosts = tmp.resolve("known_hosts");
        Files.writeString(knownHosts, "[127.0.0.1]:" + sshd.getPort() + " " + PublicKeyEntry.toString(hostKey.getPublic()) + "\n");
        templateDir = TestSupport.templateDir(tmp);

        onboardingApi = new StubOnboardingApi();
        onboardingApi.byNationalId.put(TestSupport.NID,
                new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));
    }

    @AfterEach
    void stop() throws Exception {
        sshd.stop(true);
        onboardingApi.close();
    }

    private Config cfg(int port, Path hosts) {
        return TestSupport.config(tmp, templateDir, "127.0.0.1", port, hosts, "repo", onboardingApi.config());
    }

    private void cycle(Config cfg) throws Exception {
        CardRenderer renderer = new CardRenderer(cfg.templateDir(), cfg.fontRegular(), cfg.fontBold());
        AuditLog audit = new AuditLog(cfg.archive().resolve("dispatch.log"));
        new Generator(cfg, renderer, audit, new CustomerLookup(cfg.api()),
                new Quarantine(cfg.quarantine(), cfg.quarantineKey())).processInbox();
        new Dispatcher(cfg, audit).dispatchOutbox();
    }

    private String log(Config cfg) throws Exception {
        Path f = cfg.archive().resolve("dispatch.log");
        return Files.exists(f) ? Files.readString(f) : "";
    }

    private Path failedReason(Config cfg) throws Exception {
        try (Stream<Path> s = Files.list(cfg.failed())) {
            return s.filter(Files::isDirectory).findFirst().orElseThrow().resolve("reason.txt");
        }
    }

    // ---- happy path and onboarding API lookups -------------------------------------------------------------------------

    @Test
    void looksUpCustomerAndDeliversCardNamedByAccountNumber() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001", TestSupport.NID);

        cycle(cfg);

        Path remote = remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg");
        assertTrue(Files.exists(remote), "card delivered to T24 path");
        assertArrayEquals(Files.readAllBytes(cfg.archive().resolve(TestSupport.ACCOUNT + ".jpg")), Files.readAllBytes(remote));
        assertFalse(Files.exists(remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg.part")), "no .part left behind");
        assertEquals(0, count(cfg.outbox()));
        assertEquals(0, count(cfg.inbox()));
        assertTrue(log(cfg).contains("DELIVERED account=" + TestSupport.ACCOUNT));
    }

    @Test
    void nameAndBranchComeFromTheOneAccountDetailsCall() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001", TestSupport.NID);

        cycle(cfg);

        assertEquals(1, onboardingApi.requests.size(), "get CIF is off: one call only");
        assertTrue(onboardingApi.requests.get(0).endsWith("/" + TestSupport.NID + "/" + TestSupport.PASSPORT + "/" + TestSupport.PHONE),
                "account call carries nationalId/passport/phone in that order");
        assertTrue(Files.exists(remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg")), "15-digit card delivered");
    }

    @Test
    void unknownBranchCodeIsRejected() throws Exception {
        onboardingApi.byNationalId.put("120000000002", new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, "LY9999999"));
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-BRANCH", "120000000002");

        cycle(cfg);

        assertTrue(Files.readString(failedReason(cfg)).contains("branch code LY9999999 is not in the branch table"));
        assertEquals(0, count(remoteRoot));
    }

    @Test
    void identityNumbersAndNameAreNeverLogged() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001", TestSupport.NID);
        cycle(cfg);
        String log = log(cfg);
        for (String secret : new String[]{TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE, TestSupport.NAME}) {
            assertFalse(log.contains(secret), "log must not contain " + secret);
        }
    }

    @Test
    void customerNotFoundIsRejectedWithoutCallingGetCif() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0404", "999999999999");

        cycle(cfg);

        assertEquals(1, onboardingApi.requests.size(), "only the account call was made");
        assertTrue(Files.readString(failedReason(cfg)).contains("not found"));
        assertEquals(0, count(remoteRoot));
    }

    @Test
    void apiDownLeavesInputForNextCycleThenDelivers() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001", TestSupport.NID);

        onboardingApi.down = true;
        cycle(cfg);
        assertEquals(1, count(cfg.inbox()), "input kept for retry");
        assertTrue(log(cfg).contains("HTTP 503"));

        onboardingApi.down = false;
        cycle(cfg);
        assertTrue(Files.exists(remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg")));
        assertEquals(0, count(cfg.inbox()));
    }

    @Test
    void identityThatCouldChangeTheUrlIsRejectedBeforeAnyCall() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-EVIL", "..%2F..%2Fadmin");

        cycle(cfg);

        assertTrue(onboardingApi.requests.isEmpty(), "no HTTP call made");
        assertTrue(Files.readString(failedReason(cfg)).contains("nationalId"));
    }

    @Test
    void accountNumberWithWrongDigitCountIsRejected() throws Exception {
        onboardingApi.byNationalId.put("120000000001", new StubOnboardingApi.Customer("1234567", TestSupport.NAME, TestSupport.BRANCH_CODE));
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-SHORT", "120000000001");

        cycle(cfg);

        assertTrue(Files.readString(failedReason(cfg)).contains("15 digits"));
        assertEquals(0, count(remoteRoot));
    }

    // ---- suspicious files: metadata is checked before the file is opened -----------------------------------

    @Test
    void oversizedFileIsDeletedNotOpened() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        Path dir = TestSupport.writeInputSet(cfg.inbox(), "ONB-BIG", TestSupport.NID);
        byte[] big = new byte[6_000_000]; // limit in tests is 5 MB
        big[0] = 42;
        Files.write(dir.resolve("selfie.jpg"), big);
        TestSupport.backdate(dir);

        cycle(cfg);

        assertQuarantined(cfg, "ONB-BIG", "selfie.jpg", "limit", big);
    }

    @Test
    void textFileNamedJpgIsDeleted() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        Path dir = TestSupport.writeInputSet(cfg.inbox(), "ONB-FAKE", TestSupport.NID);
        byte[] fake = "<script>not an image</script>".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("signature.png"), fake);
        TestSupport.backdate(dir);

        cycle(cfg);

        assertQuarantined(cfg, "ONB-FAKE", "signature.png", "not a JPEG, PNG or WebP", fake);
    }

    /** A tiny PNG whose header claims 60000x60000 pixels: decoding it would need ~14 GB of memory. */
    @Test
    void decompressionBombIsCaughtFromTheHeaderAlone() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        Path dir = TestSupport.writeInputSet(cfg.inbox(), "ONB-BOMB", TestSupport.NID);
        byte[] bomb = pngHeader(60_000, 60_000);
        Files.write(dir.resolve("selfie.jpg"), bomb);
        TestSupport.backdate(dir);

        cycle(cfg);

        assertQuarantined(cfg, "ONB-BOMB", "selfie.jpg", "60000x60000", bomb);
    }

    /** Suspicious file: original deleted, an encrypted copy in quarantine/ that decrypts back to the exact bytes. */
    private void assertQuarantined(Config cfg, String id, String file, String reasonPart, byte[] original) throws Exception {
        assertTrue(log(cfg).contains("SUSPICIOUS"), log(cfg));
        assertTrue(log(cfg).contains("action=encrypted+deleted"));
        assertEquals(0, count(cfg.outbox()), "no card made for a suspicious set");
        assertFalse(Files.exists(cfg.archive().resolve(TestSupport.ACCOUNT + ".jpg")));
        Path reason = failedReason(cfg);
        assertTrue(Files.readString(reason).contains(reasonPart), Files.readString(reason));
        assertFalse(Files.exists(reason.resolveSibling(file)), file + " was deleted");
        assertEquals(0, count(remoteRoot));

        Path enc = cfg.quarantine().resolve(id + "-" + file + ".enc");
        assertTrue(Files.exists(enc), "encrypted copy in quarantine");
        assertEquals(-1, indexOf(Files.readAllBytes(enc), java.util.Arrays.copyOf(original, Math.min(16, original.length))),
                "quarantine file is not the plain content");
        Path back = tmp.resolve("decrypted");
        new Quarantine(cfg.quarantine(), cfg.quarantineKey()).decrypt(enc, back);
        assertArrayEquals(original, Files.readAllBytes(back), "decrypts to the exact original");
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
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
        c.write(new byte[]{8, 2, 0, 0, 0}); // 8-bit RGB
        byte[] chunk = ihdr.toByteArray();
        d.writeInt(13);
        d.write(chunk);
        CRC32 crc = new CRC32();
        crc.update(chunk);
        d.writeInt((int) crc.getValue());
        return out.toByteArray();
    }

    // ---- SFTP delivery rules -----------------------------------------------------------------------------------

    @Test
    void sameInputTwiceIsDeliveredOnce() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001", TestSupport.NID);
        cycle(cfg);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001-again", TestSupport.NID);
        cycle(cfg);

        assertTrue(log(cfg).contains("EXISTING account=" + TestSupport.ACCOUNT));
        assertEquals(1, log(cfg).lines().filter(l -> l.contains(" DELIVERED ")).count());
    }

    @Test
    void sameAccountAgainReturnsExistingCardNotOverwritten() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001", TestSupport.NID);
        cycle(cfg);
        byte[] delivered = Files.readAllBytes(remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg"));

        // Same account, but the CIF now answers with a different name.
        onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, "أحمد سالم", TestSupport.BRANCH_CODE));
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0002", TestSupport.NID);
        cycle(cfg);

        assertArrayEquals(delivered, Files.readAllBytes(remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg")));
        assertTrue(log(cfg).contains("EXISTING account=" + TestSupport.ACCOUNT));
        assertEquals(1, log(cfg).lines().filter(l -> l.contains(" GENERATED ")).count(), "only one card ever made");
    }

    @Test
    void existingDifferentFileOnT24IsRefused() throws Exception {
        Config cfg = cfg(sshd.getPort(), knownHosts);
        Files.write(remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg"), new byte[]{1, 2, 3});
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001", TestSupport.NID);

        cycle(cfg);

        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg")));
        assertTrue(log(cfg).contains("REFUSED account=" + TestSupport.ACCOUNT));
    }

    @Test
    void unknownHostKeyIsRejectedAndNothingIsUploaded() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        Path wrongHosts = tmp.resolve("wrong_known_hosts");
        Files.writeString(wrongHosts, "[127.0.0.1]:" + sshd.getPort() + " "
                + PublicKeyEntry.toString(gen.generateKeyPair().getPublic()) + "\n");
        Config cfg = cfg(sshd.getPort(), wrongHosts);
        TestSupport.writeInputSet(cfg.inbox(), "ONB-0001", TestSupport.NID);

        cycle(cfg);
        cycle(cfg);
        cycle(cfg); // maxAttempts = 3
        assertTrue(log(cfg).contains("FAILED account=" + TestSupport.ACCOUNT));
        assertEquals(0, count(remoteRoot), "nothing written to T24");
    }

    @Test
    void sftpDownThenUpDeliversOnRetry() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        Config down = cfg(closedPort, knownHosts);
        TestSupport.writeInputSet(down.inbox(), "ONB-0001", TestSupport.NID);
        cycle(down);
        assertEquals(1, count(down.outbox(), ".jpg"), "card waits in outbox");

        cycle(cfg(sshd.getPort(), knownHosts));
        assertTrue(Files.exists(remoteRoot.resolve(TestSupport.ACCOUNT + ".jpg")));
        assertEquals(0, count(down.outbox()));
    }

    @Test
    void checkSftpLogsInAndFindsFolderWithoutWriting() throws Exception {
        try (SftpConnection up = SftpConnection.connect(cfg(sshd.getPort(), knownHosts).t24Sftp())) {
            assertTrue(up.describeRemoteDir().endsWith("repo"));
        }
        assertEquals(0, count(remoteRoot), "nothing uploaded");
        Config missing = TestSupport.config(tmp, templateDir, "127.0.0.1", sshd.getPort(), knownHosts, "no-such-dir", onboardingApi.config());
        try (SftpConnection up = SftpConnection.connect(missing.t24Sftp())) {
            assertThrows(java.io.IOException.class, up::describeRemoteDir);
        }
    }

    private static long count(Path dir) throws Exception {
        return count(dir, "");
    }

    private static long count(Path dir, String suffix) throws Exception {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        }
    }
}
