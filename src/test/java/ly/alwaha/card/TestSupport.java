package ly.alwaha.card;

import ly.alwaha.card.Layout.Box;
import ly.alwaha.card.Layout.Slot;
import ly.alwaha.card.Layout.TextBox;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Fake test data: a synthetic selfie and signature, the placeholder template, and a config pointing at temp folders. */
final class TestSupport {

    static final String NAME = "محمد علي عبدالله الورفلي";
    static final String BRANCH = "فرع سوق الجمعة";
    static final String BRANCH_ENGLISH = "Sooq Eljoma Branch";
    static final String ACCOUNT = "101234567890123"; // 15 digits; first 7 = CIF
    static final String BRANCH_CODE = "LY0010002";
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
        ImageIO.write(placeholder(layout), "png", dir.resolve("template.png").toFile());
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

    static Config config(Path tmp, Path templateDir, String host, int port, Path knownHosts, String remoteDir,
                         Config.Api api) {
        return new Config(false, tmp.resolve("work"), templateDir,
                project().resolve("fonts/Almarai-Regular.ttf"), project().resolve("fonts/Almarai-Bold.ttf"),
                1, 3, 0.95f,
                host, port, "t24", remoteDir, knownHosts, null, "secret", null, 10,
                api, new Config.ImageLimits(5_000_000, 10_000, 50_000_000), QUARANTINE_KEY,
                new Config.Source(new Config.SftpEndpoint("", 22, "", "", knownHosts, null, null, null, 10), "",
                        Config.DEFAULTS.get("source.folder.pattern"), "Profile", "SignatureCard", null),
                new Config.Server("127.0.0.1", 0, true), null);
    }

    static Config withSource(Config c, Config.Source s) {
        return copy(c, s, c.server());
    }

    static Config withServer(Config c, Config.Server server) {
        return copy(c, c.source(), server);
    }

    private static Config copy(Config c, Config.Source s, Config.Server server) {
        return new Config(c.testProfile(), c.workDir(), c.templateDir(), c.fontRegular(), c.fontBold(), c.pollSeconds(),
                c.maxAttempts(), c.jpegQuality(), c.sftpHost(), c.sftpPort(), c.sftpUser(),
                c.sftpRemoteDir(), c.sftpKnownHosts(), c.sftpKeyPath(), c.sftpPassword(), c.sftpKeyPassphrase(),
                c.sftpTimeoutSeconds(), c.api(), c.limits(), c.quarantineKey(), s, server, c.t24Local());
    }

    /**
     * A plain stand-in card with grey bars and empty boxes at the layout positions.
     * Clearly watermarked: it must never be sent to T24.
     */
    static BufferedImage placeholder(Layout l) {
        BufferedImage img = new BufferedImage(l.width(), l.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        CardRenderer.hints(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, l.width(), l.height());

        Color bar = new Color(0xD9, 0xD9, 0xD9), line = new Color(0x77, 0x77, 0x77);
        g.setColor(bar);
        Box p = l.photo();
        int r = l.photoCornerRadius();
        g.fillRoundRect(p.x(), p.y(), p.w(), p.h(), 2 * r, 2 * r);
        for (TextBox t : List.of(l.accountName(), l.branch())) g.fillRect(t.x(), t.y(), t.w(), t.h());
        for (Slot s : l.signatureSlots()) g.fillRect(s.fullName().x(), s.fullName().y(), s.fullName().w(), s.fullName().h());

        g.setColor(line);
        g.setStroke(new BasicStroke(3f));
        Box c = l.individualCheckbox();
        g.drawRoundRect(c.x(), c.y(), c.w(), c.h(), 12, 12);
        for (Box b : l.accountNumberBoxes()) g.drawRoundRect(b.x(), b.y(), b.w(), b.h(), 10, 10);
        for (Slot s : l.signatureSlots()) g.drawRect(s.signature().x(), s.signature().y(), s.signature().w(), s.signature().h());

        g.setColor(new Color(0x33, 0x33, 0x33));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 56));
        g.drawString("Signature Card", 160, 200);
        g.setColor(new Color(200, 0, 0));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        g.drawString("PLACEHOLDER TEMPLATE - NOT FOR T24", 160, l.height() - 140);
        g.dispose();
        return img;
    }
}
