package com.finflow.ledger.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Une transaction à comptabiliser. Elle est équilibrée dès sa construction : la base la
 * contrôle de nouveau au COMMIT (ADR-011), ce contrôle-ci évite seulement un aller-retour.
 */
public record Posting(UUID id, Kind kind, String idempotencyKey, String requestHash, UUID reversalOf,
                      String description, List<Line> lines) {

    public Posting {
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new InvalidPostingException("Une transaction comporte au moins deux lignes");
        }
        long debits = lines.stream().filter(l -> l.side() == Side.DEBIT).mapToLong(Line::amountCents).sum();
        long credits = lines.stream().filter(l -> l.side() == Side.CREDIT).mapToLong(Line::amountCents).sum();
        if (debits != credits) {
            throw new InvalidPostingException("Transaction déséquilibrée : débits " + debits + ", crédits " + credits);
        }
    }

    /** Le compte source est débité, le compte de destination crédité. */
    public static Posting transfer(String key, String hash, UUID source, UUID destination, long amountCents,
                                   String description) {
        if (source.equals(destination)) {
            throw new InvalidPostingException("Le compte source et le compte de destination doivent différer");
        }
        return new Posting(UUID.randomUUID(), Kind.TRANSFER, key, hash, null, description,
                List.of(new Line(source, Side.DEBIT, amountCents), new Line(destination, Side.CREDIT, amountCents)));
    }

    /** Le compte de compensation est débité, le compte client crédité. */
    public static Posting deposit(String key, String hash, UUID clearing, UUID account, long amountCents,
                                  String description) {
        return new Posting(UUID.randomUUID(), Kind.DEPOSIT, key, hash, null, description,
                List.of(new Line(clearing, Side.DEBIT, amountCents), new Line(account, Side.CREDIT, amountCents)));
    }

    /** L'inverse ligne à ligne de la transaction d'origine. */
    public static Posting reversal(String key, String hash, UUID original, List<Line> originalLines,
                                   String description) {
        List<Line> inverse = new ArrayList<>();
        for (Line line : originalLines) {
            inverse.add(new Line(line.accountId(), line.side().opposite(), line.amountCents()));
        }
        return new Posting(UUID.randomUUID(), Kind.REVERSAL, key, hash, original, description, inverse);
    }

    /** Variation de solde de chaque compte touché, triée par identifiant. */
    public Map<UUID, Long> deltas() {
        Map<UUID, Long> deltas = new TreeMap<>();
        for (Line line : lines) {
            deltas.merge(line.accountId(), line.delta(), Long::sum);
        }
        return deltas;
    }
}
