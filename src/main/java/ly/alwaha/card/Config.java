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
        boolean testProfile,
        Path workDir,
        Path templateDir,
        Path fontRegular,
        Path fontBold,
        int pollSeconds,
        int maxAttempts,
        float jpegQuality,
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
        Server server,
        LocalTarget t24Local) {

    /** True if any side runs in LOCAL TEST MODE (shown as a banner in the dashboard). */
    public boolean localTestMode() {
        return localTestMode(api, source, t24Local);
    }

    private static boolean localTestMode(Api api, Source source, LocalTarget t24Local) {
        return (api != null && api.local() != null) || (source != null && source.local()) || t24Local != null;
    }

    /**
     * profile=production (default) refuses every local mode, so production can never be switched to test folders/files.
     * profile=test allows them; the dashboard then shows a permanent TEST PROFILE banner.
     */
    public Config {
        if (!testProfile && localTestMode(api, source, t24Local)) {
            throw new IllegalArgumentException("profile=production does not allow local test modes "
                    + "(api.mode / source.mode / t24.mode must be real / sftp / sftp); use a separate profile=test config");
        }
    }

    /**
     * The onboarding API:
     *   account details by identity → account number, name, branch code
     *   getBranches                 → every branch's code (companyId) and English name (displayName)
     * The card's branch name is the Arabic name saved in the branch table (config/branches.properties);
     * if a branch has none yet, the English name from getBranches is used.
     * Response fields are picked with JSON pointers / field names so they follow the real response without code changes.
     */
    public record Api(
            String baseUrl,
            String accountPath,
            String accountNumberPointer,
            String fullNamePointer,
            String branchPointer,
            String branchesPath,
            String branchesListPointer,
            String branchCodeField,
            String branchNameField,
            String authHeader,
            String authValue,
            int timeoutSeconds,
            Map<String, String> branches,
            LocalApi local) {}

    /**
     * LOCAL TEST MODE for the onboarding API (api.mode=local): answers come from two files instead of the network.
     * customersFile: [{"nationalId","passportNumber","phoneNumber","response": <a pasted account-details response>}]
     * branchesFile:  a pasted getBranches response. null = real API (api.mode=real).
     */
    public record LocalApi(Path customersFile, Path branchesFile) {}

    /** LOCAL TEST MODE for delivery (t24.mode=local): cards go to this folder instead of the T24 SFTP. null = SFTP. */
    public record LocalTarget(Path dir) {}

    /** Checked from the file size and image header before any pixels are decoded. */
    public record ImageLimits(long maxBytes, int maxSide, long maxPixels) {}

    /** One SFTP server: T24's document repository, or the onboarding server the images are read from. */
    public record SftpEndpoint(String host, int port, String user, String remoteDir, Path knownHosts,
                               Path keyPath, String password, String keyPassphrase, int timeoutSeconds) {}

    /**
     * The onboarding SFTP the selfie and signature are READ from (never written or deleted). The app's layout:
     *   {root}/{branch3}/{nationalId}_{accountNumber}_{trace number}[_OpenNewBankAccount]/Profile.bmp, SignatureCard.bmp, …
     * folderPattern (under root) may use {branch3} (last 3 characters of the branch code), {nationalId},
     * {accountNumber}, {passportNumber}, {phoneNumber}, {requestId}, and * as a wildcard in its LAST part.
     * If several folders match, the newest is used. selfieFile / signatureFile are base names (any extension).
     */
    public record Source(SftpEndpoint sftp, String root, String folderPattern, String selfieFile, String signatureFile,
                         String localRoot) {
        /** LOCAL TEST MODE (source.mode=local): read from the local folder localRoot instead of the SFTP. */
        public boolean local() {
            return localRoot != null;
        }

        /** The uploads root actually used: the local folder in test mode, otherwise the SFTP path. */
        public String effectiveRoot() {
            return local() ? localRoot : root;
        }

        public boolean configured() {
            return (local() ? !localRoot.isEmpty() : !sftp.host().isEmpty() && !root.isEmpty())
                    && !folderPattern.isEmpty() && !selfieFile.isEmpty() && !signatureFile.isEmpty();
        }
    }

    /** Our endpoint for the onboarding app (internal network, no key). enabled=false: requests get 503 DISABLED. */
    public record Server(String bind, int port, boolean enabled) {}

    /** Built-in values for settings missing from the file (the dashboard shows these as the values in effect). */
    public static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("api.base.url", ""),
            Map.entry("api.account.path", "/irf-itss-container/api/v1.0.0/holdings/customer/account/details/{nationalId}/{passportNumber}/{phoneNumber}"),
            Map.entry("api.account.number.pointer", "/body/0/accountNumber"),
            Map.entry("api.name.pointer", "/body/0/accountName"),
            Map.entry("api.branch.pointer", "/body/0/branch"),
            Map.entry("api.branches.path", "/irf-itss-container/api/v1.0.0/holdings/getBranches"),
            Map.entry("api.branches.list.pointer", "/body"),
            Map.entry("api.branches.code.field", "companyId"),
            Map.entry("api.branches.name.field", "displayName"),
            Map.entry("api.auth.header", ""),
            Map.entry("api.timeout.seconds", "45"),
            Map.entry("branches.file", "config/branches.properties"),
            Map.entry("endpoint.enabled", "true"),
            Map.entry("api.mode", "real"),
            Map.entry("api.local.customers.file", "config/test-customers.json"),
            Map.entry("api.local.branches.file", "config/test-branches.json"),
            Map.entry("source.mode", "sftp"),
            Map.entry("source.local.root", ""),
            Map.entry("t24.mode", "sftp"),
            Map.entry("t24.local.dir", ""),
            Map.entry("image.max.bytes", "40000000"),
            Map.entry("image.max.side", "10000"),
            Map.entry("image.max.pixels", "50000000"),
            Map.entry("source.sftp.host", ""),
            Map.entry("source.sftp.port", "22"),
            Map.entry("source.sftp.user", ""),
            Map.entry("source.sftp.known.hosts", "config/source_known_hosts"),
            Map.entry("source.sftp.key.path", ""),
            Map.entry("source.sftp.timeout.seconds", "30"),
            Map.entry("source.root", ""),
            Map.entry("source.folder.pattern", "{branch3}/{nationalId}_{accountNumber}_*"),
            Map.entry("source.selfie.file", "Profile"),
            Map.entry("source.signature.file", "SignatureCard"),
            Map.entry("work.dir", "work"),
            Map.entry("template.dir", "templates/BM-AC008-2026"),
            Map.entry("font.regular", "fonts/Almarai-Regular.ttf"),
            Map.entry("font.bold", "fonts/Almarai-Bold.ttf"),
            Map.entry("poll.seconds", "30"),
            Map.entry("dispatch.max.attempts", "5"),
            Map.entry("jpeg.quality", "0.95"),
            Map.entry("profile", "production"),
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
                p.getProperty("api.account.number.pointer").trim(),
                p.getProperty("api.name.pointer").trim(),
                p.getProperty("api.branch.pointer").trim(),
                p.getProperty("api.branches.path").trim(),
                p.getProperty("api.branches.list.pointer").trim(),
                p.getProperty("api.branches.code.field").trim(),
                p.getProperty("api.branches.name.field").trim(),
                p.getProperty("api.auth.header").trim(),
                secrets.get("API_AUTH_VALUE"),
                Integer.parseInt(p.getProperty("api.timeout.seconds")),
                loadBranches(Path.of(p.getProperty("branches.file").trim())),
                mode(p, "api.mode", "real")
                        ? null
                        : new LocalApi(Path.of(p.getProperty("api.local.customers.file").trim()),
                                       Path.of(p.getProperty("api.local.branches.file").trim())));
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
                p.getProperty("source.root").trim(),
                p.getProperty("source.folder.pattern").trim(),
                p.getProperty("source.selfie.file").trim(),
                p.getProperty("source.signature.file").trim(),
                mode(p, "source.mode", "sftp") ? null : p.getProperty("source.local.root").trim());
        String profile = p.getProperty("profile").trim();
        if (!profile.equals("production") && !profile.equals("test")) {
            throw new IllegalArgumentException("profile must be production or test");
        }
        return new Config(
                profile.equals("test"),
                Path.of(p.getProperty("work.dir")),
                Path.of(p.getProperty("template.dir")),
                Path.of(p.getProperty("font.regular")),
                Path.of(p.getProperty("font.bold")),
                Integer.parseInt(p.getProperty("poll.seconds")),
                Integer.parseInt(p.getProperty("dispatch.max.attempts")),
                Float.parseFloat(p.getProperty("jpeg.quality")),
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
                        bool(p, "endpoint.enabled")),
                mode(p, "t24.mode", "sftp") ? null : new LocalTarget(Path.of(p.getProperty("t24.local.dir").trim())));
    }

    /** true = the production value (real / sftp), false = local; anything else is refused. */
    private static boolean mode(Properties p, String key, String production) {
        String v = p.getProperty(key).trim();
        if (v.equals(production)) return true;
        if (v.equals("local")) return false;
        throw new IllegalArgumentException(key + " must be " + production + " or local");
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

    public Path staging()    { return workDir.resolve("staging"); }
    public Path requests()   { return workDir.resolve("requests"); }
    public Path outbox()     { return workDir.resolve("outbox"); }
    public Path archive()    { return workDir.resolve("archive"); }
    public Path failed()     { return workDir.resolve("failed"); }
    public Path quarantine() { return workDir.resolve("quarantine"); }
}
