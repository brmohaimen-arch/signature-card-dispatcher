package ly.alwaha.card;

import java.io.Closeable;
import java.io.IOException;

/**
 * Where finished cards are delivered: the T24 SFTP repository (production) or a local folder (local test mode).
 * Same rules either way: written as .part, checked, renamed; an existing card is never overwritten.
 */
public interface CardTarget extends Closeable {

    enum Outcome { DELIVERED, ALREADY_PRESENT, REMOTE_DIFFERS }

    Outcome upload(byte[] data, String name) throws IOException;

    /** Where the card ends up, for the log. */
    String remotePath(String name);
}
