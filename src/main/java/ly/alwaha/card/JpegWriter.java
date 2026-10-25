package ly.alwaha.card;

import org.w3c.dom.NodeList;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;

/**
 * JPEG with no chroma subsampling (4:4:4) and the DPI in the JFIF header.
 * 4:4:4 keeps thin signature strokes and small Arabic text free of colour fringes.
 */
public final class JpegWriter {

    private static final String FORMAT = "javax_imageio_jpeg_image_1.0";

    private JpegWriter() {}

    public static byte[] write(BufferedImage img, float quality, int dpi) throws IOException {
        ImageWriter writer = jdkWriter();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            IIOMetadata md = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
            IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(FORMAT);
            IIOMetadataNode jfif = (IIOMetadataNode) root.getElementsByTagName("app0JFIF").item(0);
            jfif.setAttribute("resUnits", "1"); // dots per inch
            jfif.setAttribute("Xdensity", Integer.toString(dpi));
            jfif.setAttribute("Ydensity", Integer.toString(dpi));
            NodeList comps = root.getElementsByTagName("componentSpec");
            for (int i = 0; i < comps.getLength(); i++) {
                IIOMetadataNode c = (IIOMetadataNode) comps.item(i);
                c.setAttribute("HsamplingFactor", "1");
                c.setAttribute("VsamplingFactor", "1");
            }
            md.setFromTree(FORMAT, root);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(img, null, md), param);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * Same as write(), with the request UUID stored as the EXIF ImageUniqueID: the card's digital fingerprint.
     * EXIF expects 32 hex characters, so the UUID is stored without hyphens.
     */
    public static byte[] write(BufferedImage img, float quality, int dpi, UUID requestId) throws IOException {
        byte[] jpg = write(img, quality, dpi);
        if (requestId == null) return jpg;
        // Splice an APP1 (Exif) segment right after APP0 (JFIF), which must stay first.
        if ((jpg[2] & 0xFF) != 0xFF || (jpg[3] & 0xFF) != 0xE0) throw new IOException("unexpected JPEG layout");
        int app0End = 4 + (((jpg[4] & 0xFF) << 8) | (jpg[5] & 0xFF));
        byte[] app1 = exifImageUniqueId(requestId.toString().replace("-", ""));
        byte[] out = new byte[jpg.length + app1.length];
        System.arraycopy(jpg, 0, out, 0, app0End);
        System.arraycopy(app1, 0, out, app0End, app1.length);
        System.arraycopy(jpg, app0End, out, app0End + app1.length, jpg.length - app0End);
        return out;
    }

    /** A minimal big-endian EXIF block: IFD0 → Exif IFD → ImageUniqueID (tag 0xA420, ASCII, 33 bytes). */
    private static byte[] exifImageUniqueId(String hex32) {
        ByteBuffer tiff = ByteBuffer.allocate(77).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);         // header, IFD0 at 8
        tiff.putShort((short) 1)                                                      // IFD0: 1 entry
                .putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(26)   // ExifIFD pointer → 26
                .putInt(0);                                                           // no next IFD
        tiff.putShort((short) 1)                                                      // Exif IFD: 1 entry
                .putShort((short) 0xA420).putShort((short) 2).putInt(33).putInt(44)  // ImageUniqueID → 44
                .putInt(0);
        tiff.put(hex32.getBytes(StandardCharsets.US_ASCII)).put((byte) 0);
        byte[] body = tiff.array();
        ByteBuffer seg = ByteBuffer.allocate(4 + 6 + body.length).order(ByteOrder.BIG_ENDIAN);
        seg.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + body.length));
        seg.put("Exif".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 0).put(body);
        return seg.array();
    }

    /** Prefer the JDK's own writer so the metadata format above is guaranteed. */
    private static ImageWriter jdkWriter() {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter first = null;
        while (it.hasNext()) {
            ImageWriter w = it.next();
            if (w.getClass().getName().equals("com.sun.imageio.plugins.jpeg.JPEGImageWriter")) return w;
            if (first == null) first = w;
        }
        if (first == null) throw new IllegalStateException("No JPEG writer available");
        return first;
    }
}
