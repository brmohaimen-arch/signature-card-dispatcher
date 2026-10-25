package ly.alwaha.card;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Passwords and keys entered in the dashboard, stored AES-256-GCM encrypted in config/secrets.enc.
 * The master key is the SECRETS_MASTER_KEY environment variable (base64 of 32 random bytes).
 * An environment variable with the same name as a secret always wins over the stored value.
 * Values can be set or cleared, never read back through the dashboard.
 */
public final class Secrets {

    public static final List<String> NAMES = List.of(
            "SFTP_PASSWORD", "SFTP_KEY_PASSPHRASE",
            "SOURCE_SFTP_PASSWORD", "SOURCE_SFTP_KEY_PASSPHRASE",
            "API_AUTH_VALUE", "QUARANTINE_KEY");

    private static final byte[] MAGIC = "SCS1".getBytes(StandardCharsets.US_ASCII);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final SecretKeySpec master;
    private final Function<String, String> env;

    public Secrets(Path file, String base64MasterKey, Function<String, String> env) {
        this.file = file;
        this.master = parseMaster(base64MasterKey);
        this.env = env;
    }

    /** secrets.enc next to the config file, master key and overrides from the real environment. */
    public static Secrets forConfig(Path configFile) {
        Path dir = configFile.toAbsolutePath().getParent();
        return new Secrets(dir.resolve("secrets.enc"), System.getenv("SECRETS_MASTER_KEY"), System::getenv);
    }

    public boolean storeAvailable() {
        return master != null;
    }

    public String get(String name) {
        String v = env.apply(name);
        if (v != null && !v.isEmpty()) return v;
        return master == null ? null : read().get(name);
    }

    /** "env", "store" or "none": shown in the dashboard instead of the value. */
    public String source(String name) {
        String v = env.apply(name);
        if (v != null && !v.isEmpty()) return "env";
        return master != null && read().containsKey(name) ? "store" : "none";
    }

    /** Sets (non-empty value) or clears (empty value) secrets. */
    public synchronized void put(Map<String, String> updates) throws IOException {
        if (master == null) throw new IllegalStateException("SECRETS_MASTER_KEY is not set");
        Map<String, String> all = new TreeMap<>(read());
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (!NAMES.contains(e.getKey())) throw new IllegalArgumentException("unknown secret " + e.getKey());
            if (e.getValue() == null || e.getValue().isEmpty()) all.remove(e.getKey());
            else all.put(e.getKey(), e.getValue());
        }
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        byte[] plain = JSON.writeValueAsBytes(all);
        try {
            byte[] enc = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plain);
            byte[] out = new byte[MAGIC.length + iv.length + enc.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(iv, 0, out, MAGIC.length, iv.length);
            System.arraycopy(enc, 0, out, MAGIC.length + iv.length, enc.length);
            Generator.writeAtomically(file, out);
        } catch (GeneralSecurityException e) {
            throw new IOException("could not encrypt secrets", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private Map<String, String> read() {
        if (master == null || !Files.exists(file)) return Map.of();
        try {
            byte[] all = Files.readAllBytes(file);
            if (all.length < MAGIC.length + 12 || !Arrays.equals(Arrays.copyOf(all, MAGIC.length), MAGIC)) {
                throw new IllegalStateException(file + " is not a secrets file");
            }
            byte[] iv = Arrays.copyOfRange(all, MAGIC.length, MAGIC.length + 12);
            byte[] plain = cipher(Cipher.DECRYPT_MODE, iv).doFinal(all, MAGIC.length + 12, all.length - MAGIC.length - 12);
            return JSON.readValue(plain, new TypeReference<Map<String, String>>() {});
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot decrypt " + file + ": wrong SECRETS_MASTER_KEY or file altered");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file + ": " + e.getMessage());
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, master, new GCMParameterSpec(128, iv));
        return c;
    }

    private static SecretKeySpec parseMaster(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        byte[] k;
        try {
            k = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SECRETS_MASTER_KEY is not valid base64");
        }
        if (k.length != 32) throw new IllegalStateException("SECRETS_MASTER_KEY must be 32 bytes, got " + k.length);
        return new SecretKeySpec(k, "AES");
    }
}
