package ly.alwaha.card;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the selfie and signature from the onboarding SFTP. READ ONLY: nothing on that server is written,
 * moved or deleted. Metadata first: folders are listed and the remote size is checked before anything is downloaded.
 *
 * The app's layout: {root}/{branch3}/{nationalId}_{accountNumber}_{traceNumber}/Profile.bmp + SignatureCard.bmp
 * The folder is found with source.folder.pattern ("*" = anything, e.g. the 8-character code); the newest match wins.
 */
public final class SourceImages {

    public record Fetched(Path selfie, Path signature, int matchingFolders) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9]+)}");
    private static final Pattern SAFE_EXT = Pattern.compile("[a-z0-9]{1,5}");

    private final Config.Source source;
    private final Config.ImageLimits limits;
    private final Quarantine quarantine;

    public SourceImages(Config.Source source, Config.ImageLimits limits, Quarantine quarantine) {
        this.source = source;
        this.limits = limits;
        this.quarantine = quarantine;
    }

    public boolean configured() {
        return source.configured();
    }

    /** Finds the customer's folder and downloads both images into stagingDir (our copies). Values must be validated. */
    public Fetched fetch(String requestId, Map<String, String> vars, Path stagingDir) throws IOException, RejectedException {
        try (SourceFiles c = source.local() ? LocalFolders.source() : SftpConnection.connect(source.sftp())) {
            String pattern = expand(source.folderPattern(), vars);
            int slash = pattern.lastIndexOf('/');
            String parent = join(source.effectiveRoot(), slash < 0 ? "" : pattern.substring(0, slash));
            Pattern folder = glob(pattern.substring(slash + 1));

            List<SourceFiles.Entry> matches = c.list(parent).stream()
                    .filter(e -> e.directory() && folder.matcher(e.name()).matches())
                    .sorted(Comparator.comparing(SourceFiles.Entry::modified).reversed())
                    .toList();
            // Not there (yet) is temporary: the app may still be uploading, so it can retry with the same requestId.
            if (matches.isEmpty()) throw new IOException("customer folder not found on the onboarding server (yet)");
            String dir = join(parent, matches.get(0).name());

            List<SourceFiles.Entry> files = c.list(dir);
            Path selfie = fetchOne(c, "selfie", join(dir, file(files, source.selfieFile(), "selfie")), requestId, stagingDir);
            Path signature = fetchOne(c, "signature", join(dir, file(files, source.signatureFile(), "signature")), requestId, stagingDir);
            return new Fetched(selfie, signature, matches.size());
        }
    }

    /** The file whose name without extension is base (case-insensitive), e.g. "Profile" → Profile.bmp. */
    private static String file(List<SourceFiles.Entry> files, String base, String kind) throws IOException {
        for (SourceFiles.Entry e : files) {
            if (e.directory()) continue;
            int dot = e.name().lastIndexOf('.');
            String stem = dot < 0 ? e.name() : e.name().substring(0, dot);
            if (stem.equalsIgnoreCase(base)) return e.name();
        }
        throw new IOException(kind + " (" + base + ".*) not found in the customer folder (yet)");
    }

    /** "*" matches anything; everything else is literal. */
    static Pattern glob(String g) {
        StringBuilder re = new StringBuilder();
        for (String part : g.split("\\*", -1)) {
            if (re.length() > 0 || g.startsWith("*")) re.append(".*");
            re.append(Pattern.quote(part));
        }
        return Pattern.compile(g.equals("*") ? ".*" : re.toString());
    }

    private static String join(String a, String b) {
        if (b.isEmpty()) return a;
        return a.endsWith("/") ? a + b : a + "/" + b;
    }

    private Path fetchOne(SourceFiles c, String kind, String remote, String requestId, Path dir)
            throws IOException, RejectedException {
        String name = kind + extension(remote);
        long size = c.size(remote);
        // Not there yet is temporary: the app can retry with the same requestId.
        if (size < 0) throw new IOException(kind + " image not found on the onboarding server");
        if (size == 0 || size > limits.maxBytes()) {
            Path enc;
            try (InputStream in = c.open(remote)) {
                enc = quarantine.encrypt(in, name, requestId);
            }
            throw new SuspiciousFileException(name,
                    size == 0 ? "file is empty" : "file is " + size + " bytes, limit is " + limits.maxBytes(), enc);
        }
        Files.createDirectories(dir);
        Path local = dir.resolve(name);
        try (InputStream in = c.open(remote); OutputStream out = Files.newOutputStream(local)) {
            copyAtMost(in, out, limits.maxBytes() + 1);
        }
        if (Files.size(local) > limits.maxBytes()) {
            Path enc = quarantine.encryptAndDelete(local, requestId);
            throw new SuspiciousFileException(name, "grew beyond " + limits.maxBytes() + " bytes while downloading", enc);
        }
        return local;
    }

    static String expand(String template, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value == null) throw new IllegalStateException("unknown placeholder {" + m.group(1) + "} in " + template);
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String extension(String remote) {
        String last = remote.substring(remote.lastIndexOf('/') + 1).toLowerCase();
        int dot = last.lastIndexOf('.');
        String ext = dot < 0 ? "" : last.substring(dot + 1);
        return SAFE_EXT.matcher(ext).matches() ? "." + ext : "";
    }

    private static void copyAtMost(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while (total < max && (n = in.read(buf, 0, (int) Math.min(buf.length, max - total))) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
    }
}
