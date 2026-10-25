package ly.alwaha.card;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Suspicious files are encrypted into work/quarantine/ and the original is deleted.
 * AES-256-GCM, key from the QUARANTINE_KEY environment variable (base64 of 32 random bytes).
 * The file is streamed, so even a huge file is never held in memory or kept unencrypted.
 * File format: "SCQ1" + 12-byte IV + ciphertext (with the GCM tag).
 */
public final class Quarantine {

    private static final byte[] MAGIC = "SCQ1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;

    private final Path dir;
    private final SecretKeySpec key;

    public Quarantine(Path dir, String base64Key) {
        this.dir = dir;
        this.key = parseKey(base64Key);
    }

    /** Encrypts OUR local file into quarantine, then deletes it. Returns the encrypted file. */
    public Path encryptAndDelete(Path file, String inputId) throws IOException {
        Path target;
        try (InputStream in = Files.newInputStream(file)) {
            target = encrypt(in, file.getFileName().toString(), inputId);
        }
        // Delete only after the encrypted copy is safely written.
        Files.delete(file);
        return target;
    }

    /** Encrypts a stream (e.g. straight from the source SFTP) into quarantine; nothing is stored unencrypted. */
    public Path encrypt(InputStream in, String fileName, String inputId) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(inputId + "-" + fileName + ".enc");
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        try (OutputStream raw = Files.newOutputStream(tmp)) {
            raw.write(MAGIC);
            raw.write(iv);
            try (OutputStream out = new CipherOutputStream(raw, cipher(Cipher.ENCRYPT_MODE, iv))) {
                in.transferTo(out);
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    /** For the security team: decrypt-quarantine IN.enc OUT. Fails if the key is wrong or the file was altered. */
    public void decrypt(Path enc, Path out) throws IOException {
        try (InputStream raw = Files.newInputStream(enc)) {
            byte[] magic = raw.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) throw new IOException(enc + " is not a quarantine file");
            byte[] iv = raw.readNBytes(IV_BYTES);
            try (InputStream in = new CipherInputStream(raw, cipher(Cipher.DECRYPT_MODE, iv))) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, key, new GCMParameterSpec(128, iv));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IOException("cipher setup failed", e);
        }
    }

    private static SecretKeySpec parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("QUARANTINE_KEY is not set (base64 of 32 random bytes; see README)");
        }
        byte[] k;
        try {
            k = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("QUARANTINE_KEY is not valid base64");
        }
        if (k.length != 32) throw new IllegalStateException("QUARANTINE_KEY must be 32 bytes (AES-256), got " + k.length);
        return new SecretKeySpec(k, "AES");
    }
}
