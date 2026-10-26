package ly.alwaha.card;

import ly.alwaha.card.Layout.Box;
import ly.alwaha.card.Layout.Slot;
import ly.alwaha.card.Layout.TextBox;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fills the official Signature Card template with one customer's data. */
public final class CardRenderer {

    private static final Color INK = new Color(0x1A, 0x1A, 0x1A);
    private static final int TEXT_PADDING = 12;

    private final BufferedImage template;
    private final Layout layout;
    private final Font regular;
    private final Font bold;

    public CardRenderer(Path templateDir, Path regularFont, Path boldFont) throws IOException {
        this.layout = Layout.load(templateDir.resolve("layout.json"));
        this.template = ImageLoader.toRgb(readTemplate(templateDir));
        if (template.getWidth() != layout.width() || template.getHeight() != layout.height()) {
            throw new IllegalStateException("template is " + template.getWidth() + "x" + template.getHeight()
                    + " but layout.json expects " + layout.width() + "x" + layout.height());
        }
        this.regular = font(regularFont);
        this.bold = font(boldFont);
    }

    public Layout layout() { return layout; }

    public BufferedImage template() { return template; }

    /** The visible digital print in the Authorized Signature box: when the card was made and by which request. */
    public record DigitalPrint(java.util.UUID requestId, java.time.ZonedDateTime at) {}

    /** Without a digital print (previews and tests). */
    public BufferedImage render(CardData data, BufferedImage selfie, BufferedImage signature) throws RejectedException {
        return render(data, selfie, signature, null);
    }

    public BufferedImage render(CardData data, BufferedImage selfie, BufferedImage signature, DigitalPrint print)
            throws RejectedException {
        data.validate(layout.accountNumberBoxes().size());
        BufferedImage card = copy(template);
        Graphics2D g = card.createGraphics();
        try {
            hints(g);
            drawPhoto(g, selfie);
            drawTick(g, layout.individualCheckbox());
            drawText(g, "fullName (ACC Name)", data.fullName(), layout.accountName(), bold);
            drawDigits(g, data.accountNumber());
            drawText(g, "branch", data.branch(), layout.branch(), bold);
            for (Slot slot : layout.signatureSlots()) {
                drawText(g, "fullName", data.fullName(), slot.fullName(), regular);
            }
            if (print != null && layout.authorizedSignature() != null) drawDigitalPrint(g, print, layout.authorizedSignature());
        } finally {
            g.dispose();
        }
        BufferedImage cleanSignature = whitenPaper(signature);
        for (Slot slot : layout.signatureSlots()) {
            drawSignature(card, cleanSignature, slot.signature(), slot.signaturePadding());
        }
        return card;
    }

