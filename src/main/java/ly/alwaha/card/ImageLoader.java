package ly.alwaha.card;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Reads phone images safely: metadata first, pixels last.
 *   1. file size (no bytes read)            → empty or over the limit = suspicious
 *   2. image header only (format, width, height) → unknown format or huge dimensions = suspicious
 *   3. only then read the content (as base64, a bank requirement), decode the pixels and apply the EXIF rotation
 * A file that fails 1 or 2 is suspicious: the caller encrypts it into quarantine and deletes it.
 * A file that claims to be small but expands into a huge image (a "decompression bomb") is caught at step 2.
 */
public final class ImageLoader {

    static final int MIN_SHORT_SIDE = 300;
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "jpg", "png", "webp");

    private ImageLoader() {}

    /** Default limits, for tools and tests. */
    public static BufferedImage load(Path file) throws IOException, RejectedException {
        return load(file, new Config.ImageLimits(15_000_000, 10_000, 50_000_000));
    }

    public static BufferedImage load(Path file, Config.ImageLimits limits) throws IOException, RejectedException {
        String name = file.getFileName().toString();

        long size = Files.size(file);
        if (size == 0) throw new SuspiciousFileException(file, "file is empty");
        if (size > limits.maxBytes()) {
            throw new SuspiciousFileException(file, "file is " + size + " bytes, limit is " + limits.maxBytes());
        }

        try (ImageInputStream header = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(header);
            if (!readers.hasNext()) {
                throw new SuspiciousFileException(file, "not a JPEG, PNG or WebP image (HEIC is not supported)");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!ALLOWED_FORMATS.contains(format)) throw new SuspiciousFileException(file, "format " + format + " is not allowed");
                reader.setInput(header, true, true);
                long w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > limits.maxSide() || h > limits.maxSide() || w * h > limits.maxPixels()) {
                    throw new SuspiciousFileException(file, "header says " + w + "x" + h + " pixels, over the limit");
                }
            } catch (IOException | RuntimeException e) {
                throw new SuspiciousFileException(file, "unreadable image header: " + e.getMessage());
            } finally {
                reader.dispose();
            }
        }

        // Bank requirement: once the metadata has passed, the content is handled as base64 and decoded
        // from it for use. (base64 adds no protection by itself; the checks above do that.)
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        byte[] bytes = Base64.getDecoder().decode(base64);
        BufferedImage img;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                img = reader.read(0);
            } catch (IOException | RuntimeException e) {
                throw new SuspiciousFileException(file, "header looked valid but the image could not be decoded: " + e.getMessage());
            } finally {
                reader.dispose();
            }
        }
        img = toRgb(orient(img, exifOrientation(bytes)));
        if (Math.min(img.getWidth(), img.getHeight()) < MIN_SHORT_SIDE) {
            throw new RejectedException(name + " is too small (" + img.getWidth() + "x" + img.getHeight()
                    + ", minimum " + MIN_SHORT_SIDE + " px on the short side)");
        }
        return img;
    }

    static int exifOrientation(byte[] bytes) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory dir = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
            // No readable EXIF: treat as upright.
        }
        return 1;
    }

    /** Applies EXIF orientation 1-8 by remapping pixels; no resampling. */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) return src;
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = orientation >= 5;
        int dw = swap ? h : w, dh = swap ? w : h;
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx, dy;
                switch (orientation) {
                    case 2 -> { dx = w - 1 - x; dy = y; }             // mirror horizontal
                    case 3 -> { dx = w - 1 - x; dy = h - 1 - y; }     // rotate 180
                    case 4 -> { dx = x; dy = h - 1 - y; }             // mirror vertical
                    case 5 -> { dx = y; dy = x; }                     // transpose
                    case 6 -> { dx = h - 1 - y; dy = x; }             // rotate 90 CW
                    case 7 -> { dx = h - 1 - y; dy = w - 1 - x; }     // transverse
                    default -> { dx = y; dy = w - 1 - x; }            // 8: rotate 90 CCW
                }
                out[dy * dw + dx] = in[y * w + x];
            }
        }
        BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
        dst.setRGB(0, 0, dw, dh, out, 0, dw);
        return dst;
    }

    /** Flattens onto white so transparent PNGs don't turn black. */
    static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }
}
