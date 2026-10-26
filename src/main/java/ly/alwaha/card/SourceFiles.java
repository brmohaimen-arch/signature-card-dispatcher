package ly.alwaha.card;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Where the app's images are read from: the onboarding SFTP (production) or a local folder (local test mode).
 * Read only: list folders, read sizes, open files. Nothing is ever written, moved or deleted.
 */
public interface SourceFiles extends Closeable {

    /** One entry of a folder listing. */
    record Entry(String name, boolean directory, java.time.Instant modified) {}

    /** The folder's entries, or an empty list if it doesn't exist. */
    List<Entry> list(String dir) throws IOException;

    /** The file's size in bytes, or -1 if it doesn't exist. Metadata only. */
    long size(String path) throws IOException;

    InputStream open(String path) throws IOException;
}
