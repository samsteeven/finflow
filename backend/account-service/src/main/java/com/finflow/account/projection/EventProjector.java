package com.finflow.account.projection;

import com.finflow.events.EventEnvelope;
import com.finflow.events.PaymentChanged;
import com.finflow.events.PostedLine;
import com.finflow.events.Side;
import com.finflow.events.TransactionPosted;
import com.finflow.messaging.Envelopes;
import com.finflow.messaging.Inbox;
import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applique un événement aux projections (ADR-014). L'effet et la marque « déjà traité » sont dans
 * la même transaction : une livraison en double ne change rien, un échec n'applique rien.
 * L'événement s'exécute dans l'organisation qu'il déclare, après vérification de sa clé de
 * partition ; la RLS fait le reste.
 */
@Service
public class EventProjector {

    static final String CONSUMER = "account-service";
    private static final Logger log = LoggerFactory.getLogger(EventProjector.class);

    private final ProjectionRepository repository;
    private final Inbox inbox;
    private final TransactionTemplate tx;

    EventProjector(ProjectionRepository repository, Inbox inbox, TransactionTemplate tx) {
        this.repository = repository;
        this.inbox = inbox;
        this.tx = tx;
    }

    /**
     * @throws IllegalArgumentException si l'événement est mal formé : inutile de le rejouer, il va en file morte
     */
    public void apply(String messageKey, EventEnvelope event) {
        if (messageKey == null || !messageKey.equals(Envelopes.partitionKey(event))) {
            throw new IllegalArgumentException("Clé de partition incohérente avec l'enveloppe : " + messageKey);
        }
        TenantId tenant = TenantId.parse(event.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("tenantId invalide : " + event.getTenantId()));
        TenantContext.runAs(tenant, () -> tx.executeWithoutResult(status -> {
            if (!inbox.firstDelivery(CONSUMER, event.getEventId())) {
                log.debug("Événement {} déjà traité", event.getEventId());
                return;
            }
            switch (event.getPayload()) {
                case PaymentChanged payment -> project(tenant, event, payment);
                case TransactionPosted posted -> project(posted);
                default -> throw new IllegalArgumentException("Charge utile inconnue : " + event.getEventType());
            }
        }));
    }

    private void project(TenantId tenant, EventEnvelope event, PaymentChanged payment) {
        boolean applied = repository.upsertPayment(UUID.fromString(event.getAggregateId()), tenant.value(),
                UUID.fromString(payment.getSourceAccountId()), UUID.fromString(payment.getDestinationAccountId()),
                payment.getAmountCents(), payment.getStatus(), event.getAggregateVersion());
        if (!applied) {
            log.info("Événement {} du paiement {} ignoré : version {} déjà dépassée", event.getEventType(),
                    event.getAggregateId(), event.getAggregateVersion());
        }
    }

    private void project(TransactionPosted posted) {
        for (PostedLine line : posted.getLines()) {
            long delta = line.getSide() == Side.CREDIT ? line.getAmountCents() : -line.getAmountCents();
            repository.addToBalance(UUID.fromString(line.getAccountId()), delta);
        }
    }
}
