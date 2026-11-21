# FinFlow

Plateforme B2B multi-tenant de **simulation** d'opérations financières : comptes, transferts, paiements et grand livre en partie double, construite en microservices Spring Boot avec un front Angular. Aucun vrai fonds n'est manipulé.

Le sujet du projet est la **cohérence financière dans un système distribué** : pannes, messages dupliqués, traitements concurrents et opérations asynchrones ne doivent jamais produire de double débit ni d'écriture déséquilibrée. La cible complète (event sourcing, saga, outbox, Kafka, partie double) est décrite dans [docs/architecture/vision.md](docs/architecture/vision.md), et les décisions dans les [ADR](docs/architecture/adr/README.md).

## État : incréments 1 et 2, fondations, comptes et paiements

| Élément | Contenu |
|---|---|
| Monorepo Maven | Parent commun Spring Boot 4.2 / Spring Cloud 2026.0, Java 25, un module par service ([ADR-002](docs/architecture/adr/0002-monorepo-maven.md)) |
| `api-gateway` | Spring Cloud Gateway : valide le jeton (signature, émetteur, audience, expiration), relaie le jeton, retire les en-têtes d'identité forgés (`X-Tenant-Id`, `X-User-Id`, `Cookie`), CORS limité au front |
| `tenant-service` | Organisations et appartenances, `POST /api/v1/tenants` (administrateur de plateforme), `GET /api/v1/tenants/current`, `GET /api/v1/tenants/{id}`, `GET /api/v1/tenants/current/members` ; erreurs en Problem Details |
| `account-service` | Comptes en EUR, statuts (`ACTIVE`, `FROZEN`, `CLOSED`), limites par opération et journalière en centimes ; compte clôturé figé par un déclencheur PostgreSQL |
| `payment-service` | Paiement en **event sourcing** (JDBC, version optimiste, snapshots toutes les 5 versions, projection en ligne), machine à états complète, **`Idempotency-Key`** obligatoire, validation par appel à Account en relayant le jeton, limites sous verrou consultatif par compte source ; `GET /api/v1/payments/{id}/events` pour l'historique ([ADR-008](docs/architecture/adr/0008-paiement-event-sourcing.md), [ADR-009](docs/architecture/adr/0009-idempotency-key.md), [ADR-010](docs/architecture/adr/0010-validation-synchrone-et-limites.md)) |
| `shared/security-starter` | Conversion du jeton Keycloak (rôles du realm, claim `tenant_id`), `TenantContext`, gestionnaires de transactions JPA et JDBC qui posent le tenant **à chaque transaction**, chaîne de sécurité commune |
| `shared/test-support` | Faux Keycloak (JWKS local, jetons signés validés par la configuration de production) et base PostgreSQL de test avec rôles propriétaire et applicatif |
| Isolation | RLS PostgreSQL **forcée**, rôle applicatif sans `BYPASSRLS` ni droit de suppression, refus par défaut sans contexte ([ADR-005](docs/architecture/adr/0005-rls-et-contexte-par-transaction.md)) |
| Keycloak | Realm `finflow` importé au démarrage : client `finflow-web` (PKCE S256), audience `finflow-api`, attribut `tenant_id` modifiable par un administrateur seulement ([ADR-004](docs/architecture/adr/0004-keycloak-et-tenant-dans-le-jeton.md)) |
| Docker Compose | PostgreSQL 18 (une base et deux rôles par service), Keycloak 26, Kafka 4 KRaft à un nœud (profil `kafka`) ([ADR-006](docs/architecture/adr/0006-pile-locale-sobre-en-memoire.md)) |
| CI | Tests unitaires et d'intégration (Testcontainers), puis vérification de la pile avec de vrais jetons Keycloak |

Un paiement validé s'arrête pour l'instant à l'état `PROCESSING` : sa comptabilisation par le Ledger arrive à l'incrément 3, la saga et Kafka à l'incrément 4. Le front Angular et AWS viennent ensuite (voir la [feuille de route](docs/architecture/vision.md#feuille-de-route)).

## Ce que les tests vérifient

- **Isolation en base** (`RowLevelSecurityIT`, connecté avec le rôle applicatif) :
  - une organisation ne lit pas une autre, même par son identifiant ;
  - sans contexte, aucune ligne n'est visible ;
  - écrire ou déplacer une ligne vers un autre tenant est refusé par PostgreSQL ;
  - le contexte ne fuit pas d'une transaction à la suivante sur la même connexion du pool ;
  - le rôle applicatif ne peut ni désactiver la RLS ni supprimer.
