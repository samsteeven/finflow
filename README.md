# FinFlow

Plateforme B2B multi-tenant de **simulation** d'opérations financières : comptes, transferts, paiements et grand livre en partie double, construite en microservices Spring Boot avec un front Angular. Aucun vrai fonds n'est manipulé.

Le sujet du projet est la **cohérence financière dans un système distribué** : pannes, messages dupliqués, traitements concurrents et opérations asynchrones ne doivent jamais produire de double débit ni d'écriture déséquilibrée. La cible complète (event sourcing, saga, outbox, Kafka, partie double) est décrite dans [docs/architecture/vision.md](docs/architecture/vision.md), et les décisions dans les [ADR](docs/architecture/adr/README.md).

## État : incréments 1 à 6, fondations, comptes, paiements, grand livre, Kafka, front et production

| Élément | Contenu |
|---|---|
| Monorepo Maven | Parent commun Spring Boot 4.2 / Spring Cloud 2026.0, Java 25, un module par service ([ADR-002](docs/architecture/adr/0002-monorepo-maven.md)) |
| `api-gateway` | Spring Cloud Gateway : valide le jeton (signature, émetteur, audience, expiration), relaie le jeton, retire les en-têtes d'identité forgés (`X-Tenant-Id`, `X-User-Id`, `Cookie`), CORS limité au front |
| `tenant-service` | Organisations et appartenances, `POST /api/v1/tenants` (administrateur de plateforme), `GET /api/v1/tenants/current`, `GET /api/v1/tenants/{id}`, `GET /api/v1/tenants/current/members` ; erreurs en Problem Details |
| `account-service` | Comptes en EUR, statuts (`ACTIVE`, `FROZEN`, `CLOSED`), limites par opération et journalière en centimes ; compte clôturé figé par un déclencheur PostgreSQL |
| `payment-service` | Paiement en **event sourcing** (JDBC, version optimiste, snapshots toutes les 5 versions, projection en ligne), machine à états complète, **`Idempotency-Key`** obligatoire, validation par appel à Account en relayant le jeton, limites sous verrou consultatif par compte source ; `GET /api/v1/payments/{id}/events` pour l'historique ([ADR-008](docs/architecture/adr/0008-paiement-event-sourcing.md), [ADR-009](docs/architecture/adr/0009-idempotency-key.md), [ADR-010](docs/architecture/adr/0010-validation-synchrone-et-limites.md)) |
| `ledger-service` | **Grand livre en partie double**, garanti par PostgreSQL : équilibre vérifié au `COMMIT` par des déclencheurs de contrainte différés, journal en ajout seul, soldes mis à jour uniquement par les écritures (aucun droit de modification pour le rôle applicatif), aucun découvert sur un compte client, contrepassation vérifiée comme inverse exact de l'original ; dépôts simulés, relevé et soldes ; écritures internes (`/internal/**`) non routées par la Gateway ([ADR-011](docs/architecture/adr/0011-grand-livre-en-partie-double.md), [ADR-012](docs/architecture/adr/0012-comptabilisation-par-payment.md)) |
| `shared/messaging` | **Outbox transactionnelle**, relais vers Kafka (au moins une fois, ordre préservé, un seul relais actif), **contrats Avro** (`EventEnvelope`, `PaymentChanged`, `TransactionPosted`) avec Schema Registry, table `inbox` des consommateurs ([ADR-013](docs/architecture/adr/0013-outbox-kafka-et-avro.md)) |
| Projections d'Account | Consommateur Kafka idempotent (événement traité une fois, version d'agrégat, file morte `.dlq`) : solde projeté et activité des paiements d'un compte, `GET /api/v1/accounts/{id}/activity` ([ADR-014](docs/architecture/adr/0014-consommateurs-idempotents.md)) |
| Reprise de la saga | Worker de Payment : un paiement en suspens est repris seul, avec la même clé comptable, après un délai qui double à chaque reprise, par un compte de service Keycloak ([ADR-015](docs/architecture/adr/0015-reprise-automatique-de-la-saga.md)) |
| `shared/platform` | Réglages d'observabilité communs (métriques Prometheus étiquetées par service, traces OpenTelemetry, identifiant de trace dans les journaux) et enveloppe de résilience : coupe-circuit et cloison Resilience4j nommés ([ADR-017](docs/architecture/adr/0017-observabilite-resilience-et-charge.md)) |
| Observabilité | Métriques métier (événements de paiement, sagas en suspens ou bloquées, outbox, file morte, écritures du Ledger, rejeux d'idempotence), `/actuator/prometheus` réservé au rôle `metrics` (client Keycloak `prometheus`), traces qui **traversent Kafka**, règles d'alerte et tableau de bord Grafana généré (profil `observability`) |
| Résilience | Circuit ouvert quand le Ledger ou Account s'enlise : les paiements échouent aussitôt en `FAILED` et la saga les reprend à son retour, sans double écriture ; un refus métier n'ouvre jamais le circuit. Quota par organisation à la Gateway (429 + `Retry-After`) |
| Pannes et charge | Toxiproxy (profil `chaos`) et `scripts/chaos.py` ; scénarios k6 dans `load/` avec vérification que la somme des soldes est conservée |
| `frontend` | **Angular 22** (sans zone, composants autonomes) avec Angular Material et NgRx SignalStore : connexion Keycloak (code d'autorisation + PKCE, jeton limité à l'onglet), comptes (ouverture, plafonds, gel, clôture, dépôt), journal comptable, solde du Ledger et solde projeté, virements avec clé d'idempotence, historique d'événements d'un paiement, remboursement ; actions d'administration masquées pour un simple membre ([ADR-016](docs/architecture/adr/0016-front-authentification-et-verite-des-donnees.md)) |
| `shared/security-starter` | Conversion du jeton Keycloak (rôles du realm, claim `tenant_id`), `TenantContext`, gestionnaires de transactions JPA et JDBC qui posent le tenant **à chaque transaction**, chaîne de sécurité commune |
| `shared/test-support` | Faux Keycloak (JWKS local, jetons signés validés par la configuration de production) et base PostgreSQL de test avec rôles propriétaire et applicatif |
| Isolation | RLS PostgreSQL **forcée**, rôle applicatif sans `BYPASSRLS` ni droit de suppression, refus par défaut sans contexte ([ADR-005](docs/architecture/adr/0005-rls-et-contexte-par-transaction.md)) |
| Keycloak | Realm `finflow` importé au démarrage : client `finflow-web` (PKCE S256), audience `finflow-api`, attribut `tenant_id` modifiable par un administrateur seulement ([ADR-004](docs/architecture/adr/0004-keycloak-et-tenant-dans-le-jeton.md)) |
| Docker Compose | PostgreSQL 18 (une base et deux rôles par service), Keycloak 26, Kafka 4 KRaft à un nœud et Schema Registry (profil `kafka`) ([ADR-006](docs/architecture/adr/0006-pile-locale-sobre-en-memoire.md)) |
| CI | Tests unitaires et d'intégration (Testcontainers), compilation et tests du front, puis vérification de la pile avec de vrais jetons Keycloak et parcours Playwright |

Payment orchestre désormais la comptabilisation, en synchrone : un paiement validé est confié au Ledger et finit `COMPLETED`, `REJECTED` (fonds insuffisants) ou `FAILED` (Ledger indisponible, repris par un rejeu de la même `Idempotency-Key` sans double écriture). Un paiement terminé se rembourse par contrepassation (`POST /api/v1/payments/{id}/refunds`). Depuis l'incrément 4, Payment et le Ledger écrivent leurs événements dans une **outbox** (même transaction que le changement), un relais les publie sur Kafka, Account les projette, et un paiement resté en suspens (Ledger arrêté, réponse perdue) est **repris automatiquement**, sans double écriture. Kafka peut s'arrêter : les services continuent, les événements partent à son retour. Le front Angular (incrément 5) montre tout cela à l'écran ; l'observabilité et AWS viennent ensuite (voir la [feuille de route](docs/architecture/vision.md#feuille-de-route)).

## Ce que les tests vérifient

- **Production** (incrément 6) :
  - un Ledger en panne ouvre le circuit, et les appels suivants ne partent plus (`LedgerProtectionIT`) ;
  - le circuit se referme au retour du Ledger et le paiement repris n'est pas doublé ;
  - un refus métier du Ledger (fonds insuffisants) n'ouvre jamais le circuit ; Account a son propre circuit ;
  - une organisation au-delà de son quota reçoit 429 sans atteindre le service, sans gêner les autres (`RateLimitTest`) ;
  - `/actuator/prometheus` : 401 sans jeton, 403 pour un utilisateur, lisible par le client `prometheus` (`MetricsSecurityTest`) ;
  - le contexte de trace est conservé dans l'outbox et republié en en-tête Kafka (`OutboxRelayIT`).
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
- **Grand livre** (`PostingTest`, `LedgerDatabaseIT`, `LedgerApiIT`, `LedgerConcurrencyIT`) :
  - en SQL brut avec le rôle applicatif : une transaction déséquilibrée, d'une seule ligne ou sans ligne est refusée au `COMMIT`, une ligne ajoutée après coup aussi ;
  - le journal ne se modifie ni ne se supprime, un solde ne se modifie ni ne se fixe à la création ;
  - aucun compte client ne passe sous zéro, la compensation si ;
  - une contrepassation doit être l'inverse exact de l'original, une seule fois, et ne se contrepasse pas ;
  - **dix envois simultanés du même paiement ne sont comptabilisés qu'une fois** ;
  - **quarante virements croisés A→B / B→A ne s'interbloquent pas** et conservent l'argent ;
  - **dix dépenses simultanées de 300 € sur un solde de 1 000 € : trois passent, sept sont refusées** ;
  - les soldes égalent toujours la somme des écritures (`comptes_incoherents()` renvoie zéro ligne) ;
  - isolation entre organisations sur les soldes, relevés et transactions.
- **Saga de Payment** (`PaymentApiIT`, `PaymentConcurrencyIT`) :
  - fonds insuffisants : `REJECTED` avec le motif du Ledger, sans entamer la limite journalière ;
  - Ledger en panne : `FAILED`, puis reprise par rejeu de la même clé ;
  - **réponse perdue après comptabilisation** : la reprise ne double pas l'écriture ;
  - reprises bornées à trois ;
  - remboursement par contrepassation, refusé si le destinataire a dépensé, repris sans double contrepassation, **six remboursements simultanés n'en passent qu'un**.
- **Outbox et relais** (`OutboxRelayIT`, `PaymentOutboxIT`, `LedgerOutboxIT`, contre un vrai Kafka et un vrai PostgreSQL) :
  - chaque événement de l'event store ou du journal a son événement d'outbox, écrit **dans la même transaction** : une transaction annulée n'en laisse aucun, une écriture hors transaction est refusée ;
  - le relais publie dans l'ordre, marque les événements publiés, et **s'arrête au premier échec** pour ne jamais dépasser un événement plus ancien ;
  - **Kafka injoignable** : les événements restent en attente, puis partent au retour du broker ;
  - **deux relais simultanés** ne publient jamais deux fois le même événement (verrou consultatif) ;
  - un arrêt entre l'envoi et la marque republie l'événement (au moins une fois), d'où des consommateurs idempotents ;
  - l'enveloppe porte l'organisation, la version d'agrégat, la corrélation et la chaîne de causalité.
- **Consommateurs** (`EventProjectorIT`, `KafkaConsumerIT`) :
  - **un événement livré trois fois n'a qu'un effet** ;
  - **un événement ancien arrivé après un récent ne fait pas reculer** la projection d'un paiement ;
  - un événement d'une autre organisation ne touche pas ce compte, et les projections ne se lisent pas entre organisations ;
  - un échec annule l'effet et la marque « déjà traité » ensemble ;
  - sur un vrai Kafka, un message à la clé falsifiée et un message illisible vont en **file morte** sans bloquer la partition.
- **Reprise de la saga** (`SagaRecoveryIT`, `ServiceTenantIT`) :
  - un paiement échoué pendant une panne du Ledger finit `COMPLETED` seul, avec le jeton du compte de service, sans double écriture ;
  - une réponse perdue après comptabilisation ne comptabilise pas deux fois ;
  - le délai double à chaque reprise, et, après trois reprises, le paiement attend une décision humaine ;
  - un remboursement en attente est repris, chaque reprise s'exécute dans l'organisation de son paiement ;
  - l'en-tête d'organisation n'a d'effet que pour un jeton de service sans organisation, sur les routes internes.
- **Front, tests unitaires** (Vitest) : conversion des montants sans nombre à virgule, lecture du jeton, rôles, jeton expiré jamais envoyé, jeton envoyé à la seule Gateway, 401 renvoyé vers Keycloak, destination de retour jamais externe, stores (solde illisible sans effet sur la liste, relecture bornée d'un paiement, clé d'idempotence conservée).
- **Front, parcours Playwright contre la vraie pile** (`frontend/e2e`) :
  - connexion par Keycloak avec PKCE S256, jeton en `sessionStorage` et jamais en `localStorage`, déconnexion, page profonde restituée ;
  - ouvrir deux comptes, déposer, virer, lire le journal et l'historique d'événements, voir le solde projeté par Kafka rejoindre celui du Ledger, rembourser ;
  - virement refusé pour fonds insuffisants ou plafond dépassé, avec le motif ; compte gelé non proposé ; saisie invalide refusée ;
  - **isolation** : Bruno, d'une autre organisation, ne voit ni le compte, ni le solde, ni le paiement d'Alice, même avec les identifiants ;
  - **rôles** : Clara, simple membre, ne voit aucune action d'administration, et un appel direct reçoit 403 ;
  - **panne** (`E2E_PANNES=1`) : le Ledger est arrêté, le paiement passe en échec technique, puis se termine seul à son retour avec un seul mouvement.
- **Pile réelle** (`scripts/verifier-pile.sh`, dans la CI) : jetons émis par Keycloak, claim `tenant_id`, audience, routage par la Gateway, isolation entre Alpha et Beta, dépôt simulé, transfert de 1 500 € comptabilisé, soldes, rejeu sans double écriture, remboursement et soldes rétablis, écritures internes non routées, refus par limite ou fonds insuffisants et refus d'un compte d'une autre organisation, sujets Avro dans le Schema Registry, soldes projetés rejoignant ceux du Ledger, jeton du compte de service.

Les régressions de sécurité et de cohérence sont vérifiées par mutation : 8 sur 8 détectées à l'incrément 1, 15 sur 15 à l'incrément 2, 24 sur 24 à l'incrément 3 (équilibre, découvert, droits sur le journal et les soldes, verrous, idempotence, relais du jeton, reprises de la saga), 27 sur 27 à l'incrément 4 (ordre et verrou du relais, purge, clé de partition, causalité, délai exponentiel, inbox et garde de version, RLS des projections, compte de service). À l'incrément 6, 9 sur 9 (circuit jamais ouvert, jamais refermé, refus métier compté comme panne, circuit ouvert non reconnu comme refus, quota ignoré ou partagé entre organisations, `Retry-After` absent, métriques ouvertes à tout utilisateur, trace non propagée dans Kafka). Le front est vérifié de la même façon : 10 mutations sur 10 détectées (redirection externe, jeton envoyé à tout hôte, 401 ignoré, jeton expiré accepté, rôle déduit à tort, montants, relecture sans borne, comptes gelés proposés, solde illisible).

## Lancer en local

Prérequis : Java 25, Docker, et Node 24 pour le front. Maven est fourni par le wrapper.

```bash
docker compose up -d
```

Pour Kafka et le Schema Registry (outbox, projections, reprise de la saga) :

```bash
docker compose --profile kafka up -d
```

Les services ne se servent de Kafka que si `MESSAGING_ENABLED=true` (outbox, relais, consommateur), et ne reprennent seuls les sagas en suspens que si `SAGA_RECOVERY_ENABLED=true`. Par défaut, rien de tout cela n'est lancé, et les tests n'en ont pas besoin.

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
cd backend && ./mvnw -pl ledger-service spring-boot:run
```

```bash
cd backend && ./mvnw -pl api-gateway spring-boot:run
```

```bash
bash scripts/verifier-pile.sh
```

Pour lancer les cinq services d'un coup depuis les jars (après `./mvnw -DskipTests package`), puis le front et ses tests de bout en bout :

```bash
scripts/lancer-services.sh start
```

```bash
cd frontend && npm ci && npx ng serve
```

```bash
cd frontend && npx playwright install chromium && npx playwright test
```

### Observabilité, pannes et charge

```bash
docker compose --profile kafka --profile observability --profile chaos up -d --wait
```

Prometheus (http://localhost:9090) collecte les cinq services avec un jeton du client `prometheus` ; Grafana (http://localhost:3000, `admin` / `admin`) ouvre directement le tableau de bord FinFlow ; Jaeger (http://localhost:16686) montre les traces, y compris à travers Kafka. `scripts/lancer-services.sh` active l'export des traces si Jaeger tourne. Le tableau de bord se régénère par `python infrastructure/observability/generer-tableau-de-bord.py`.

Panne du Ledger avec Toxiproxy (Payment est lancé derrière le proxy) :

```bash
LEDGER_SERVICE_URL=http://localhost:28084 scripts/lancer-services.sh start payment
```

```bash
python scripts/chaos.py
```

Charge avec k6 (image Docker, aucune installation) :

```bash
docker run --rm -v "$PWD/load:/load" -e GATEWAY_URL=http://host.docker.internal:8080 -e KEYCLOAK_URL=http://host.docker.internal:8180 grafana/k6:2.4.0 run /load/paiements.js
```

Le quota par organisation de la Gateway (300 requêtes par minute par défaut) se relève pour la charge : `RATE_LIMIT_REQUESTS=1000000` au lancement de la Gateway. Sur un poste de 15 Go, Playwright devient instable si Grafana, Prometheus et Jaeger tournent pendant les tests : arrêtez-les avant (`docker compose stop grafana prometheus jaeger toxiproxy`). Sur le poste de développement, 20 paiements par seconde passent avec un p95 d'environ 120 ms ; à 30 par seconde, il dépasse la seconde.

Le front est servi sur http://localhost:4200 (`alice` / `finflow-dev`). Les tests Playwright démarrent le front eux-mêmes et supposent la pile démarrée. `scripts/lancer-services.sh stop` arrête les services ; `E2E_PANNES=1` ajoute le parcours qui arrête le Ledger.

| Service | Adresse |
|---|---|
| Gateway | http://localhost:8080 |
| tenant-service | http://localhost:8081 |
| account-service | http://localhost:8082 |
| payment-service | http://localhost:8083 |
| ledger-service | http://localhost:8084 |
| Keycloak | http://localhost:8180 (console : `admin` / `admin_dev`) |
| PostgreSQL | `localhost:5439` |
| Kafka (profil `kafka`) | `localhost:9094` |
| Schema Registry (profil `kafka`) | http://localhost:8085 |
| Front Angular | http://localhost:4200 |
| Prometheus, Grafana, Jaeger (profil `observability`) | http://localhost:9090, http://localhost:3000, http://localhost:16686 |
| Toxiproxy (profil `chaos`) | http://localhost:8474 (API), ports 28084 (Ledger) et 28082 (Account) |

Comptes du realm local (mot de passe `finflow-dev`) :

| Utilisateur | Rôle | Organisation |
|---|---|---|
| `plateforme` | `platform-admin` | aucune |
| `alice` | `tenant-admin` | Alpha Industries |
| `bruno` | `tenant-admin` | Beta Logistique |
| `clara` | `tenant-member` | Alpha Industries |

Le client `payment-service` (`client_credentials`, rôle `service`, secret de développement) est le compte technique de la reprise des sagas. Le client `prometheus` (`client_credentials`, rôle `metrics`) sert à la collecte des métriques. Le client `finflow-dev-cli`, qui délivre des jetons par mot de passe pour les scripts, n'existe que dans ce realm de développement. Le realm se régénère avec `python infrastructure/keycloak/generer-realm.py`.

## Mémoire et coûts

Le poste de développement a 15 Go de RAM. La pile de l'incrément 1 (PostgreSQL, Keycloak, Kafka, Gateway, tenant-service) occupait environ 1,4 Go ; chaque service Spring Boot ajoute environ 250 Mo, avec des heap JVM bornées. Kafka n'est lancé qu'avec le profil `kafka`. Rien n'est créé sur AWS sans accord explicite, après chiffrage : MSK et RDS coûtent cher même au repos.
