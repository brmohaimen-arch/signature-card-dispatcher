package ly.alwaha.card;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Settings from config/application.properties. Secrets are never in that file: SFTP_PASSWORD,
 * SFTP_KEY_PASSPHRASE, SOURCE_SFTP_PASSWORD, SOURCE_SFTP_KEY_PASSPHRASE, API_AUTH_VALUE and QUARANTINE_KEY
 * come from the environment or from the encrypted config/secrets.enc (see Secrets).
 */
public record Config(
        Path workDir,
        Path templateDir,
        Path fontRegular,
        Path fontBold,
        int pollSeconds,
        int stableSeconds,
        int maxAttempts,
        float jpegQuality,
        boolean allowPlaceholderTemplate,
        String sftpHost,
        int sftpPort,
        String sftpUser,
        String sftpRemoteDir,
        Path sftpKnownHosts,
        Path sftpKeyPath,
        String sftpPassword,
        String sftpKeyPassphrase,
        int sftpTimeoutSeconds,
        Api api,
        ImageLimits limits,
        String quarantineKey,
        Source source,
        Server server) {

    /**
     * The onboarding API lookup: identity → account details (account number, name, branch code).
     * Optional get-CIF call (off by default): CIF = first cifDigits of the account; name + branch then come from it.
     * The branch code is turned into the branch name with the branch table (config/branches.properties).
     * Response fields are picked with JSON pointers so they can follow the real response shape without code changes.
     */
    public record Api(
            String baseUrl,
            String accountPath,
            boolean cifEnabled,
            String cifPath,
            String accountNumberPointer,
            String fullNamePointer,
            String branchPointer,
            int cifDigits,
            String authHeader,
            String authValue,
            int timeoutSeconds,
            Map<String, String> branches) {}

    /** Checked from the file size and image header before any pixels are decoded. */
    public record ImageLimits(long maxBytes, int maxSide, long maxPixels) {}

    /** One SFTP server: T24's document repository, or the onboarding server the images are read from. */
    public record SftpEndpoint(String host, int port, String user, String remoteDir, Path knownHosts,
                               Path keyPath, String password, String keyPassphrase, int timeoutSeconds) {}

    /**
     * The onboarding SFTP the selfie and signature are READ from (never written or deleted).
     * Paths may use {requestId} {nationalId} {passportNumber} {phoneNumber} {accountNumber} {cif}.
     * Left empty until the bank gives the real layout; the endpoint then answers "not configured yet".
     */
    public record Source(SftpEndpoint sftp, String selfiePath, String signaturePath) {
        public boolean configured() {
            return !sftp.host().isEmpty() && !selfiePath.isEmpty() && !signaturePath.isEmpty();
        }
    }

    /** Our endpoint for the onboarding app (internal network, no key). enabled=false: requests get 503 DISABLED. */
    public record Server(String bind, int port, boolean enabled) {}

    /** Built-in values for settings missing from the file (the dashboard shows these as the values in effect). */
    public static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("api.base.url", ""),
            Map.entry("api.account.path", "/irf-itss-container/api/v1.0.0/holdings/customer/account/details/{nationalId}/{passportNumber}/{phoneNumber}"),
            Map.entry("api.cif.enabled", "false"),
            Map.entry("api.cif.path", ""),
            Map.entry("api.account.number.pointer", "/body/0/accountNumber"),
            Map.entry("api.name.pointer", "/body/0/accountName"),
            Map.entry("api.branch.pointer", "/body/0/branch"),
            Map.entry("api.cif.digits", "7"),
            Map.entry("api.auth.header", ""),
            Map.entry("api.timeout.seconds", "45"),
            Map.entry("branches.file", "config/branches.properties"),
            Map.entry("endpoint.enabled", "true"),
            Map.entry("image.max.bytes", "15000000"),
            Map.entry("image.max.side", "10000"),
            Map.entry("image.max.pixels", "50000000"),
            Map.entry("source.sftp.host", ""),
            Map.entry("source.sftp.port", "22"),
            Map.entry("source.sftp.user", ""),
            Map.entry("source.sftp.known.hosts", "config/source_known_hosts"),
            Map.entry("source.sftp.key.path", ""),
            Map.entry("source.sftp.timeout.seconds", "30"),
            Map.entry("source.selfie.path", ""),
            Map.entry("source.signature.path", ""),
            Map.entry("work.dir", "work"),
            Map.entry("template.dir", "templates/BM-AC008-2026"),
            Map.entry("font.regular", "fonts/Almarai-Regular.ttf"),
            Map.entry("font.bold", "fonts/Almarai-Bold.ttf"),
            Map.entry("poll.seconds", "30"),
            Map.entry("input.stable.seconds", "10"),
            Map.entry("dispatch.max.attempts", "5"),
            Map.entry("jpeg.quality", "0.95"),
            Map.entry("allow.placeholder.template", "false"),
            Map.entry("sftp.host", ""),
            Map.entry("sftp.port", "22"),
            Map.entry("sftp.user", ""),
            Map.entry("sftp.remote.dir", ""),
            Map.entry("sftp.known.hosts", "config/known_hosts"),
            Map.entry("sftp.key.path", ""),
            Map.entry("sftp.timeout.seconds", "30"),
            Map.entry("server.bind", "0.0.0.0"),
            Map.entry("server.port", "8085"));

    private static Properties defaults() {
        Properties d = new Properties();
        d.putAll(DEFAULTS);
        return d;
    }

    public static Config load(Path file) throws IOException {
        return load(file, Secrets.forConfig(file));
    }

    public static Config load(Path file, Secrets secrets) throws IOException {
        Properties p = new Properties(defaults());
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        Api api = new Api(
                p.getProperty("api.base.url").trim(),
                p.getProperty("api.account.path").trim(),
                bool(p, "api.cif.enabled"),
                p.getProperty("api.cif.path").trim(),
                p.getProperty("api.account.number.pointer").trim(),
                p.getProperty("api.name.pointer").trim(),
                p.getProperty("api.branch.pointer").trim(),
                Integer.parseInt(p.getProperty("api.cif.digits")),
                p.getProperty("api.auth.header").trim(),
                secrets.get("API_AUTH_VALUE"),
                Integer.parseInt(p.getProperty("api.timeout.seconds")),
                loadBranches(Path.of(p.getProperty("branches.file").trim())));
        ImageLimits limits = new ImageLimits(
                Long.parseLong(p.getProperty("image.max.bytes")),
                Integer.parseInt(p.getProperty("image.max.side")),
                Long.parseLong(p.getProperty("image.max.pixels")));
        Source source = new Source(
                new SftpEndpoint(
                        p.getProperty("source.sftp.host").trim(),
                        Integer.parseInt(p.getProperty("source.sftp.port")),
                        p.getProperty("source.sftp.user").trim(),
                        "",
                        Path.of(p.getProperty("source.sftp.known.hosts")),
                        optionalPath(p.getProperty("source.sftp.key.path")),
                        secrets.get("SOURCE_SFTP_PASSWORD"),
                        secrets.get("SOURCE_SFTP_KEY_PASSPHRASE"),
                        Integer.parseInt(p.getProperty("source.sftp.timeout.seconds"))),
                p.getProperty("source.selfie.path").trim(),
                p.getProperty("source.signature.path").trim());
        return new Config(
                Path.of(p.getProperty("work.dir")),
                Path.of(p.getProperty("template.dir")),
                Path.of(p.getProperty("font.regular")),
                Path.of(p.getProperty("font.bold")),
                Integer.parseInt(p.getProperty("poll.seconds")),
                Integer.parseInt(p.getProperty("input.stable.seconds")),
                Integer.parseInt(p.getProperty("dispatch.max.attempts")),
                Float.parseFloat(p.getProperty("jpeg.quality")),
                Boolean.parseBoolean(p.getProperty("allow.placeholder.template")),
                p.getProperty("sftp.host").trim(),
                Integer.parseInt(p.getProperty("sftp.port")),
                p.getProperty("sftp.user").trim(),
                p.getProperty("sftp.remote.dir").trim(),
                Path.of(p.getProperty("sftp.known.hosts")),
                optionalPath(p.getProperty("sftp.key.path")),
                secrets.get("SFTP_PASSWORD"),
                secrets.get("SFTP_KEY_PASSPHRASE"),
                Integer.parseInt(p.getProperty("sftp.timeout.seconds")),
                api,
                limits,
                secrets.get("QUARANTINE_KEY"),
                source,
                new Server(p.getProperty("server.bind").trim(),
                        Integer.parseInt(p.getProperty("server.port")),
                        bool(p, "endpoint.enabled")));
    }

    private static boolean bool(Properties p, String key) {
        String v = p.getProperty(key).trim();
        if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(v);
    }

    /** code=name lines (UTF-8), edited from the dashboard. A missing file means an empty table. */
    public static Map<String, String> loadBranches(Path file) throws IOException {
        if (!Files.exists(file)) return Map.of();
        Properties b = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            b.load(r);
        }
        Map<String, String> out = new java.util.TreeMap<>();
        for (String code : b.stringPropertyNames()) out.put(code.strip(), b.getProperty(code).strip());
        return Map.copyOf(out);
    }

    /** The T24 document repository, from the sftp.* settings. */
    public SftpEndpoint t24Sftp() {
        return new SftpEndpoint(sftpHost, sftpPort, sftpUser, sftpRemoteDir, sftpKnownHosts,
                sftpKeyPath, sftpPassword, sftpKeyPassphrase, sftpTimeoutSeconds);
    }

    private static Path optionalPath(String s) {
        return s.isBlank() ? null : Path.of(s.trim());
    }

    public Path inbox()      { return workDir.resolve("inbox"); }
    public Path staging()    { return workDir.resolve("staging"); }
    public Path requests()   { return workDir.resolve("requests"); }
    public Path processed()  { return workDir.resolve("processed"); }
    public Path outbox()     { return workDir.resolve("outbox"); }
    public Path archive()    { return workDir.resolve("archive"); }
    public Path failed()     { return workDir.resolve("failed"); }
    public Path quarantine() { return workDir.resolve("quarantine"); }
}
