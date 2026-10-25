package ly.alwaha.card;

import ly.alwaha.card.Layout.Box;
import ly.alwaha.card.Layout.Slot;
import ly.alwaha.card.Layout.TextBox;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** Developer tools: the calibration overlay, and a stand-in template until the official one arrives. */
public final class TemplateTools {

    private TemplateTools() {}

    /** Draws every field box from layout.json over the template, so the positions can be checked by eye. */
    public static BufferedImage calibration(BufferedImage template, Layout l) {
        BufferedImage out = new BufferedImage(template.getWidth(), template.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        CardRenderer.hints(g);
        g.drawImage(template, 0, 0, null);
        g.setStroke(new BasicStroke(3f));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        mark(g, l.photo(), "photo");
        mark(g, l.individualCheckbox(), "individual");
        mark(g, l.accountName().box(), "accountName");
        mark(g, l.branch().box(), "branch");
        for (int i = 0; i < l.accountNumberBoxes().size(); i++) {
            mark(g, l.accountNumberBoxes().get(i), Integer.toString(i + 1));
        }
        for (int i = 0; i < l.signatureSlots().size(); i++) {
            Slot s = l.signatureSlots().get(i);
            mark(g, s.fullName().box(), "fullName " + (i + 1));
            mark(g, s.signature(), "signature " + (i + 1));
        }
        g.dispose();
        return out;
    }

    private static void mark(Graphics2D g, Box b, String label) {
        g.setColor(new Color(220, 0, 0));
        g.drawRect(b.x(), b.y(), b.w(), b.h());
        g.drawString(label, b.x() + 4, b.y() + 22);
    }

    /**
     * A plain stand-in card with grey bars and empty boxes at the layout positions.
     * Clearly watermarked: it must never be sent to T24.
     */
    public static BufferedImage placeholder(Layout l) {
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
