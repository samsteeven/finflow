# ADR-008 : Event sourcing du paiement, en JDBC, avec version optimiste et snapshots

**État** : acceptée.

## Contexte

Le blueprint applique l'event sourcing à Payment et Ledger seulement : chaque transition d'un paiement est un événement persisté, et l'état courant se reconstruit en les rejouant. Il faut aussi lister les paiements et additionner les montants du jour pour les limites, sans rejouer tous les événements à chaque fois.

## Décision

- **Agrégat en Java pur** (`Payment`, sans annotation de persistance). Ses événements forment une interface scellée (`PaymentEvent`) dont chaque événement est un `record`. Les décisions (`create`, `validate`, `reject`, `startProcessing`…) vérifient la machine à états et **renvoient** un événement. L'état n'est modifié que par `apply(événement)`, qu'on appelle à la décision comme au rejeu.
- **Machine à états** : `CREATED → VALIDATING → PROCESSING → COMPLETED`, avec trois autres issues :
  - `VALIDATING → REJECTED` ;
  - `PROCESSING → REJECTED`, quand le Ledger refuse pour fonds insuffisants ([ADR-012](0012-comptabilisation-par-payment.md)) ;
  - `PROCESSING → FAILED → PROCESSING`, pour une reprise bornée.

  Remboursement : `COMPLETED → REFUND_PENDING → REFUNDED`, ou retour `REFUND_PENDING → COMPLETED` si le Ledger refuse la contrepassation (`RefundRejected`). Toute autre transition lève une `IllegalTransitionException`.
- **Event store en JDBC** (`payment_events`), sans JPA : un journal en ajout seul se décrit mieux en SQL qu'en entités. Clé primaire `(payment_id, version)`. Ajouter un événement à la version *n* + 1 alors qu'un autre traitement l'a déjà écrite viole la clé primaire. Cette violation devient une `ConcurrencyConflictException` (concurrence optimiste, sans verrou tenu pendant les appels réseau).
- Le rôle applicatif n'a que `SELECT` et `INSERT` sur `payment_events` : **un événement ne se modifie ni ne se supprime**, et c'est PostgreSQL qui l'impose.
- **Enveloppe** : `event_id`, `event_type`, `event_version`, `tenant_id`, `correlation_id`, `causation_id`, `occurred_at` et `payload` en `jsonb`. Le schéma de chaque événement est versionné dès le départ pour préparer Kafka (incrément 4).
- **Snapshots** (`payment_snapshots`) : un instantané de l'état est pris toutes les `5` versions. La lecture charge le dernier instantané, puis rejoue seulement les événements suivants. Un paiement compte rarement plus de cinq événements, mais le mécanisme est en place et testé : l'état reconstruit avec ou sans snapshot doit être identique.
- **Projection en ligne** (`payment_summaries`) : la même transaction qui ajoute un événement met à jour une ligne de résumé (statut, montant, comptes, version). C'est le modèle de lecture des listes et des cumuls journaliers. La projection ignore un événement dont la version n'est pas supérieure à la sienne.
- Le **gestionnaire de transactions JDBC** du `security-starter` (`TenantAwareDataSourceTransactionManager`) pose le tenant à chaque transaction, comme son équivalent JPA (ADR-005).

## Conséquences

- L'historique d'un paiement se consulte tel quel : `GET /api/v1/payments/{id}/events`. C'est le point de départ de la démonstration de traçabilité.
- La projection peut toujours se reconstruire depuis le journal ; le journal fait foi.
- L'outbox (incrément 4) s'ajoutera dans la même transaction que l'événement, sans changer l'agrégat.
