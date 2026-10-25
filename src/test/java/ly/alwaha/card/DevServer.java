package ly.alwaha.card;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Local demo for trying the dashboard without the bank network. Test sources only, never in the jar.
 * Fake onboarding API + one fake customer, an embedded SFTP server (onboarding images + T24 repository), and the
 * real service with the dashboard. Host keys are NOT pinned: pin them from the Configuration tab.
 *
 *   args: PORT PASSWORD
 */
public final class DevServer {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String password = args[1];
        Path tmp = Files.createTempDirectory("scd-demo");

        Path sftpRoot = Files.createDirectories(tmp.resolve("sftp-root"));
        Files.createDirectories(sftpRoot.resolve("repo"));
        Path customer = Files.createDirectories(sftpRoot.resolve("onboarding").resolve(TestSupport.NID));
        ImageIO.write(TestSupport.selfie(), "jpg", customer.resolve("selfie.jpg").toFile());
        ImageIO.write(TestSupport.signature(), "png", customer.resolve("signature.png").toFile());
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((u, p, s) -> "t24".equals(u) && "secret".equals(p));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot));
        sshd.start();

        StubOnboardingApi onboardingApi = new StubOnboardingApi();
        onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));

        Path configDir = Files.createDirectories(tmp.resolve("config"));
        Path configFile = configDir.resolve("application.properties");
        String root = TestSupport.project().toString().replace('\\', '/');
        Files.writeString(configFile, String.join("\n",
                "# DEMO config (fake T24, fake customer)",
                "work.dir=" + tmp.resolve("work").toString().replace('\\', '/'),
                "template.dir=" + root + "/templates/BM-AC008-2026",
                "font.regular=" + root + "/fonts/Almarai-Regular.ttf",
                "font.bold=" + root + "/fonts/Almarai-Bold.ttf",
                "poll.seconds=3",
                "api.base.url=" + onboardingApi.baseUrl(),
                "api.cif.path=" + StubOnboardingApi.CIF_PATH + "{cif}",
                "branches.file=" + configDir.toString().replace('\\', '/') + "/branches.properties",
                "sftp.host=127.0.0.1",
                "sftp.port=" + sshd.getPort(),
                "sftp.user=t24",
                "sftp.remote.dir=repo",
                "sftp.known.hosts=" + configDir.toString().replace('\\', '/') + "/known_hosts",
                "source.sftp.host=127.0.0.1",
                "source.sftp.port=" + sshd.getPort(),
                "source.sftp.user=t24",
                "source.sftp.known.hosts=" + configDir.toString().replace('\\', '/') + "/source_known_hosts",
                "source.selfie.path=/onboarding/{nationalId}/selfie.jpg",
                "source.signature.path=/onboarding/{nationalId}/signature.png",
                "server.bind=127.0.0.1",
                "server.port=" + port,
                ""), StandardCharsets.UTF_8);

        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        Secrets secrets = new Secrets(configDir.resolve("secrets.enc"), Base64.getEncoder().encodeToString(master), n -> null);
        byte[] qk = new byte[32];
        new SecureRandom().nextBytes(qk);
        secrets.put(Map.of("SFTP_PASSWORD", "secret", "SOURCE_SFTP_PASSWORD", "secret",
                "QUARANTINE_KEY", Base64.getEncoder().encodeToString(qk)));

        Service service = new Service(Config.load(configFile, secrets), configFile, secrets);
        ApiServer api = new ApiServer(service, new AdminApi(service, password));
        api.start();
        System.out.println("DEMO dashboard: http://127.0.0.1:" + port + "/dashboard");
        System.out.println("DEMO customer:  nationalId=" + TestSupport.NID + " passport=" + TestSupport.PASSPORT
                + " phone=" + TestSupport.PHONE);
        System.out.println("DEMO files:     " + tmp);
        while (true) {
            Service.Components c = service.current();
            try {
                c.dispatcher().dispatchOutbox();
            } catch (Exception e) {
                c.audit().log("ERROR", null, "reason=\"" + e + "\"");
            }
            Thread.sleep(c.cfg().pollSeconds() * 1000L);
        }
    }
}
