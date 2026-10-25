package ly.alwaha.card;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * One small JSON file per request UUID in work/requests/: the request's digital fingerprint record and the
 * stored answer that makes retries idempotent. No ID numbers or names are stored, only the outcome.
 */
public final class RequestStore {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String requestId, int httpStatus, String status, String accountNumber, String reason,
                        String cardSha256, String originalRequestId, String createdAt) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    public RequestStore(Path dir) {
        this.dir = dir;
    }

    public Optional<Entry> get(UUID requestId) throws IOException {
        Path f = file(requestId);
        return Files.exists(f) ? Optional.of(JSON.readValue(f.toFile(), Entry.class)) : Optional.empty();
    }

    public void put(UUID requestId, Entry e) throws IOException {
        Generator.writeAtomically(file(requestId), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(e));
    }

    private Path file(UUID requestId) {
        return dir.resolve(requestId + ".json");
    }
}
