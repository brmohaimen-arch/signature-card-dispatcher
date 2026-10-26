package ly.alwaha.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Builds the card text from the onboarding API:
 *   1. account details by national ID + passport + phone → account number, name, branch code
 *   2. branch name: the Arabic name saved in the branch table; if there is none yet, the English name from getBranches
 * The card gets the ARABIC part of the customer's name.
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
    private static final Pattern ARABIC = Pattern.compile("[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]");
    // The branch list rarely changes: fetch it at most once an hour (or on "Refresh" in the dashboard).
    private static final Duration BRANCHES_TTL = Duration.ofHours(1);
    // An unknown code may be a brand-new branch: allow one early refresh, but not more often than this.
    private static final Duration BRANCHES_MIN_REFRESH = Duration.ofMinutes(5);

    private final Config.Api api;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private Map<String, String> branchList;   // code → English name, from getBranches
    private Instant branchListAt = Instant.EPOCH;

    public CustomerLookup(Config.Api api) {
        this.api = api;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(api.timeoutSeconds())).build();
    }

    /** Base URL and the account path are set. */
    public boolean configured() {
        return api.local() != null || !api.baseUrl().isEmpty() && !api.accountPath().isEmpty();
    }

    /** The account-details response: from the API, or in LOCAL TEST MODE from the customers file. */
    private JsonNode accountResponse(Identity id) throws IOException, RejectedException {
        String path = accountPath(id); // validates the identity values either way
        if (api.local() == null) return get("account details", path, true);
        JsonNode customers = readLocal(api.local().customersFile());
        for (JsonNode c : customers) {
            if (c.path("nationalId").asText().equals(id.nationalId().strip())
                    && c.path("passportNumber").asText().equals(id.passportNumber().strip())
                    && c.path("phoneNumber").asText().equals(id.phoneNumber().strip())) {
                return c.path("response");
            }
        }
        throw new RejectedException("account details: customer not found (404) [local test file]");
    }

    /** The getBranches response: from the API, or in LOCAL TEST MODE from the branches file. */
    private JsonNode branchesResponse() throws IOException, RejectedException {
        if (api.local() != null) return readLocal(api.local().branchesFile());
        if (api.baseUrl().isEmpty() || api.branchesPath().isEmpty()) {
            throw new IOException("getBranches is not configured (api.base.url / api.branches.path)");
        }
        return get("getBranches", api.branchesPath(), false);
    }

    private JsonNode readLocal(java.nio.file.Path file) throws IOException {
        if (!java.nio.file.Files.exists(file)) throw new IOException("local test file not found: " + file);
        try {
            return json.readTree(file.toFile());
        } catch (IOException e) {
            throw new IOException("local test file is not valid JSON: " + file);
        }
    }

    public CardData lookup(Identity id) throws IOException, RejectedException {
        if (!configured()) throw new IOException("onboarding API is not configured (api.base.url / api.account.path)");
        JsonNode account = accountResponse(id);
        String accountNumber = field(account, api.accountNumberPointer(), "account number");
        if (!accountNumber.matches("\\d+")) {
            throw new RejectedException("account number from the onboarding API is not numeric");
        }
        String branchCode = field(account, api.branchPointer(), "branch");
        return new CardData(
                arabicPart(field(account, api.fullNamePointer(), "customer name")),
                branchName(branchCode),
                accountNumber,
                branchCode);
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

    /** Arabic name from the branch table; if none is saved yet, the English name from getBranches. */
    private String branchName(String rawCode) throws IOException, RejectedException {
        String code = rawCode.strip();
        String arabic = api.branches().get(code);
        if (arabic != null && !arabic.isBlank()) return arabic;
        String english = branches(false).get(code);
        if (english == null && Instant.now().isAfter(branchListAt.plus(BRANCHES_MIN_REFRESH))) {
            english = branches(true).get(code);
        }
        if (english == null || english.isBlank()) {
            throw new RejectedException("branch code " + code + " is not in the getBranches list");
        }
        return english;
    }

    /** code → English name from getBranches, cached for an hour; force = fetch now (dashboard "Refresh"). */
    public synchronized Map<String, String> branches(boolean force) throws IOException, RejectedException {
        if (!force && branchList != null && Instant.now().isBefore(branchListAt.plus(BRANCHES_TTL))) return branchList;
        JsonNode list = branchesResponse().at(api.branchesListPointer());
        if (!list.isArray()) {
            throw new RejectedException("getBranches response has no list at " + api.branchesListPointer());
        }
        Map<String, String> out = new TreeMap<>();
        for (JsonNode b : list) {
            String code = b.path(api.branchCodeField()).asText("").strip();
            String name = b.path(api.branchNameField()).asText("").strip();
            if (!code.isEmpty() && !name.isEmpty()) out.putIfAbsent(code, name);
        }
        branchList = Map.copyOf(out);
        branchListAt = Instant.now();
        return branchList;
    }

    /**
     * For check-api: prints the raw account response and what each field picks out (plus the Arabic name and
     * the branch result), so the settings can be matched to the real response. Use a TEST customer only.
     */
    public String probe(Identity id) throws IOException {
        StringBuilder r = new StringBuilder();
        try {
            JsonNode account = accountResponse(id);
            r.append("account details response:\n").append(json.writerWithDefaultPrettyPrinter().writeValueAsString(account)).append('\n');
            String accountNumber = field(account, api.accountNumberPointer(), "account number");
            r.append("  ").append(api.accountNumberPointer()).append(" -> ").append(accountNumber)
                    .append(" (").append(accountNumber.length()).append(" digits)\n");
            String name = field(account, api.fullNamePointer(), "customer name");
            r.append("  ").append(api.fullNamePointer()).append(" -> ").append(name).append('\n');
            r.append("     Arabic part for the card -> ").append(arabicPart(name)).append('\n');
            String code = field(account, api.branchPointer(), "branch");
            r.append("  ").append(api.branchPointer()).append(" -> ").append(code).append('\n');
            String saved = api.branches().get(code);
            r.append("     branch on the card -> ").append(saved != null ? saved + " (Arabic, from the branch table)"
                    : branchName(code) + " (English from getBranches: no Arabic name saved yet)").append('\n');
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

    /** notFoundIsCustomer: a 404 on the account call means "no such customer"; on getBranches it's a setup problem. */
    private JsonNode get(String what, String path, boolean notFoundIsCustomer) throws IOException, RejectedException {
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
        if (status == 404 && notFoundIsCustomer) throw new RejectedException(what + ": customer not found (404)");
        if (status >= 500 || status == 429 || status == 408 || status == 404) {
            throw new IOException(what + " returned HTTP " + status);
        }
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
