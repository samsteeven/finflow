package com.finflow.payment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faux Ledger : comptabilise de façon idempotente (une transaction par paiement ou remboursement,
 * comme le vrai) et sait simuler un refus métier, une panne et une réponse perdue après
 * comptabilisation.
 */
final class FakeLedgerService {

    private static final Pattern ID = Pattern.compile("\"(paymentId|refundId)\"\\s*:\\s*\"([^\"]+)\"");

    private final Map<String, UUID> posted = new ConcurrentHashMap<>();
    private final List<String> receivedTokens = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean down;
    private volatile boolean insufficientFunds;
    private volatile boolean reversalRefused;
    private volatile boolean notReversible;
    private final AtomicInteger lostResponses = new AtomicInteger();
    private final HttpServer server;

    FakeLedgerService() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/v1/ledger/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void reset() {
        down = false;
        insufficientFunds = false;
        reversalRefused = false;
        notReversible = false;
        lostResponses.set(0);
    }

    void down(boolean down) {
        this.down = down;
    }

    void insufficientFunds(boolean value) {
        this.insufficientFunds = value;
    }

    void reversalRefused(boolean value) {
        this.reversalRefused = value;
    }

    void notReversible(boolean value) {
        this.notReversible = value;
    }

    /** Les {@code n} prochains appels sont comptabilisés, mais la réponse n'arrive jamais à l'appelant. */
    void loseNextResponses(int n) {
        lostResponses.set(n);
    }

    int calls() {
        return calls.get();
    }

    /** Nombre de transactions réellement comptabilisées (une par clé). */
    int postedTransactions() {
        return posted.size();
    }

    List<String> receivedTokens() {
        return receivedTokens;
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        receivedTokens.add(auth == null ? "" : auth.replaceFirst("^Bearer ", ""));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (down) {
            respond(exchange, 503, "{}");
            return;
        }
        boolean reversal = exchange.getRequestURI().getPath().endsWith("/reversals");
        if (reversal && notReversible) {
            respond(exchange, 409, "{\"code\":\"NON_CONTREPASSABLE\",\"detail\":\"déjà contrepassée\"}");
            return;
        }
        if ((reversal && reversalRefused) || (!reversal && insufficientFunds)) {
            respond(exchange, 422, "{\"code\":\"FONDS_INSUFFISANTS\",\"detail\":\"solde insuffisant\"}");
            return;
        }
        Matcher m = ID.matcher(body);
        if (!m.find()) {
            respond(exchange, 400, "{}");
            return;
        }
        String key = m.group(1) + ":" + m.group(2);
        boolean replay = posted.containsKey(key);
        UUID id = posted.computeIfAbsent(key, k -> UUID.randomUUID());
        if (lostResponses.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            respond(exchange, 503, "{}");
            return;
        }
        respond(exchange, replay ? 200 : 201, "{\"id\":\"" + id + "\"}");
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
