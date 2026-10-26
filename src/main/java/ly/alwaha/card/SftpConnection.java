package ly.alwaha.card;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Duration;

/**
 * One SFTP connection: to the T24 document repository (upload) or the onboarding server (read only).
 * The host key must already be in known_hosts. Unknown or changed keys are rejected; there's no "accept all".
 */
public final class SftpConnection implements SourceFiles, CardTarget {

    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;
    private final String remoteDir;

    private SftpConnection(SshClient client, ClientSession session, SftpClient sftp, String remoteDir) {
        this.client = client;
        this.session = session;
        this.sftp = sftp;
        this.remoteDir = remoteDir;
    }

    public static SftpConnection connect(Config.SftpEndpoint e) throws IOException {
        if (e.host().isEmpty() || e.user().isEmpty()) {
            throw new IOException("SFTP host and user must be set");
        }
        if (!Files.isRegularFile(e.knownHosts())) {
            throw new IOException("known_hosts not found at " + e.knownHosts() + "; the server's host key must be pinned");
        }
        Duration timeout = Duration.ofSeconds(e.timeoutSeconds());
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, e.knownHosts()));
        if (e.keyPath() != null) {
            FileKeyPairProvider keys = new FileKeyPairProvider(e.keyPath());
            if (e.keyPassphrase() != null) keys.setPasswordFinder(FilePasswordProvider.of(e.keyPassphrase()));
            client.setKeyIdentityProvider(keys);
        }
        client.start();
        try {
            ClientSession session = client.connect(e.user(), e.host(), e.port()).verify(timeout).getSession();
            if (e.password() != null) session.addPasswordIdentity(e.password());
            session.auth().verify(timeout);
            SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            return new SftpConnection(client, session, sftp, e.remoteDir());
        } catch (IOException | RuntimeException ex) {
            client.stop();
            throw ex;
        }
    }

    /**
     * Upload as name.part, check the size and read back the hash, then rename.
     * T24 never sees a half-written file. An existing file is never overwritten.
     */
    public Outcome upload(byte[] data, String name) throws IOException {
        String target = remotePath(name);
        String part = target + ".part";
        String sha = AuditLog.sha256(data);

        if (exists(target)) {
            return AuditLog.sha256(read(target)).equals(sha) ? Outcome.ALREADY_PRESENT : Outcome.REMOTE_DIFFERS;
        }
        try (OutputStream out = sftp.write(part)) {
            out.write(data);
        }
        long size = sftp.stat(part).getSize();
        if (size != data.length) {
            throw new IOException("size check failed for " + part + ": remote " + size + ", local " + data.length);
        }
        if (!AuditLog.sha256(read(part)).equals(sha)) {
            throw new IOException("read-back hash check failed for " + part);
        }
        sftp.rename(part, target);
        return Outcome.DELIVERED;
    }

    /** Metadata only: the file's size in bytes, or -1 if it doesn't exist. Nothing is downloaded. */
    public long size(String path) throws IOException {
        try {
            return sftp.stat(path).getSize();
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) return -1;
            throw e;
        }
    }

    /** Read-only listing of a remote folder (metadata only); an empty list if it doesn't exist. */
    public java.util.List<Entry> list(String dir) throws IOException {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        try {
            for (SftpClient.DirEntry e : sftp.readDir(dir)) {
                String name = e.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                SftpClient.Attributes a = e.getAttributes();
                java.time.Instant modified = a.getModifyTime() == null ? java.time.Instant.EPOCH : a.getModifyTime().toInstant();
                out.add(new Entry(name, a.isDirectory(), modified));
            }
        } catch (SftpException ex) {
            if (ex.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) return out;
            throw ex;
        }
        return out;
    }

    /** Read-only stream of a remote file. The caller closes it. */
    public InputStream open(String path) throws IOException {
        return sftp.read(path);
    }

    /** For check-sftp: the target path must exist and be a folder. Nothing is written. */
    public String describeRemoteDir() throws IOException {
        String dir = remoteDir.isEmpty() ? "." : remoteDir;
        SftpClient.Attributes attrs = sftp.stat(dir);
        if (!attrs.isDirectory()) throw new IOException(dir + " exists but is not a folder");
        return sftp.canonicalPath(dir);
    }

    public String remotePath(String name) {
        return remoteDir.isEmpty() ? name : remoteDir + "/" + name;
    }

    private boolean exists(String path) throws IOException {
        return size(path) >= 0;
    }

    private byte[] read(String path) throws IOException {
        try (InputStream in = sftp.read(path)) {
            return in.readAllBytes();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            sftp.close();
            session.close();
        } finally {
            client.stop();
        }
    }
}
