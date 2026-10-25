package ly.alwaha.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Builds the card text from the onboarding API:
 *   1. account details by national ID + passport + phone  → account number
 *   2. CIF = the first 7 digits of the account number     → full name + branch
 * Permanent problems (customer not found, missing field) throw RejectedException.
 * Temporary ones (timeout, 5xx) throw IOException, so the input stays in inbox/ and is retried next cycle.
 * Identity values are never logged.
 */
public final class CustomerLookup {

    /** What the onboarding side gives us about the customer. */
    public record Identity(String nationalId, String passportNumber, String phoneNumber) {}

    // Path segments: letters, digits and a leading + for phones. Nothing that could change the URL.
    private static final Pattern SEGMENT = Pattern.compile("\\+?[A-Za-z0-9]{1,40}");

    private final Config.Api api;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public CustomerLookup(Config.Api api) {
        this.api = api;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(api.timeoutSeconds())).build();
    }

    /** Base URL and both paths are set. Until then the endpoint answers "not configured". */
    public boolean configured() {
        return !api.baseUrl().isEmpty() && !api.accountPath().isEmpty() && !api.cifPath().isEmpty();
    }

    public CardData lookup(Identity id) throws IOException, RejectedException {
        if (!configured()) throw new IOException("onboarding API is not configured (api.base.url / api.cif.path)");
        JsonNode account = get("account details", accountPath(id));
        String accountNumber = field(account, api.accountNumberPointer(), "account number");
        if (!accountNumber.matches("\\d+") || accountNumber.length() < api.cifDigits()) {
            throw new RejectedException("account number from the onboarding API is not numeric or shorter than " + api.cifDigits() + " digits");
        }

        String cif = accountNumber.substring(0, api.cifDigits());
        JsonNode customer = get("get CIF", api.cifPath().replace("{cif}", cif));
        return new CardData(
                field(customer, api.fullNamePointer(), "customer name"),
                field(customer, api.branchPointer(), "branch"),
                accountNumber);
    }

    /**
     * For check-api: prints both raw responses and what each JSON pointer picks out,
     * so the pointers in application.properties can be set from the real response. Use a TEST customer only.
     */
    public String probe(Identity id) throws IOException {
        StringBuilder r = new StringBuilder();
        try {
            JsonNode account = get("account details", accountPath(id));
            r.append("account details response:\n").append(json.writerWithDefaultPrettyPrinter().writeValueAsString(account)).append('\n');
            String accountNumber = field(account, api.accountNumberPointer(), "account number");
            r.append("  ").append(api.accountNumberPointer()).append(" -> ").append(accountNumber).append('\n');
            String cif = accountNumber.substring(0, Math.min(api.cifDigits(), accountNumber.length()));
            JsonNode customer = get("get CIF", api.cifPath().replace("{cif}", cif));
            r.append("\nget CIF (").append(cif).append(") response:\n")
                    .append(json.writerWithDefaultPrettyPrinter().writeValueAsString(customer)).append('\n');
            r.append("  ").append(api.fullNamePointer()).append(" -> ").append(field(customer, api.fullNamePointer(), "customer name")).append('\n');
            r.append("  ").append(api.branchPointer()).append(" -> ").append(field(customer, api.branchPointer(), "branch")).append('\n');
            r.append("\nOK: all pointers matched");
        } catch (RejectedException e) {
            r.append("\nSTOPPED: ").append(e.getMessage()).append("\n-> adjust the pointer in application.properties to match the response above");
        }
        return r.toString();
    }

    private String accountPath(Identity id) throws RejectedException {
        return api.accountPath()
                .replace("{nationalId}", segment("nationalId", id.nationalId()))
                .replace("{passportNumber}", segment("passportNumber", id.passportNumber()))
                .replace("{phoneNumber}", segment("phoneNumber", id.phoneNumber()));
    }

    private JsonNode get(String what, String path) throws IOException, RejectedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(api.baseUrl() + path))
                .timeout(Duration.ofSeconds(api.timeoutSeconds()))
                .header("Accept", "application/json")
                .GET();
        if (!api.authHeader().isEmpty()) {
            if (api.authValue() == null) throw new IllegalStateException("API_AUTH_VALUE is not set");
            req.header(api.authHeader(), api.authValue());
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(what + " call interrupted", e);
        }
        int status = res.statusCode();
        if (status == 404) throw new RejectedException(what + ": customer not found (404)");
        if (status >= 500 || status == 429 || status == 408) throw new IOException(what + " returned HTTP " + status);
        if (status != 200) throw new RejectedException(what + " returned HTTP " + status);
        try {
            return json.readTree(res.body());
        } catch (IOException e) {
            throw new IOException(what + " returned a response that is not JSON");
        }
    }

    private static String field(JsonNode root, String pointer, String what) throws RejectedException {
        JsonNode n = root.at(pointer);
        if (n.isMissingNode() || n.isNull() || n.asText().isBlank()) {
            throw new RejectedException(what + " not found in the onboarding API response (" + pointer + ")");
        }
        return n.asText().strip();
    }

    private static String segment(String name, String value) throws RejectedException {
        if (value == null || !SEGMENT.matcher(value.strip()).matches()) {
            throw new RejectedException(name + " is missing or has characters that are not allowed");
        }
        return value.strip();
    }
}
