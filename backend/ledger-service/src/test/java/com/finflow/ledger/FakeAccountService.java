package com.finflow.ledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faux account-service : il répond comme le vrai (404 pour un compte d'une autre organisation,
 * d'après la claim {@code tenant_id} du jeton relayé), enregistre les jetons reçus et peut
 * simuler une panne.
 */
final class FakeAccountService {

    private static final Pattern TENANT = Pattern.compile("\"tenant_id\"\\s*:\\s*\"([^\"]+)\"");

    record Account(UUID tenant, String status, String currency, long perTransactionCents, long dailyCents) {
    }

    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final List<String> receivedTokens = new CopyOnWriteArrayList<>();
    private volatile boolean down;
    private final HttpServer server;

    FakeAccountService() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/v1/accounts/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    UUID add(UUID tenant, String status, long perTransactionCents, long dailyCents) {
        UUID id = UUID.randomUUID();
        accounts.put(id, new Account(tenant, status, "EUR", perTransactionCents, dailyCents));
        return id;
    }

    void down(boolean down) {
        this.down = down;
    }

    List<String> receivedTokens() {
        return receivedTokens;
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (down) {
            respond(exchange, 503, "{}");
            return;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String token = auth == null ? "" : auth.replaceFirst("^Bearer ", "");
        receivedTokens.add(token);
        UUID id = UUID.fromString(exchange.getRequestURI().getPath().substring("/api/v1/accounts/".length()));
        Account account = accounts.get(id);
        if (account == null || !account.tenant().toString().equals(tenantOf(token))) {
            respond(exchange, 404, "{}");
            return;
        }
        respond(exchange, 200, """
                {"id":"%s","name":"Compte","type":"CURRENT","currency":"%s","status":"%s",
                 "limits":{"perTransactionCents":%d,"dailyCents":%d}}"""
                .formatted(id, account.currency(), account.status(), account.perTransactionCents(),
                        account.dailyCents()));
    }

    private static String tenantOf(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        Matcher m = TENANT.matcher(payload);
        return m.find() ? m.group(1) : "";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
