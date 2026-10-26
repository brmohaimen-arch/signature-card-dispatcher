package ly.alwaha.card;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Pixel positions of every field on the template, loaded from layout.json. */
public record Layout(
        String templateId,
        int width,
        int height,
        int dpi,
        Box photo,
        int photoCornerRadius,
        Box individualCheckbox,
        TextBox accountName,
        List<Box> accountNumberBoxes,
        TextBox branch,
        List<Slot> signatureSlots,
        Box authorizedSignature) {   // where the digital print goes; optional

    public record Box(int x, int y, int w, int h) {}

    /** align: "right" (default, Arabic), "left" or "center". */
    public record TextBox(int x, int y, int w, int h, int maxFontPx, int minFontPx, String align) {
        Box box() { return new Box(x, y, w, h); }
    }

    public record Slot(TextBox fullName, Box signature, int signaturePadding) {}

    public static Layout load(Path file) throws IOException {
        Layout l = new ObjectMapper().readValue(file.toFile(), Layout.class);
        if (l.accountNumberBoxes().isEmpty()) {
            throw new IllegalStateException("layout.json must define the account number boxes");
        }
        // Digit i goes into box i, so the boxes must run left to right or the number would print reversed.
        for (int i = 1; i < l.accountNumberBoxes().size(); i++) {
            if (l.accountNumberBoxes().get(i).x() <= l.accountNumberBoxes().get(i - 1).x()) {
                throw new IllegalStateException("layout.json accountNumberBoxes must be listed left to right (box "
                        + (i + 1) + " is not to the right of box " + i + ")");
            }
        }
        if (l.signatureSlots().isEmpty()) {
            throw new IllegalStateException("layout.json must define at least one signature slot");
        }
        return l;
    }
}
