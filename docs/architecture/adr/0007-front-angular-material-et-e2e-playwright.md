# ADR-007 : Front Angular avec Angular Material et NgRx SignalStore, E2E avec Playwright

**État** : acceptée (le front arrive à l'incrément 5).

## Contexte

Le portfolio montre déjà un front Angular 22 en CSS maison, sans bibliothèque de composants ni gestion d'état dédiée (StockPilot). Le front de FinFlow doit donc apporter autre chose. Le blueprint hésite entre Angular Material et Tailwind, et prévoit Playwright pour les tests de bout en bout.

## Décision

- **Angular Material** : tableaux paginés et triables, formulaires, dialogues, thème. C'est le standard des back-offices financiers en Angular.
- **NgRx SignalStore** pour l'état partagé (organisation courante, comptes, paiements en cours). Les signaux natifs restent utilisés pour l'état local.
- **Connexion OIDC à Keycloak** avec le flux *authorization code* et PKCE, et un intercepteur qui ajoute le jeton aux appels de la Gateway.
- **Playwright** pour les tests de bout en bout. C'est une exception assumée à la règle habituelle du portfolio (Cypress) : Cypress figure déjà dans StockPilot et ContractFlow.
- Le front ne calcule jamais un solde et ne prend aucune décision de risque : il affiche ce que renvoient les services.

## Conséquences

- Les mises à jour en temps réel (SSE) et les écrans d'administration viendront après les parcours comptes, paiements et journal.
