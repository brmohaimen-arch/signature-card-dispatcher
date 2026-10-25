package ly.alwaha.card;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads an SFTP server's host key (without logging in) so the admin can compare its fingerprint with the one
 * the server's owner gives them, then pins exactly that key in known_hosts.
 */
public final class HostKeys {

    public record Seen(String fingerprint, String knownHostsLine) {}

    private HostKeys() {}

    public static Seen fetch(String host, int port, int timeoutSeconds) throws IOException {
        if (host == null || host.isBlank()) throw new IOException("host is not set");
        AtomicReference<PublicKey> seen = new AtomicReference<>();
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, addr, key) -> {
            seen.set(key);
            return false; // only looking: never actually connect with an unverified key
        });
        client.start();
        Duration t = Duration.ofSeconds(timeoutSeconds);
        try (ClientSession s = client.connect("hostkey-probe", host, port).verify(t).getSession()) {
            s.auth().verify(t);
        } catch (Exception expected) {
            // The key exchange is refused on purpose once the key has been seen.
        } finally {
            client.stop();
        }
        PublicKey key = seen.get();
        if (key == null) throw new IOException("could not read the host key from " + host + ":" + port);
        String hostPattern = port == 22 ? host : "[" + host + "]:" + port;
        return new Seen(KeyUtils.getFingerPrint(key), hostPattern + " " + PublicKeyEntry.toString(key));
    }

    /**
     * Fetches the key again and pins it only if it still has the fingerprint the admin confirmed.
     * The previous known_hosts file is kept as a backup.
     */
    public static Seen pin(String host, int port, int timeoutSeconds, String confirmedFingerprint, Path knownHosts)
            throws IOException {
        Seen now = fetch(host, port, timeoutSeconds);
        if (!now.fingerprint().equals(confirmedFingerprint)) {
            throw new IOException("the server's key changed since you checked it (now " + now.fingerprint() + "); not pinned");
        }
        Path parent = knownHosts.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        if (Files.exists(knownHosts)) {
            Files.createDirectories(parent.resolve("backups"));
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Files.copy(knownHosts, parent.resolve("backups").resolve(knownHosts.getFileName() + "-" + stamp),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(knownHosts, now.knownHostsLine() + "\n", StandardCharsets.UTF_8);
        return now;
    }
}
