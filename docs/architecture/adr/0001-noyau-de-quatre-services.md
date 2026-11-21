# ADR-001 : Un noyau de quatre services avant les huit du blueprint

**État** : acceptée.

## Contexte

Le blueprint décrit huit services métier (Tenant, Account, Payment, Ledger, Risk, Reconciliation, Notification, Reporting), une Gateway et Keycloak. Pour un projet de portfolio mené seul, c'est beaucoup de code peu différencié : Notification et Reporting refont ce que DevPulse et StockPilot montrent déjà (consommateurs, e-mails, projections). Le poste de développement n'a que 15 Go de RAM : dix JVM, Kafka, Keycloak et PostgreSQL ne tiennent pas ensemble.

Ce qui distingue FinFlow, c'est le chemin d'un transfert : compte, paiement, écritures comptables, événements. Il ne traverse que quatre services.

## Décision

- Le noyau comprend **Tenant, Account, Payment et Ledger**, plus la Gateway. Il est construit, testé et démontré en entier avant tout autre service.
- **Risk, Reconciliation, Notification et Reporting** sont reportés après le noyau, dans des incréments optionnels.
- En l'absence de Risk, l'étape « risque » de la saga applique les limites portées par le compte (plafond par opération, plafond journalier) derrière une interface `RiskCheck`. Le futur service Risk la remplacera par un appel REST, sans changer la saga.
- Les principes du blueprint restent tous valables : Database per Service, event sourcing limité à Payment et Ledger, saga orchestrée par Payment, outbox, consommation au moins une fois avec idempotence.

## Conséquences

- Les scénarios critiques (double dépense, rejeu, panne de Kafka ou du Ledger, isolation, événements en désordre, compensation) se démontrent tous avec le noyau.
- Le topic `risk.events` et les consommateurs Reporting et Notification n'existent pas tant que leurs services ne sont pas écrits.
- Le front n'affiche, au départ, ni tableau de bord analytique ni rapprochement.
