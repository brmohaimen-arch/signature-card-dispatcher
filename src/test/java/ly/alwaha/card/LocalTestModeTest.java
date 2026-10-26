package ly.alwaha.card;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOCAL TEST MODE end to end, from a real config file: no SFTP server, no network.
 * Customer data from the shipped example files, images from a local OCR_Uploads copy, card into a local folder.
 */
class LocalTestModeTest {

    @TempDir
    Path tmp;
    Service service;
    ApiServer api;
    Path uploads;
    Path out;
    Path customerDir;
    Path configFile;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        Path project = TestSupport.project();
        uploads = tmp.resolve("OCR_Uploads");
        out = tmp.resolve("T24_out");
        customerDir = Files.createDirectories(uploads.resolve("002")
                .resolve(TestSupport.NID + "_" + TestSupport.ACCOUNT + "_c07a5154"));
        ImageIO.write(TestSupport.selfie(), "bmp", customerDir.resolve("Profile.bmp").toFile());
        ImageIO.write(TestSupport.signature(), "bmp", customerDir.resolve("SignatureCard.bmp").toFile());

        Path configDir = Files.createDirectories(tmp.resolve("config"));
        configFile = configDir.resolve("application.properties");
        Files.writeString(configFile, String.join("\n",
                "profile=test",
                "work.dir=" + fwd(tmp.resolve("work")),
                "template.dir=" + fwd(project.resolve("templates/BM-AC008-2026")),
                "font.regular=" + fwd(project.resolve("fonts/Almarai-Regular.ttf")),
                "font.bold=" + fwd(project.resolve("fonts/Almarai-Bold.ttf")),
                "branches.file=" + fwd(configDir.resolve("branches.properties")),
                "api.mode=local",
                "api.local.customers.file=" + fwd(project.resolve("config/test-customers.example.json")),
                "api.local.branches.file=" + fwd(project.resolve("config/test-branches.example.json")),
                "source.mode=local",
                "source.local.root=" + fwd(uploads),
                "t24.mode=local",
                "t24.local.dir=" + fwd(out),
                "server.bind=127.0.0.1",
                "server.port=0",
                ""), StandardCharsets.UTF_8);
        Secrets secrets = new Secrets(configDir.resolve("secrets.enc"), Base64.getEncoder().encodeToString(new byte[32]), n -> null);
        secrets.put(java.util.Map.of("QUARANTINE_KEY", TestSupport.QUARANTINE_KEY));
        service = new Service(Config.load(configFile, secrets), configFile, secrets);
        api = new ApiServer(service, null);
        api.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private static String fwd(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private HttpResponse<String> submit(String nationalId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + ApiServer.BASE))
                .header("Content-Type", "application/json").header("X-Request-Id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"nationalId\":\"" + nationalId + "\",\"passportNumber\":\""
                        + TestSupport.PASSPORT + "\",\"phoneNumber\":\"" + TestSupport.PHONE + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void wholeFlowRunsOnLocalFoldersAndFiles() throws Exception {
        assertTrue(service.current().cfg().localTestMode());

        HttpResponse<String> r = submit(TestSupport.NID);
        assertEquals(202, r.statusCode(), r.body());
        assertEquals(TestSupport.ACCOUNT, json.readTree(r.body()).path("accountNumber").asText());

        service.current().dispatcher().dispatchOutbox();

        Path card = out.resolve(TestSupport.ACCOUNT + ".jpg");
        assertTrue(Files.exists(card), "card written to the local output folder");
        assertFalse(Files.exists(out.resolve(TestSupport.ACCOUNT + ".jpg.part")));
        assertNotNull(ImageIO.read(card.toFile()), "a valid JPEG");
        assertTrue(Files.exists(customerDir.resolve("Profile.bmp")) && Files.exists(customerDir.resolve("SignatureCard.bmp")),
                "the uploads folder is only read");
        try (Stream<Path> s = Files.list(out)) {
            assertEquals(1, s.count(), "only the card in the output folder");
        }
    }

    @Test
    void customerNotInTheLocalFileIsRejected() throws Exception {
        HttpResponse<String> r = submit("999999999999");
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains("local test file"), r.body());
    }

    @Test
    void englishBranchNameFromTheLocalBranchesFileWhenNoArabicIsSaved() throws Exception {
        CardData d = service.current().lookup().lookup(
                new CustomerLookup.Identity(TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE));
        assertEquals("Sooq Eljoma Branch", d.branch());
        assertEquals("محمد علي عبدالله الورفلي", d.fullName());
    }

    @Test
    void productionProfileRefusesEveryLocalMode() throws Exception {
        String test = Files.readString(configFile).replace("profile=test", "profile=production");
        Secrets none = new Secrets(tmp.resolve("none.enc"), null, n -> null);
        List<String> modes = List.of("api.mode=local", "source.mode=local", "t24.mode=local");
        for (String keep : modes) {
            String prod = test;
            for (String other : modes) if (!other.equals(keep)) prod = prod.replace(other + "\n", "");
            Files.writeString(configFile, prod);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(configFile, none), keep);
            assertTrue(e.getMessage().contains("profile=production"), e.getMessage());
        }
    }
}
