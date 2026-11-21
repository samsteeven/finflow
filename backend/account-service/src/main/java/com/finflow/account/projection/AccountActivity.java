package com.finflow.account.projection;

import java.util.List;
import java.util.UUID;

/** Vue de consultation d'un compte, construite depuis les événements : pas la vérité comptable. */
public record AccountActivity(UUID accountId, long projectedBalanceCents, List<PaymentActivity> payments) {
}
