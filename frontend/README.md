# Front FinFlow

Angular 22 (sans zone, composants autonomes), Angular Material, NgRx SignalStore, `oidc-client-ts`.
Les décisions sont dans l'[ADR-016](../docs/architecture/adr/0016-front-authentification-et-verite-des-donnees.md).

| Commande | Effet |
|---|---|
| `npm ci` | installe les dépendances |
| `npx ng serve` | sert le front sur http://localhost:4200 (la pile doit tourner, voir le README racine) |
| `npx ng test --watch=false` | tests unitaires (Vitest) |
| `npx playwright test` | parcours de bout en bout contre la vraie pile |
| `E2E_PANNES=1 npx playwright test pannes` | ajoute l'arrêt réel du Ledger |

Adresses de la pile : `src/app/core/config.ts`.
