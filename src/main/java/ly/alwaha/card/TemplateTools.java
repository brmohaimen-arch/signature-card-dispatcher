package ly.alwaha.card;

import ly.alwaha.card.Layout.Box;
import ly.alwaha.card.Layout.Slot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** The calibration overlay: every layout.json box drawn over the template, to check positions by eye. */
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
        if (l.authorizedSignature() != null) mark(g, l.authorizedSignature(), "digital print");
        g.dispose();
        return out;
    }

    private static void mark(Graphics2D g, Box b, String label) {
        g.setColor(new Color(220, 0, 0));
        g.drawRect(b.x(), b.y(), b.w(), b.h());
        g.drawString(label, b.x() + 4, b.y() + 22);
    }
}
