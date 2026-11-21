# Vision et architecture cible

Ce document résume le blueprint du projet : ce que FinFlow doit devenir, et dans quel ordre. Ce qui existe déjà est indiqué dans le [README](../../README.md). Les écarts au blueprint d'origine sont signalés et justifiés par un ADR.

## Le produit

Une plateforme B2B multi-tenant de **simulation** d'opérations financières : des organisations clientes gèrent leurs comptes, font des transferts, suivent leurs paiements et consultent un historique comptable, chacune dans un espace isolé. Aucun vrai fonds n'est manipulé, et l'on ne reproduit pas l'infrastructure réglementaire d'une banque. Le premier périmètre se limite aux **transferts internes en EUR** ; les opérations externes passeront par un prestataire simulé.

L'intérêt technique est la **cohérence financière dans un système distribué** : pannes, messages dupliqués, traitements concurrents et opérations asynchrones ne doivent jamais produire de double débit ni d'écriture déséquilibrée.

### Le scénario de référence

L'organisation Alpha possède un compte A (10 000 €) et un compte B (2 000 €), et transfère 1 500 € de A vers B. FinFlow authentifie l'utilisateur et son organisation, vérifie que A appartient à Alpha, contrôle les limites, vérifie les fonds, enregistre les écritures, publie les événements, met à jour les soldes de consultation et notifie. Si un service tombe au milieu, l'opération reprend ou se compense, sans double débit.

## Architecture

Des microservices Spring Boot autonomes, chacun avec sa base, derrière une API Gateway ; Keycloak fournit l'identité ; Kafka transporte les événements.

```
Angular (Material, SignalStore, OIDC PKCE)
   │
   ▼
API Gateway (Spring Cloud Gateway) ── valide le JWT, retire les en-têtes de tenant forgés
   │ REST (jeton relayé)
   ├──► tenant-service   ── finflow_tenant   (organisations, appartenances)
   ├──► account-service  ── finflow_account  (comptes, limites, soldes projetés)
   ├──► payment-service  ── finflow_payment  (event store, saga, idempotence, outbox)
   └──► ledger-service   ── finflow_ledger   (partie double, event store, outbox)
                 │ outbox ─► Kafka (topics *.events) ─► consommateurs idempotents
Keycloak (OIDC) : identités, sessions, rôles, attribut tenant_id
```

**Database per Service** : un service ne lit jamais la base d'un autre. En local comme sur AWS, les bases logiques partagent un même cluster PostgreSQL pour maîtriser les coûts, mais chaque service a ses propres rôles et n'a aucun droit sur les autres bases.

### Périmètre : un noyau de quatre services

Le blueprint prévoit huit services métier. FinFlow construit d'abord un **noyau** : Tenant, Account, Payment, Ledger et la Gateway ([ADR-001](adr/0001-noyau-de-quatre-services.md)). Risk, Reconciliation, Notification et Reporting viennent ensuite, si le temps le permet. Tant que Risk n'existe pas, Payment applique lui-même les limites portées par le compte ; c'est l'étape « risque » de la saga, qu'un appel au futur service remplacera.

| Service | Responsabilité | Noyau |
|---|---|---|
| Tenant | Organisations, appartenances, rôles métier, contexte tenant | oui |
| Account | Comptes, types, statuts, limites, soldes projetés (vue de consultation, pas la vérité comptable) | oui |
| Payment | Cycle de vie du paiement, saga orchestrée, idempotence, remboursements | oui |
| Ledger | Partie double, journal immuable, soldes comptables : **source de vérité** | oui |
| Risk | Règles explicables, décisions ACCEPT / REVIEW / REJECT | plus tard |
| Reconciliation | Import CSV, matching, écarts, rapprochement manuel | plus tard |
| Notification | E-mails, alertes, reprises | plus tard |
| Reporting | Read models construits depuis les événements | plus tard |

## Cohérence financière

| Mécanisme | Rôle |
|---|---|
| Kafka | Transport et distribution des événements ; **pas** une source de vérité |
| Event Sourcing | Historique des changements, pour **Payment et Ledger uniquement** |
| Transactional Outbox | Publication fiable : changement métier et événement dans la même transaction SQL |

