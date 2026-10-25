package ly.alwaha.card;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.stream.Stream;

/** Fake test data: a synthetic selfie and signature, the placeholder template, and a config pointing at temp folders. */
final class TestSupport {

    static final String NAME = "محمد علي عبدالله الورفلي";
    static final String BRANCH = "فرع طرابلس الرئيسي";
    static final String ACCOUNT = "0012345678901";
    static final String NID = "119900112233";
    static final String PASSPORT = "PA1234567";
    static final String PHONE = "0912345678";
    /** Test-only key (32 bytes, base64). Never used outside tests. */
    static final String QUARANTINE_KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    private TestSupport() {}

    static Path project() {
        return Path.of("").toAbsolutePath();
    }

    /** Copies layout.json into a temp template folder and generates the placeholder template next to it. */
    static Path templateDir(Path tmp) throws IOException {
        Path dir = tmp.resolve("template");
        Files.createDirectories(dir);
        Files.copy(project().resolve("templates/BM-AC008-2026/layout.json"), dir.resolve("layout.json"));
        Layout layout = Layout.load(dir.resolve("layout.json"));
        ImageIO.write(TemplateTools.placeholder(layout), "png", dir.resolve("template.png").toFile());
        return dir;
    }

    static CardRenderer renderer(Path tmp) throws IOException {
        return new CardRenderer(templateDir(tmp), project().resolve("fonts/Almarai-Regular.ttf"),
                project().resolve("fonts/Almarai-Bold.ttf"));
    }

    static BufferedImage selfie() {
        BufferedImage img = new BufferedImage(900, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(90, 120, 160), 0, 1200, new Color(30, 40, 60)));
        g.fillRect(0, 0, 900, 1200);
        g.setColor(new Color(224, 180, 150));
        g.fillOval(250, 250, 400, 520);
        g.setColor(new Color(60, 60, 60));
        g.fillOval(350, 430, 50, 30);
        g.fillOval(500, 430, 50, 30);
        g.fillRect(150, 900, 600, 300);
        g.dispose();
        return img;
    }

    /** Dark-blue strokes on slightly grey paper, like a photographed signature. */
    static BufferedImage signature() {
        BufferedImage img = new BufferedImage(1600, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        CardRenderer.hints(g);
        g.setColor(new Color(244, 244, 240));
        g.fillRect(0, 0, 1600, 700);
        g.setColor(new Color(20, 30, 90));
        g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new CubicCurve2D.Double(150, 450, 400, 50, 600, 650, 850, 300));
        g.draw(new CubicCurve2D.Double(850, 300, 1000, 100, 1200, 600, 1450, 250));
        g.draw(new CubicCurve2D.Double(300, 550, 700, 520, 1100, 560, 1400, 500));
        g.dispose();
        return img;
    }

    /** Writes an inbox folder like the onboarding side would: identity + two images, backdated so it counts as "stable". */
    static Path writeInputSet(Path inbox, String id, String nationalId) throws IOException {
        Path dir = inbox.resolve(id);
        Files.createDirectories(dir);
        ImageIO.write(selfie(), "jpg", dir.resolve("selfie.jpg").toFile());
        ImageIO.write(signature(), "png", dir.resolve("signature.png").toFile());
        Files.writeString(dir.resolve("data.json"),
                "{\"nationalId\":\"" + nationalId + "\",\"passportNumber\":\"" + PASSPORT
                        + "\",\"phoneNumber\":\"" + PHONE + "\"}",
                StandardCharsets.UTF_8);
        backdate(dir);
        return dir;
    }

    static void backdate(Path dir) throws IOException {
        FileTime old = FileTime.from(Instant.now().minusSeconds(600));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) Files.setLastModifiedTime(f, old);
        }
        Files.setLastModifiedTime(dir, old);
    }

    static Config config(Path tmp, Path templateDir, String host, int port, Path knownHosts, String remoteDir,
                         Config.Api api) {
        return new Config(tmp.resolve("work"), templateDir,
                project().resolve("fonts/Almarai-Regular.ttf"), project().resolve("fonts/Almarai-Bold.ttf"),
                1, 0, 3, 0.95f, true,
                host, port, "t24", remoteDir, knownHosts, null, "secret", null, 10,
                api, new Config.ImageLimits(5_000_000, 10_000, 50_000_000), QUARANTINE_KEY,
                new Config.Source(new Config.SftpEndpoint("", 22, "", "", knownHosts, null, null, null, 10), "", ""),
                new Config.Server("127.0.0.1", 0));
    }
}
