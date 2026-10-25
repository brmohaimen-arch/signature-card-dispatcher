package ly.alwaha.card;

import com.fasterxml.jackson.databind.ObjectMapper;
import ly.alwaha.card.Layout.Box;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CardRendererTest {

    @TempDir
    static Path tmp;
    static CardRenderer renderer;

    @BeforeAll
    static void setUp() throws Exception {
        renderer = TestSupport.renderer(tmp);
    }

    private static byte[] render(CardData data) throws Exception {
        BufferedImage card = renderer.render(data, TestSupport.selfie(), TestSupport.signature());
        return JpegWriter.write(card, 0.95f, 300);
    }

    @Test
    void rendersSampleCardForVisualCheck() throws Exception {
        byte[] jpg = render(new CardData(TestSupport.NAME, TestSupport.BRANCH, TestSupport.ACCOUNT));
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/sample-card.jpg"), jpg);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(jpg));
        assertEquals(1748, back.getWidth());
        assertEquals(2480, back.getHeight());
    }

    @Test
    void jpegIs444With300Dpi() throws Exception {
        byte[] jpg = render(new CardData(TestSupport.NAME, TestSupport.BRANCH, TestSupport.ACCOUNT));
        // APP0 JFIF: units=1 (dpi), Xdensity=300, Ydensity=300
        int app0 = indexOf(jpg, (byte) 0xFF, (byte) 0xE0);
        assertEquals("JFIF", new String(jpg, app0 + 4, 4));
        assertEquals(1, jpg[app0 + 11]);
        assertEquals(300, u16(jpg, app0 + 12));
        assertEquals(300, u16(jpg, app0 + 14));
        // SOF0: every component sampled 1x1, i.e. no chroma subsampling
        int sof = indexOf(jpg, (byte) 0xFF, (byte) 0xC0);
        int components = jpg[sof + 9];
        assertEquals(3, components);
        for (int c = 0; c < components; c++) {
            assertEquals(0x11, jpg[sof + 10 + c * 3 + 1] & 0xFF, "component " + c + " sampling");
        }
    }

    @Test
    void sameInputGivesIdenticalBytes() throws Exception {
        CardData d = new CardData(TestSupport.NAME, TestSupport.BRANCH, TestSupport.ACCOUNT);
        assertEquals(AuditLog.sha256(render(d)), AuditLog.sha256(render(d)));
    }

    @Test
    void signatureAppearsInAllFourSlotsWithoutStretching() throws Exception {
        BufferedImage card = renderer.render(new CardData(TestSupport.NAME, TestSupport.BRANCH, TestSupport.ACCOUNT),
                TestSupport.selfie(), TestSupport.signature());
        int[] ink = new int[4];
        for (int i = 0; i < 4; i++) {
            Box b = renderer.layout().signatureSlots().get(i).signature();
            ink[i] = darkPixels(card, b);
            assertTrue(ink[i] > 2000, "slot " + (i + 1) + " has signature ink");
        }
        // Same image in every slot: ink amounts are within 25% of each other (slot sizes differ slightly).
        for (int i = 1; i < 4; i++) {
            assertEquals(ink[0], ink[i], ink[0] * 0.25, "slot " + (i + 1) + " ink vs slot 1");
        }
    }

    @Test
    void greyPaperBecomesWhiteButInkIsUntouched() {
        BufferedImage sig = TestSupport.signature();
        BufferedImage clean = CardRenderer.whitenPaper(sig);
        assertEquals(0xFFFFFF, clean.getRGB(10, 10) & 0xFFFFFF, "paper corner is white");
        int changedInk = 0, ink = 0;
        for (int y = 0; y < sig.getHeight(); y++) {
            for (int x = 0; x < sig.getWidth(); x++) {
                int p = sig.getRGB(x, y) & 0xFFFFFF;
                if (p == 0x141E5A) { // the pen colour
                    ink++;
                    if ((clean.getRGB(x, y) & 0xFFFFFF) != p) changedInk++;
                }
            }
        }
        assertTrue(ink > 1000);
        assertEquals(0, changedInk, "no ink pixel altered");
    }

    @Test
    void individualIsTickedInsideItsBox() throws Exception {
        BufferedImage card = renderer.render(new CardData(TestSupport.NAME, TestSupport.BRANCH, TestSupport.ACCOUNT),
                TestSupport.selfie(), TestSupport.signature());
        assertTrue(darkPixels(card, renderer.layout().individualCheckbox()) > 200);
    }

    /** "1" has far less ink than "0", so the ink per box shows where each digit landed. */
    @Test
    void accountNumberReadsLeftToRightNeverFlipped() throws Exception {
        List<Box> boxes = renderer.layout().accountNumberBoxes();
        int last = boxes.size() - 1;
        String oneFirst = "1" + "0".repeat(last);   // 1000000000000
        String oneLast = "0".repeat(last) + "1";    // 0000000000001

        BufferedImage a = renderer.render(new CardData(TestSupport.NAME, TestSupport.BRANCH, oneFirst),
                TestSupport.selfie(), TestSupport.signature());
        BufferedImage b = renderer.render(new CardData(TestSupport.NAME, TestSupport.BRANCH, oneLast),
                TestSupport.selfie(), TestSupport.signature());

        assertTrue(darkPixels(a, boxes.get(0)) < darkPixels(a, boxes.get(last)), "first digit is in the leftmost box");
        assertTrue(darkPixels(b, boxes.get(0)) > darkPixels(b, boxes.get(last)), "last digit is in the rightmost box");
    }

    @Test
    void layoutWithBoxesOutOfOrderIsRefused() throws Exception {
        Path dir = tmp.resolve("bad-layout");
        Files.createDirectories(dir);
        Layout l = Layout.load(TestSupport.project().resolve("templates/BM-AC008-2026/layout.json"));
        List<Box> swapped = new ArrayList<>(l.accountNumberBoxes());
        Collections.swap(swapped, 0, 1);
        Layout bad = new Layout(l.templateId(), l.width(), l.height(), l.dpi(), l.photo(), l.photoCornerRadius(),
                l.individualCheckbox(), l.accountName(), swapped, l.branch(), l.signatureSlots());
        new ObjectMapper().writeValue(dir.resolve("layout.json").toFile(), bad);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Layout.load(dir.resolve("layout.json")));
        assertTrue(e.getMessage().contains("left to right"));
    }

    @Test
    void arabicIndicDigitsAndHiddenDirectionMarksAreRejected() {
        String last = "0".repeat(renderer.layout().accountNumberBoxes().size() - 1);
        for (String bad : new String[]{"٠" + last, "‏0" + last.substring(1) + "1", "‮" + last + "1"}) {
            assertThrows(RejectedException.class,
                    () -> render(new CardData(TestSupport.NAME, TestSupport.BRANCH, bad)), bad);
        }
    }

    @Test
    void rejectsWrongAccountNumbers() {
        for (String bad : new String[]{"001234567890", "00123456789012", "00123456789O1", ""}) {
            RejectedException e = assertThrows(RejectedException.class,
                    () -> render(new CardData(TestSupport.NAME, TestSupport.BRANCH, bad)));
            assertTrue(e.getMessage().contains("13 digits"), bad);
        }
    }

    @Test
    void rejectsMissingFields() {
        assertThrows(RejectedException.class, () -> render(new CardData(" ", TestSupport.BRANCH, TestSupport.ACCOUNT)));
        assertThrows(RejectedException.class, () -> render(new CardData(TestSupport.NAME, null, TestSupport.ACCOUNT)));
    }

    @Test
    void rejectsNameTooLongInsteadOfCuttingIt() {
        String longName = (TestSupport.NAME + " ").repeat(6).strip();
        RejectedException e = assertThrows(RejectedException.class,
                () -> render(new CardData(longName, TestSupport.BRANCH, TestSupport.ACCOUNT)));
        assertTrue(e.getMessage().contains("does not fit"));
    }

    @Test
    void exifOrientation6RotatesClockwise() {
        BufferedImage src = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, 0xFF0000); // top-left red
        BufferedImage out = ImageLoader.orient(src, 6);
        assertEquals(2, out.getWidth());
        assertEquals(3, out.getHeight());
        assertEquals(0xFF0000, out.getRGB(1, 0) & 0xFFFFFF); // red is now top-right
    }

    private static int darkPixels(BufferedImage img, Box b) {
        int n = 0;
        for (int y = b.y(); y < b.y() + b.h(); y++) {
            for (int x = b.x(); x < b.x() + b.w(); x++) {
                int rgb = img.getRGB(x, y);
                int lum = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
                if (lum < 100) n++;
            }
        }
        return n;
    }

    private static int indexOf(byte[] a, byte b0, byte b1) {
        for (int i = 0; i < a.length - 1; i++) if (a[i] == b0 && a[i + 1] == b1) return i;
        throw new AssertionError("marker not found");
    }

    private static int u16(byte[] a, int i) {
        return ((a[i] & 0xFF) << 8) | (a[i + 1] & 0xFF);
    }
}
