# Décisions d'architecture

| N° | Décision | État |
|---|---|---|
| [001](0001-noyau-de-quatre-services.md) | Un noyau de quatre services avant les huit du blueprint | acceptée |
| [002](0002-monorepo-maven.md) | Monorepo Maven, un module par service sous un parent commun | acceptée |
| [003](0003-java-25-spring-boot-4.md) | Java 25, Spring Boot 4.2 et Spring Cloud 2026.0 | acceptée |
| [004](0004-keycloak-et-tenant-dans-le-jeton.md) | Keycloak, tenant porté par le jeton | acceptée |
| [005](0005-rls-et-contexte-par-transaction.md) | RLS forcée, contexte tenant posé à chaque transaction | acceptée |
| [006](0006-pile-locale-sobre-en-memoire.md) | Une pile locale sobre en mémoire | acceptée |
| [007](0007-front-angular-material-et-e2e-playwright.md) | Front Angular Material et SignalStore, E2E Playwright | acceptée |
| [008](0008-paiement-event-sourcing.md) | Event sourcing du paiement, en JDBC, avec version optimiste et snapshots | acceptée |
| [009](0009-idempotency-key.md) | Idempotency-Key sur la création d'un paiement | acceptée |
| [010](0010-validation-synchrone-et-limites.md) | Validation par appel synchrone à Account, limites sous verrou consultatif | acceptée |
| [011](0011-grand-livre-en-partie-double.md) | Grand livre en partie double, garanti par PostgreSQL | acceptée |
| [012](0012-comptabilisation-par-payment.md) | Comptabilisation orchestrée par Payment, d'abord en synchrone | acceptée |
