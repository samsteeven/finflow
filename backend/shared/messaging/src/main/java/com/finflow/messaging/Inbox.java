package com.finflow.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Table des événements déjà traités par un consommateur (ADR-014). L'insertion se fait dans la
 * transaction qui applique l'effet : un événement livré deux fois ne produit qu'un effet, et un
 * échec annule à la fois l'effet et la marque.
 */
public class Inbox {

    private final JdbcTemplate jdbc;

    public Inbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code true} à la première livraison, {@code false} si l'événement a déjà été traité. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String consumer, String eventId) {
        return jdbc.update("""
                insert into inbox (consumer, event_id) values (?, ?::uuid)
                on conflict do nothing
                """, consumer, eventId) == 1;
    }
}
