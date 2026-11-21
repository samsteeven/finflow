# ADR-010 : Validation par appel synchrone à Account, limites sous verrou consultatif

**État** : acceptée.

## Contexte

Avant d'accepter un transfert, Payment doit vérifier trois choses :
- les deux comptes existent, appartiennent à l'organisation, sont actifs et sont en EUR ;
- le montant respecte la limite par opération du compte source ;
- le cumul du jour respecte sa limite journalière.

Le blueprint prévoit un appel REST synchrone pour vérifier un compte, et le service Risk est reporté (ADR-001).

## Décision

- **Payment appelle Account en REST** (`GET /api/v1/accounts/{id}`) en **relayant le jeton de l'utilisateur**. Account applique donc lui-même son contrôle d'accès et sa RLS : un compte d'une autre organisation répond 404, exactement comme un compte inexistant. Payment n'a aucun droit propre sur les comptes.
- Délais explicites : 1 seconde pour la connexion, 2 secondes pour la réponse. Un 404 est une **réponse métier** : le paiement est rejeté. Une panne, un délai dépassé ou un 5xx sont des **incidents techniques** : le paiement reste en `VALIDATING` et sera repris (rejeu de la même `Idempotency-Key` dès maintenant, reprise automatique par la saga à l'incrément 4). Les appels HTTP se font **hors transaction** : aucune connexion PostgreSQL n'est retenue pendant un appel réseau.
- Les **limites** sont évaluées par un `RiskCheck` interne à Payment (`LimitsRiskCheck`), remplaçable plus tard par un appel au service Risk. La limite journalière porte sur les paiements du jour (UTC) du compte source en `PROCESSING`, `COMPLETED` ou `REFUND_PENDING`.
- Le contrôle du cumul et l'ajout de l'événement se font dans une transaction qui prend d'abord `pg_advisory_xact_lock` sur le compte source. Deux paiements simultanés du même compte sont ainsi **sérialisés** : ils ne peuvent pas lire tous les deux un cumul qui ne compte pas l'autre. Le verrou est relâché à la fin de la transaction.
- Chaque décision de rejet porte un motif explicable : règle, valeur observée, seuil.

## Conséquences

- Les fonds disponibles ne sont pas vérifiés ici : c'est le rôle du Ledger, source de vérité (incrément 3). Ces limites sont des règles de risque, pas un solde.
- Une reprise sans utilisateur (saga en tâche de fond) n'aura pas de jeton à relayer : elle passera par un jeton de service (`client_credentials`), ajouté avec la saga.
- Le verrou consultatif ne protège qu'à l'intérieur de Payment. Le Ledger aura son propre contrôle de concurrence sur les soldes.
