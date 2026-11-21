# ADR-012 : Comptabilisation orchestrée par Payment, d'abord en synchrone

**État** : acceptée.

## Contexte

Le blueprint confie la saga à Payment : comptes, risque, comptabilisation par le Ledger, puis publication. Il prévoit aussi le comportement en cas de défaillance :
- Ledger indisponible : la saga attend et réessaie de façon contrôlée ;
- réponse du Ledger perdue : la reprise utilise la même clé d'idempotence ;
- comptabilisation faite : la correction passe par une compensation explicite.

Kafka et l'outbox arrivent à l'incrément 4.

## Décision

- Après la validation, Payment appelle `POST /internal/v1/ledger/transfers` avec la clé `payment:<id>`. Selon la réponse, le paiement devient :
  - `COMPLETED` (avec l'identifiant de la transaction comptable) si le Ledger a comptabilisé ;
  - `REJECTED` (règle `FONDS_INSUFFISANTS`, depuis `PROCESSING`) si le Ledger refuse pour des raisons métier ;
  - `FAILED` en cas de panne, de délai dépassé ou d'erreur serveur. L'issue est alors inconnue, car le Ledger a peut-être comptabilisé.
- **Reprise** : un paiement `FAILED` est repris (`PaymentRetried`, au plus 3 fois) lors d'un rejeu de la même `Idempotency-Key`. La reprise renvoie la **même clé comptable** : si la première écriture avait réussi, le Ledger renvoie la transaction existante et rien n'est comptabilisé deux fois. Depuis l'incrément 4, un worker reprend aussi les paiements en suspens sans attendre un rejeu ([ADR-015](0015-reprise-automatique-de-la-saga.md)).
- **Remboursement** : `POST /api/v1/payments/{id}/refunds` fait passer le paiement en `REFUND_PENDING`, puis demande au Ledger une contrepassation (`refund:<id>`). Selon la réponse :
  - `REFUNDED` si la contrepassation est faite ;
  - retour à `COMPLETED` (`RefundRejected`) si le destinataire n'a plus les fonds ;
  - maintien en `REFUND_PENDING` en cas de panne, et un nouvel appel reprend avec la même clé.
- Les opérations d'écriture du Ledger sont sous `/internal/v1/…` et **la Gateway ne les route pas** : depuis l'extérieur, on ne peut ni transférer ni contrepasser sans passer par les contrôles de Payment. Elles valident quand même le jeton relayé et le tenant. Elles acceptent aussi un jeton de service (`client_credentials`) pour la reprise de la saga en tâche de fond ([ADR-015](0015-reprise-automatique-de-la-saga.md)).
- Les dépôts simulés (alimentation d'un compte depuis le compte de compensation) sont publics pour un `tenant-admin` : ils remplacent le prestataire externe simulé du blueprint.
- Les appels réseau restent **hors transaction**. Chaque décision s'écrit dans une transaction courte, avec un contrôle de version (ADR-008).

## Conséquences

- La saga est encore synchrone : la réponse au `POST /payments` arrive après la comptabilisation. Une panne de Payment entre l'écriture comptable et `PaymentCompleted` laisse le paiement en `PROCESSING` jusqu'au rejeu suivant. C'est ce que corrigera la reprise automatique de l'incrément 4.
- Le schéma de la machine à états change : `PROCESSING → REJECTED` (refus du Ledger) et `REFUND_PENDING → COMPLETED` (remboursement refusé) s'ajoutent à l'ADR-008.
