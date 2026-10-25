package ly.alwaha.card;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One sub-folder per customer in inbox/: data.json (nationalId, passportNumber, phoneNumber),
 * selfie.*, signature.* (or signature_original.*). Name, branch and account number come from the onboarding API, not from here.
 */
public final class FolderInput {

    public record InputSet(String id, CustomerLookup.Identity identity, Path selfie, Path signature) {}

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FolderInput() {}

    /** Folders whose files haven't changed for stableSeconds, so a half-copied set is never picked up. */
    public static List<Path> readyFolders(Path inbox, int stableSeconds) throws IOException {
        List<Path> ready = new ArrayList<>();
        if (!Files.isDirectory(inbox)) return ready;
        Instant cutoff = Instant.now().minusSeconds(stableSeconds);
        try (Stream<Path> dirs = Files.list(inbox)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                if (newest(dir).toInstant().isBefore(cutoff)) ready.add(dir);
            }
        }
        return ready;
    }

    public static InputSet read(Path dir) throws IOException, RejectedException {
        Path json = dir.resolve("data.json");
        if (!Files.exists(json)) throw new RejectedException("data.json is missing");
        CustomerLookup.Identity identity;
        try {
            identity = JSON.readValue(json.toFile(), CustomerLookup.Identity.class);
        } catch (IOException e) {
            // No parser message here: it can quote the identity numbers.
            throw new RejectedException("data.json is not valid JSON");
        }
        Path selfie = find(dir, "selfie").orElseThrow(() -> new RejectedException("selfie image is missing"));
        // The processed signature is preferred; the original is used only when it's the only one given.
        Path signature = find(dir, "signature").or(() -> find(dir, "signature_original"))
                .orElseThrow(() -> new RejectedException("signature image is missing"));
        return new InputSet(dir.getFileName().toString(), identity, selfie, signature);
    }

    /** Matches "name.anything", case-insensitive. The real type is detected later from the content. */
    private static Optional<Path> find(Path dir, String base) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        int dot = n.lastIndexOf('.');
                        return dot > 0 && n.substring(0, dot).equals(base);
                    })
                    .sorted()
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static FileTime newest(Path dir) throws IOException {
        FileTime newest = Files.getLastModifiedTime(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                FileTime t = Files.getLastModifiedTime(f);
                if (t.compareTo(newest) > 0) newest = t;
            }
        }
        return newest;
    }
}