    /**
     * Photographed paper is never pure white, so it would show as a grey patch on the card.
     * Pixels at the paper's own brightness become white. Darker pixels (the ink) are left exactly as they are.
     */
    static BufferedImage whitenPaper(BufferedImage sig) {
        int w = sig.getWidth(), h = sig.getHeight();
        int[] px = sig.getRGB(0, 0, w, h, null, 0, w);
        int[] histogram = new int[256];
        for (int p : px) histogram[luminance(p)]++;
        // Paper brightness = the 80th percentile: a signature is mostly paper with a little ink.
        int paper = 0;
        for (int acc = 0, target = (int) (px.length * 0.80); paper < 255; paper++) {
            acc += histogram[paper];
            if (acc >= target) break;
        }
        int threshold = Math.max(paper - 30, 128);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < px.length; i++) {
            if (luminance(px[i]) >= threshold) px[i] = 0xFFFFFF;
        }
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    private static int luminance(int rgb) {
        return ((rgb >> 16 & 0xFF) * 299 + (rgb >> 8 & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    // Photo: fill the box (cover), centre-crop, clip to rounded corners. Scaled only, never retouched.
    private void drawPhoto(Graphics2D g, BufferedImage selfie) {
        Box b = layout.photo();
        double s = Math.max((double) b.w() / selfie.getWidth(), (double) b.h() / selfie.getHeight());
        int tw = (int) Math.ceil(selfie.getWidth() * s), th = (int) Math.ceil(selfie.getHeight() * s);
        BufferedImage scaled = scale(selfie, tw, th);
        Shape oldClip = g.getClip();
        int r = layout.photoCornerRadius();
        g.setClip(new RoundRectangle2D.Double(b.x(), b.y(), b.w(), b.h(), 2.0 * r, 2.0 * r));
        g.drawImage(scaled, b.x() + (b.w() - tw) / 2, b.y() + (b.h() - th) / 2, null);
        g.setClip(oldClip);
    }

    /**
     * Four centred lines inside the box: a label, the date/time (Tripoli) and the request UUID split in two halves
     * (36 characters on one line would be too small to read). The UUID is also in the JPEG metadata.
     */
    private void drawDigitalPrint(Graphics2D g, DigitalPrint p, Box b) {
        String id = p.requestId().toString();
        String[] lines = {
                "توقيع رقمي · Digital onboarding",
                p.at().withZoneSameInstant(java.time.ZoneId.of("Africa/Tripoli"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " (Tripoli)",
                "Ref " + id.substring(0, 18),
                id.substring(18)};
        int pad = 14;
        float lineHeight = (b.h() - 2f * pad) / lines.length;
        g.setColor(INK);
        for (int i = 0; i < lines.length; i++) {
            Font f = (i == 0 ? bold : regular).deriveFont(Math.min(lineHeight * 0.62f, 34f));
            TextLayout tl = new TextLayout(lines[i], f, g.getFontRenderContext());
            while (tl.getAdvance() > b.w() - 2 * pad && f.getSize2D() > 10f) {
                f = f.deriveFont(f.getSize2D() - 1f);
                tl = new TextLayout(lines[i], f, g.getFontRenderContext());
            }
            float baseline = b.y() + pad + lineHeight * i + (lineHeight + tl.getAscent() - tl.getDescent()) / 2f;
            tl.draw(g, b.x() + (b.w() - tl.getAdvance()) / 2f, baseline);
        }
    }

    private static void drawTick(Graphics2D g, Box b) {
        Path2D tick = new Path2D.Double();
        tick.moveTo(b.x() + b.w() * 0.20, b.y() + b.h() * 0.55);
        tick.lineTo(b.x() + b.w() * 0.42, b.y() + b.h() * 0.78);
        tick.lineTo(b.x() + b.w() * 0.82, b.y() + b.h() * 0.24);
        g.setColor(INK);
        g.setStroke(new BasicStroke(Math.max(3f, b.w() * 0.12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(tick);
    }

    /** Shrinks the font until the text fits; below the minimum the card is rejected, never truncated. */
    private static void drawText(Graphics2D g, String field, String text, TextBox tb, Font base) throws RejectedException {
        if (base.canDisplayUpTo(text) != -1) {
            throw new RejectedException(field + " contains characters the card font cannot print");
        }
        int room = tb.w() - 2 * TEXT_PADDING;
        for (int px = tb.maxFontPx(); px >= tb.minFontPx(); px--) {
            TextLayout tl = new TextLayout(text, base.deriveFont((float) px), g.getFontRenderContext());
            float advance = tl.getAdvance();
            if (advance > room || tl.getAscent() + tl.getDescent() > tb.h()) continue;
            float x = switch (tb.align() == null ? "right" : tb.align()) {
                case "left" -> tb.x() + TEXT_PADDING;
                case "center" -> tb.x() + (tb.w() - advance) / 2f;
                default -> tb.x() + tb.w() - TEXT_PADDING - advance;
            };
            float baseline = tb.y() + (tb.h() + tl.getAscent() - tl.getDescent()) / 2f;
            g.setColor(INK);
            tl.draw(g, x, baseline);
            return;
        }
        throw new RejectedException(field + " does not fit its box even at the minimum font size");
    }

    private void drawDigits(Graphics2D g, String digits) {
        for (int i = 0; i < digits.length(); i++) {
            Box b = layout.accountNumberBoxes().get(i);
            Font f = bold.deriveFont(b.h() * 0.62f);
            TextLayout tl = new TextLayout(digits.substring(i, i + 1), f, g.getFontRenderContext());
            g.setColor(INK);
            tl.draw(g, b.x() + (b.w() - tl.getAdvance()) / 2f,
                    b.y() + (b.h() + tl.getAscent() - tl.getDescent()) / 2f);
        }
    }

    /**
     * Signature: fit inside the box (never stretched or cropped), centred.
     * Multiply blend: the white paper disappears into the box, and the ink pixels are kept as they are.
     */
    private static void drawSignature(BufferedImage card, BufferedImage sig, Box b, int pad) {
        int aw = b.w() - 2 * pad, ah = b.h() - 2 * pad;
        double s = Math.min((double) aw / sig.getWidth(), (double) ah / sig.getHeight());
        int tw = Math.max(1, (int) Math.floor(sig.getWidth() * s));
        int th = Math.max(1, (int) Math.floor(sig.getHeight() * s));
        BufferedImage scaled = scale(sig, tw, th);
        int ox = b.x() + (b.w() - tw) / 2, oy = b.y() + (b.h() - th) / 2;
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int c = card.getRGB(ox + x, oy + y), s2 = scaled.getRGB(x, y);
                int r = ((c >> 16 & 0xFF) * (s2 >> 16 & 0xFF)) / 255;
                int gr = ((c >> 8 & 0xFF) * (s2 >> 8 & 0xFF)) / 255;
                int bl = ((c & 0xFF) * (s2 & 0xFF)) / 255;
                card.setRGB(ox + x, oy + y, (r << 16) | (gr << 8) | bl);
            }
        }
    }

    /** High-quality resize: halve step by step when shrinking a lot, then one bicubic pass. */
    static BufferedImage scale(BufferedImage src, int tw, int th) {
        BufferedImage cur = src;
        int w = src.getWidth(), h = src.getHeight();
        while (w / 2 >= tw && h / 2 >= th) {
            w /= 2;
            h /= 2;
            cur = resize(cur, w, h);
        }
        return (w == tw && h == th) ? cur : resize(cur, tw, th);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        hints(g);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage readTemplate(Path dir) throws IOException {
        for (String name : new String[]{"template.png", "template.jpg"}) {
            Path p = dir.resolve(name);
            if (Files.exists(p)) {
                BufferedImage img = ImageIO.read(p.toFile());
                if (img == null) throw new IOException("cannot read " + p);
                return img;
            }
        }
        throw new IOException("no template.png or template.jpg in " + dir);
    }

    private static Font font(Path file) throws IOException {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, file.toFile());
        } catch (FontFormatException e) {
            throw new IOException("bad font file " + file, e);
        }
    }
}
