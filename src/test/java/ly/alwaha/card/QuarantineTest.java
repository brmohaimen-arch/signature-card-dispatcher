package ly.alwaha.card;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class QuarantineTest {

    @TempDir
    Path tmp;

    @Test
    void wrongKeyOrTamperedFileCannotBeDecrypted() throws Exception {
        Path file = tmp.resolve("evil.jpg");
        Files.writeString(file, "suspicious content");
        Quarantine q = new Quarantine(tmp.resolve("q"), TestSupport.QUARANTINE_KEY);
        Path enc = q.encryptAndDelete(file, "ONB-1");
        assertFalse(Files.exists(file));

        byte[] other = new byte[32];
        other[0] = 1;
        Quarantine wrongKey = new Quarantine(tmp.resolve("q"), Base64.getEncoder().encodeToString(other));
        assertThrows(IOException.class, () -> wrongKey.decrypt(enc, tmp.resolve("out1")));

        byte[] bytes = Files.readAllBytes(enc);
        bytes[bytes.length - 1] ^= 1;
        Files.write(enc, bytes);
        assertThrows(IOException.class, () -> q.decrypt(enc, tmp.resolve("out2")));
    }

    @Test
    void missingOrShortKeyIsRefused() {
        assertThrows(IllegalStateException.class, () -> new Quarantine(tmp, null));
        assertThrows(IllegalStateException.class, () -> new Quarantine(tmp, Base64.getEncoder().encodeToString(new byte[16])));
    }
}
