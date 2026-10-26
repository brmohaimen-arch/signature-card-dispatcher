package ly.alwaha.card;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * LOCAL TEST MODE: local folders instead of the two SFTP servers, with the same rules.
 * Source: read only. Target: .part → size + hash check → rename, never overwrite.
 */
public final class LocalFolders {

    private LocalFolders() {}

    /** Reads the app's images from a local copy of OCR_Uploads. Paths are full local paths. */
    public static SourceFiles source() {
        return new SourceFiles() {
            @Override
            public List<Entry> list(String dir) throws IOException {
                Path d = Path.of(dir);
                List<Entry> out = new ArrayList<>();
                if (!Files.isDirectory(d)) return out;
                try (Stream<Path> s = Files.list(d)) {
                    for (Path p : s.toList()) {
                        out.add(new Entry(p.getFileName().toString(), Files.isDirectory(p),
                                Files.getLastModifiedTime(p).toInstant()));
                    }
                }
                return out;
            }

            @Override
            public long size(String path) throws IOException {
                Path p = Path.of(path);
                return Files.isRegularFile(p) ? Files.size(p) : -1;
            }

            @Override
            public InputStream open(String path) throws IOException {
                return Files.newInputStream(Path.of(path));
            }

            @Override
            public void close() {}
        };
    }

    /** Writes finished cards into a local folder, standing in for the T24 repository. */
    public static CardTarget target(Path dir) {
        return new CardTarget() {
            @Override
            public Outcome upload(byte[] data, String name) throws IOException {
                Files.createDirectories(dir);
                Path target = dir.resolve(name);
                String sha = AuditLog.sha256(data);
                if (Files.exists(target)) {
                    return AuditLog.sha256(Files.readAllBytes(target)).equals(sha) ? Outcome.ALREADY_PRESENT : Outcome.REMOTE_DIFFERS;
                }
                Path part = dir.resolve(name + ".part");
                Files.write(part, data);
                if (Files.size(part) != data.length || !AuditLog.sha256(Files.readAllBytes(part)).equals(sha)) {
                    throw new IOException("check failed for " + part);
                }
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                return Outcome.DELIVERED;
            }

            @Override
            public String remotePath(String name) {
                return dir.resolve(name).toString();
            }

            @Override
            public void close() {}
        };
    }
}
