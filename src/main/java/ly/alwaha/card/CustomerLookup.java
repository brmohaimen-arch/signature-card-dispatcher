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
 *   1. account details by national ID + passport + phone  → account number, name, branch code
 *   2. (only if api.cif.enabled) CIF = first 7 digits → name + branch from get CIF instead
 * The card gets the ARABIC part of the name, and the branch NAME looked up from the branch code in the branch table.
 * Permanent problems (customer not found, missing field, unknown branch code) throw RejectedException.
 * Temporary ones (timeout, 5xx) throw IOException, so the request can be retried.
 * Identity values are never logged.
 */
public final class CustomerLookup {

    /** What the onboarding side gives us about the customer. */
    public record Identity(String nationalId, String passportNumber, String phoneNumber) {}

    // Path segments: letters, digits and a leading + for phones. Nothing that could change the URL.
    private static final Pattern SEGMENT = Pattern.compile("\\+?[A-Za-z0-9]{1,40}");
    // A word with at least one Arabic letter (Arabic, Arabic Supplement, presentation forms).
    private static final Pattern ARABIC = Pattern.compile("[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]");

    private final Config.Api api;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public CustomerLookup(Config.Api api) {
        this.api = api;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(api.timeoutSeconds())).build();
    }

    /** Base URL and the account path are set (and the CIF path, if CIF is switched on). */
    public boolean configured() {
        return !api.baseUrl().isEmpty() && !api.accountPath().isEmpty() && (!api.cifEnabled() || !api.cifPath().isEmpty());
    }

    public CardData lookup(Identity id) throws IOException, RejectedException {
        if (!configured()) throw new IOException("onboarding API is not configured (api.base.url / api.account.path)");
        JsonNode account = get("account details", accountPath(id));
        String accountNumber = field(account, api.accountNumberPointer(), "account number");
        if (!accountNumber.matches("\\d+") || accountNumber.length() < api.cifDigits()) {
            throw new RejectedException("account number from the onboarding API is not numeric or shorter than " + api.cifDigits() + " digits");
        }
        JsonNode details = api.cifEnabled()
                ? get("get CIF", api.cifPath().replace("{cif}", accountNumber.substring(0, api.cifDigits())))
                : account;
        return new CardData(
                arabicPart(field(details, api.fullNamePointer(), "customer name")),
                branchName(field(details, api.branchPointer(), "branch")),
                accountNumber);
    }

    /** Only the Arabic words, in their original order: "MOHAMED ALI محمد علي" → "محمد علي". */
    static String arabicPart(String name) throws RejectedException {
        StringBuilder out = new StringBuilder();
        for (String word : name.strip().split("\\s+")) {
            if (ARABIC.matcher(word).find()) out.append(out.length() == 0 ? "" : " ").append(word);
        }
        if (out.length() == 0) throw new RejectedException("customer name from the onboarding API has no Arabic part");
        return out.toString();
    }

    private String branchName(String code) throws RejectedException {
        String name = api.branches().get(code.strip());
        if (name == null || name.isBlank()) {
            throw new RejectedException("branch code " + code.strip() + " is not in the branch table (add it in the dashboard)");
        }
        return name;
    }

    /**
     * For check-api: prints the raw responses and what each JSON pointer picks out (plus the Arabic name and
     * the branch-table result), so the settings can be matched to the real response. Use a TEST customer only.
     */
    public String probe(Identity id) throws IOException {
        StringBuilder r = new StringBuilder();
        try {
            JsonNode account = get("account details", accountPath(id));
            r.append("account details response:\n").append(json.writerWithDefaultPrettyPrinter().writeValueAsString(account)).append('\n');
            String accountNumber = field(account, api.accountNumberPointer(), "account number");
            r.append("  ").append(api.accountNumberPointer()).append(" -> ").append(accountNumber)
                    .append(" (").append(accountNumber.length()).append(" digits)\n");
            JsonNode details = account;
            if (api.cifEnabled()) {
                String cif = accountNumber.substring(0, Math.min(api.cifDigits(), accountNumber.length()));
                details = get("get CIF", api.cifPath().replace("{cif}", cif));
                r.append("\nget CIF (").append(cif).append(") response:\n")
                        .append(json.writerWithDefaultPrettyPrinter().writeValueAsString(details)).append('\n');
            }
            String name = field(details, api.fullNamePointer(), "customer name");
            r.append("  ").append(api.fullNamePointer()).append(" -> ").append(name).append('\n');
            r.append("     Arabic part for the card -> ").append(arabicPart(name)).append('\n');
            String code = field(details, api.branchPointer(), "branch");
            r.append("  ").append(api.branchPointer()).append(" -> ").append(code).append('\n');
            r.append("     branch table -> ").append(branchName(code)).append('\n');
            r.append("\nOK: all fields matched");
        } catch (RejectedException e) {
            r.append("\nSTOPPED: ").append(e.getMessage()).append("\n-> adjust the setting to match the response above");
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
