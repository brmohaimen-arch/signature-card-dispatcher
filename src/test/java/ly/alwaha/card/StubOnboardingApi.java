package ly.alwaha.card;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the onboarding API with fake customers, shaped like the real responses:
 *   account details → body[0].accountNumber / accountName ("ENGLISH NAME + Arabic name") / branch (a code)
 *   getBranches     → body[].companyId / displayName (English) / languageId / mnemonic
 */
final class StubOnboardingApi implements AutoCloseable {

    static final String ACCOUNT_PATH = "/irf-itss-container/api/v1.0.0/holdings/customer/account/details/";
    static final String BRANCHES_PATH = "/irf-itss-container/api/v1.0.0/holdings/getBranches";
    static final String ENGLISH_NAME = "MOHAMMED ALI ABDULLAH ALWARFALLI";

    /** name = the Arabic name; the API returns it after the English one, like the real accountName. */
    record Customer(String accountNumber, String name, String branchCode) {}

    final Map<String, Customer> byNationalId = new ConcurrentHashMap<>();
    /** What getBranches returns: code → English display name. */
    final Map<String, String> branches = new ConcurrentHashMap<>(Map.of(
            "LY0010001", "AlWaha Bank",
            TestSupport.BRANCH_CODE, TestSupport.BRANCH_ENGLISH,
            "LY0010003", "Zliten Branch",
            "LY0010009", "Qaminis Branch"));
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile boolean down;
    private final HttpServer server;

    StubOnboardingApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    long branchCalls() {
        return requests.stream().filter(BRANCHES_PATH::equals).count();
    }

    /** Settings matching the real responses; the test branch code has its Arabic name saved. */
    Config.Api config() {
        return config(Map.of(TestSupport.BRANCH_CODE, TestSupport.BRANCH));
    }

    Config.Api config(Map<String, String> savedArabicNames) {
        return new Config.Api(baseUrl(),
                ACCOUNT_PATH + "{nationalId}/{passportNumber}/{phoneNumber}",
                "/body/0/accountNumber", "/body/0/accountName", "/body/0/branch",
                BRANCHES_PATH, "/body", "companyId", "displayName",
                "", null, 5, savedArabicNames, null);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(path);
        if (down) {
            reply(ex, 503, "{}");
            return;
        }
        if (path.startsWith(ACCOUNT_PATH)) {
            String[] parts = path.substring(ACCOUNT_PATH.length()).split("/");
            Customer c = byNationalId.get(parts[0]);
            if (c == null) reply(ex, 404, "{\"error\":\"not found\"}");
            else reply(ex, 200, "{\"header\":{\"status\":\"success\"},\"body\":[{\"product\":\"Current Account\","
                    + "\"accountNumber\":\"" + c.accountNumber() + "\","
                    + "\"accountName\":\"" + ENGLISH_NAME + " " + c.name() + "\","
                    + "\"customerID\":\"" + c.accountNumber().substring(0, Math.min(7, c.accountNumber().length())) + "\","
                    + "\"branch\":\"" + c.branchCode() + "\",\"status\":\"ACTIVE\"}]}");
        } else if (path.equals(BRANCHES_PATH)) {
            StringBuilder b = new StringBuilder("{\"header\":{\"status\":\"success\",\"total_size\":" + branches.size() + "},\"body\":[");
            int i = 0;
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(branches).entrySet()) {
                if (i++ > 0) b.append(',');
                b.append("{\"companyId\":\"").append(e.getKey()).append("\",\"displayName\":\"").append(e.getValue())
                        .append("\",\"languageId\":1,\"mnemonic\":\"X").append(i).append("\"}");
            }
            reply(ex, 200, b.append("]}").toString());
        } else {
            reply(ex, 404, "{}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