- **Paiement** : machine à états `CREATED → VALIDATING → PROCESSING → COMPLETED`, avec `REJECTED`, `FAILED`, `REFUND_PENDING → REFUNDED`. Chaque transition est un événement persisté ; l'état courant se reconstruit en rejouant les événements, avec des snapshots.
- **Grand livre** : total des débits = total des crédits, contrôlé **en base** dans la transaction qui insère les écritures (une validation Java ne protège pas des traitements concurrents). Une écriture validée ne se modifie ni ne se supprime : une correction est une contrepassation.
- **Saga orchestrée par Payment** : comptes valides (REST) → limites → demande de comptabilisation au Ledger (REST, clé d'idempotence) → `PaymentCompleted` publié par l'outbox. Ledger indisponible : la saga attend et reprend avec la même clé. Une fois comptabilisé, on n'annule pas : on crée une opération compensatoire explicite et auditée.
- **Livraison au moins une fois** : chaque consommateur est idempotent (table des événements traités) et vérifie la version d'agrégat pour ignorer un événement ancien.
- **Idempotence des API** : `Idempotency-Key` sur les opérations sensibles, `UNIQUE (tenant_id, idempotency_key)` ; le Ledger a sa propre clé par opération comptable.
- **Montants en centimes** (`long` en Java, `bigint` en base), jamais en flottant ; devise obligatoire (EUR seule au départ).

### Événements

Enveloppe commune : `eventId`, `eventType`, `eventVersion`, `aggregateId`, `aggregateVersion`, `tenantId`, `correlationId`, `causationId`, `occurredAt`, `producer`, `payload`. Clé de partition `tenantId:aggregateId` : ordre garanti par agrégat, pas d'ordre global.

Topics du noyau : `tenant.events`, `account.events`, `payment.events`, `ledger.events`. Contrats versionnés ; Avro et Schema Registry sont prévus avec Kafka ([ADR-006](adr/0006-pile-locale-sobre-en-memoire.md) explique pourquoi ils n'arrivent pas dès l'incrément 1).

## Multi-tenancy

**Schéma partagé, `tenant_id` sur chaque table métier, Row-Level Security PostgreSQL forcée** ([ADR-005](adr/0005-rls-et-contexte-par-transaction.md)) :

1. Le tenant vient du **jeton validé** (attribut Keycloak non modifiable par l'utilisateur), jamais d'une valeur envoyée par le front ([ADR-004](adr/0004-keycloak-et-tenant-dans-le-jeton.md)).
2. Le contexte est posé **par transaction** (`set_config('app.tenant_id', …, true)`), jamais sur une connexion réutilisée.
3. Le rôle de connexion de l'application n'est pas propriétaire des tables et n'a pas `BYPASSRLS` ; sans contexte, il ne voit aucune ligne.
4. Clés étrangères et contraintes importantes incluent le tenant ; les événements Kafka portent `tenantId` et les consommateurs le vérifient.
5. Les tests tentent systématiquement de lire les données d'un autre tenant.

La RLS est une défense supplémentaire : les autorisations applicatives restent vérifiées.

## API

Tout passe par la Gateway, sous `/api/v1`. DTO séparés des entités JPA, Bean Validation, erreurs en Problem Details (RFC 9457), OpenAPI, pagination, `Idempotency-Key` sur les opérations sensibles.

| Service | Endpoint | Fonction |
|---|---|---|
| Tenant | `POST /tenants`, `GET /tenants/current` | Créer une organisation, lire la sienne |
| Account | `POST /accounts`, `GET /accounts`, `GET /accounts/{id}/balance` | Comptes et soldes |
| Payment | `POST /payments`, `GET /payments/{id}`, `POST /payments/{id}/refunds` | Paiements et remboursements |
| Ledger | `GET /ledger/transactions/{id}` | Écritures |

## Pile technique

| Couche | Choix |
|---|---|
| Langage | **Java 25 LTS** ([ADR-003](adr/0003-java-25-spring-boot-4.md)) |
| Backend | Spring Boot 4.2, Spring Cloud 2026.0, Spring Cloud Gateway |
| Sécurité | Keycloak 26 (OIDC), Spring Security resource server |
| Persistance | PostgreSQL 18, Spring Data JPA, Flyway |
| Événements | Apache Kafka 4 (KRaft) ; Avro et Schema Registry avec l'incrément Kafka |
| Résilience | Resilience4j |
| Front | Angular, **Angular Material, NgRx SignalStore**, OIDC avec PKCE ([ADR-007](adr/0007-front-angular-material-et-e2e-playwright.md)) |
| Tests | JUnit 5, Mockito, Testcontainers, REST Assured, Spring Cloud Contract, **Playwright** (E2E), k6, Toxiproxy |
| Observabilité | OpenTelemetry, Prometheus, Grafana |
| Livraison | Docker, GitHub Actions, Terraform, AWS ECS Fargate |

Monorepo Git, un module Maven par service sous un parent commun ([ADR-002](adr/0002-monorepo-maven.md)) : chaque service se construit, se teste et se déploie seul ; seuls les contrats d'événements et les starters techniques sont partagés, jamais les entités ni les repositories.

## Contraintes du poste et du budget

- **Mémoire** : le poste a 15 Go de RAM. Heap JVM bornées, Kafka KRaft à un nœud, profils Docker Compose ([ADR-006](adr/0006-pile-locale-sobre-en-memoire.md)).
- **AWS** : MSK, RDS et ElastiCache coûtent cher même au repos. Le Terraform sera écrit et validé, mais **rien n'est créé sur AWS sans accord explicite**, après chiffrage. Le développement local reste complet.

## Ce que FinFlow démontre dans le portfolio

| Projet | Ce qu'il démontre |
|---|---|
| DevPulse (.NET) | Microservices, observabilité, cloud |
| EvalForge (Python) | IA, évaluation, automatisation |
| StockPilot (NestJS) | Transactions, concurrence, RLS, backend métier |
| ContractFlow (AdonisJS) | Moteur de validation, versions, sécurité, SaaS full-stack |
| **FinFlow (Spring Boot)** | **Cohérence distribuée : event sourcing, saga, outbox, Kafka, partie double** |

Trois démonstrations sont visées : **une panne distribuée** (arrêter Kafka ou le Ledger pendant un paiement, puis reprendre sans double débit), **l'isolation multi-tenant** (une organisation ne lit rien d'une autre, même en connaissant les identifiants) et **la traçabilité comptable** (d'un paiement à ses événements, ses écritures et ses soldes).

### Scénarios critiques à automatiser

1. Deux dépenses simultanées du même solde : aucun découvert.
2. Le même paiement soumis dix fois : un seul mouvement comptable.
3. Kafka tombe après l'enregistrement d'un paiement : l'outbox publie plus tard.
4. Le Ledger tombe après réception d'une demande : la saga reprend sans double comptabilisation.
5. Un utilisateur du tenant A lit un compte du tenant B : refusé.
6. Un événement reçu deux fois : une seule projection appliquée.
7. Un événement ancien arrive après un récent : la version d'agrégat empêche la régression.
8. Une compensation : écritures d'origine intactes, nouvelles écritures équilibrées.

## Feuille de route

Le blueprint prévoit 16 semaines pour huit services. Avec le noyau, la feuille de route devient :

| Incrément | Contenu |
|---|---|
| 1 | **Fondations** : ADR, monorepo Maven, Docker Compose, Keycloak, Gateway, CI, contexte tenant et RLS, tenant-service |
| 2 | **Account et Payment** : comptes et limites, event sourcing du paiement, machine à états, `Idempotency-Key`, API REST |
| 3 | **Ledger** : partie double contrôlée en base (journal immuable = journal d'événements, soldes tenus par déclencheur), dépôts simulés, contrepassations, saga synchrone de Payment (comptabilisation, reprises, remboursements), concurrence ([ADR-011](adr/0011-grand-livre-en-partie-double.md), [ADR-012](adr/0012-comptabilisation-par-payment.md)) |
| 4 | **Kafka** : outbox transactionnelle de Payment et du Ledger, relais, contrats Avro et Schema Registry, consommateurs idempotents avec version d'agrégat et file morte (projections d'Account), reprise automatique de la saga par un compte de service ([ADR-013](adr/0013-outbox-kafka-et-avro.md), [ADR-014](adr/0014-consommateurs-idempotents.md), [ADR-015](adr/0015-reprise-automatique-de-la-saga.md)) |
| 5 | **Front Angular** : Material, SignalStore, connexion OIDC PKCE, comptes, paiements, journal et historique d'événements ; Playwright contre la vraie pile (parcours, isolation, rôles, panne du Ledger) ([ADR-016](adr/0016-front-authentification-et-verite-des-donnees.md)) |
| 6 | **Production** : OpenTelemetry, Prometheus, Grafana, Resilience4j, Toxiproxy, k6 |
| 7 | **AWS** : images Docker, Terraform (ECS Fargate, RDS, MSK ou alternative moins chère), déploiement sur accord explicite |
| Plus tard | Risk, Reconciliation, Notification, Reporting |