- **API** (`TenantApiIT`) :
  - les jetons forgés, expirés, destinés à une autre audience ou venant d'un autre émetteur sont refusés ;
  - seuls les rôles prévus créent une organisation ou listent les membres ;
  - une organisation d'un autre tenant répond 404, comme un identifiant inexistant ;
  - un en-tête ou un paramètre `tenantId` envoyé par le client est ignoré.
- **Gateway** (`GatewayRoutingTest`) :
  - rien n'atteint un service sans jeton valide ;
  - les en-têtes d'identité forgés sont retirés ;
  - CORS accepte le front local et refuse les autres origines.
- **Comptes** (`AccountApiIT`, `AccountDatabaseIT`) :
  - cycle de vie et rôles ;
  - devise et cohérence des limites imposées par la base ;
  - un compte clôturé ne se modifie plus, même en SQL ;
  - aucune lecture croisée entre organisations.
- **Paiements** (`PaymentTest`, `PaymentApiIT`, `PaymentEventStoreIT`, `PaymentConcurrencyIT`) :
  - machine à états, reprises bornées, rejeu identique avec ou sans snapshot ;
  - **dix envois simultanés de la même demande créent un seul paiement** ;
  - **deux paiements simultanés ne dépassent jamais la limite journalière** ;
  - une même clé avec un autre corps est refusée (422) ;
  - une panne d'Account laisse le paiement en `VALIDATING`, et le rejeu le reprend ;
  - le journal est immuable pour le rôle applicatif ;
  - deux écritures concurrentes de la même version ne passent pas toutes les deux ;
  - la projection ne recule jamais.
- **Pile réelle** (`scripts/verifier-pile.sh`, dans la CI) : jetons émis par Keycloak, claim `tenant_id`, audience, routage par la Gateway, isolation entre Alpha et Beta, transfert de 1 500 €, rejeu idempotent, historique des événements, refus par limite et refus d'un compte d'une autre organisation.

Les régressions de sécurité et de cohérence sont vérifiées par mutation : 8 sur 8 détectées à l'incrément 1, 15 sur 15 à l'incrément 2.

## Lancer en local

Prérequis : Java 25, Docker. Maven est fourni par le wrapper.

```bash
docker compose up -d
```

```bash
cd backend && ./mvnw verify
```

```bash
cd backend && ./mvnw -pl tenant-service spring-boot:run -Dspring-boot.run.profiles=demo
```

```bash
cd backend && ./mvnw -pl account-service spring-boot:run
```

```bash
cd backend && ./mvnw -pl payment-service spring-boot:run
```

```bash
cd backend && ./mvnw -pl api-gateway spring-boot:run
```

```bash
bash scripts/verifier-pile.sh
```

| Service | Adresse |
|---|---|
| Gateway | http://localhost:8080 |
| tenant-service | http://localhost:8081 |
| account-service | http://localhost:8082 |
| payment-service | http://localhost:8083 |
| Keycloak | http://localhost:8180 (console : `admin` / `admin_dev`) |
| PostgreSQL | `localhost:5439` |
| Kafka (profil `kafka`) | `localhost:9094` |

Comptes du realm local (mot de passe `finflow-dev`) :

| Utilisateur | Rôle | Organisation |
|---|---|---|
| `plateforme` | `platform-admin` | aucune |
| `alice` | `tenant-admin` | Alpha Industries |
| `bruno` | `tenant-admin` | Beta Logistique |

Le client `finflow-dev-cli`, qui délivre des jetons par mot de passe pour les scripts, n'existe que dans ce realm de développement. Le realm se régénère avec `python infrastructure/keycloak/generer-realm.py`.

## Mémoire et coûts

Le poste de développement a 15 Go de RAM. La pile de l'incrément 1 (PostgreSQL, Keycloak, Kafka, Gateway, tenant-service) occupait environ 1,4 Go ; chaque service Spring Boot ajoute environ 250 Mo, avec des heap JVM bornées. Kafka n'est lancé qu'avec le profil `kafka`. Rien n'est créé sur AWS sans accord explicite, après chiffrage : MSK et RDS coûtent cher même au repos.
