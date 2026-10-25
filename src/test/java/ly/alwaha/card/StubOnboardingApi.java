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

/** Stands in for the onboarding API (account details + get CIF), with fake customers. */
final class StubOnboardingApi implements AutoCloseable {

    static final String ACCOUNT_PATH = "/irf-itss-container/api/v1.0.0/holdings/customer/account/details/";
    static final String CIF_PATH = "/irf-itss-container/api/v1.0.0/holdings/customer/accountId/getCIF/";

    record Customer(String accountNumber, String name, String branch) {}

    final Map<String, Customer> byNationalId = new ConcurrentHashMap<>();
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

    Config.Api config() {
        return new Config.Api(baseUrl(),
                ACCOUNT_PATH + "{nationalId}/{passportNumber}/{phoneNumber}",
                CIF_PATH + "{cif}",
                "/body/0/accountId", "/body/0/customerName", "/body/0/branchName",
                7, "", null, 5);
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
            else reply(ex, 200, "{\"header\":{\"status\":\"success\"},\"body\":[{\"accountId\":\"" + c.accountNumber() + "\"}]}");
        } else if (path.startsWith(CIF_PATH)) {
            String cif = path.substring(CIF_PATH.length());
            Customer c = byNationalId.values().stream()
                    .filter(x -> x.accountNumber().startsWith(cif)).findFirst().orElse(null);
            if (c == null) reply(ex, 404, "{}");
            else reply(ex, 200, "{\"body\":[{\"customerName\":\"" + c.name() + "\",\"branchName\":\"" + c.branch() + "\"}]}");
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
