# ADR-017 : Observabilité, résilience des appels sortants et charge

**État** : acceptée (incrément 6). S'appuie sur l'[ADR-015](0015-reprise-automatique-de-la-saga.md) (reprise de la saga).

## Contexte

Jusqu'ici, un incident se constatait en lisant des journaux. Il faut pouvoir répondre, sans deviner, à trois questions : que se passe-t-il (métriques), où est passée cette requête (traces), et que fait le système quand un voisin tombe (résilience, vérifiée par des pannes provoquées et de la charge).

## Décision

**Un module commun, `shared/platform`.** Il apporte à chaque service, par autoconfiguration, les réglages d'observabilité (priorité la plus basse : un service peut les remplacer) et l'enveloppe de résilience. Les cinq services s'alignent par une dépendance, sans copier de configuration.

**Métriques.** Micrometer, exposées en Prometheus sur `/actuator/prometheus`. Étiquette `application` sur toutes les séries. Les métriques *métier* comptent ce qui compte pour l'argent : événements de paiement par type (après le commit), sagas en suspens, bloquées et reprises, outbox (en attente, âge du plus ancien, publiés, échecs), livraisons des consommateurs et file morte, écritures du Ledger, rejeux d'`Idempotency-Key`.

**Le point de collecte n'est pas public.** `/actuator/prometheus` exige le rôle `metrics`. Prometheus s'authentifie par `client_credentials` (client Keycloak `prometheus`) : un utilisateur d'une organisation reçoit 403, un appel sans jeton 401, et la Gateway ne route jamais ce chemin vers l'extérieur. Pour que l'émetteur du jeton reste le même depuis un conteneur ou depuis le poste, Keycloak a un nom d'hôte fixe. `/actuator/health` reste ouvert aux sondes.

**Traces.** OpenTelemetry par le pont Micrometer, exportées en OTLP vers Jaeger (profil `observability`), seulement si `TRACING_EXPORT_ENABLED=true`. Les journaux portent l'identifiant de trace. Les appels `/actuator/**` ne produisent pas de span.

**La trace traverse Kafka.** L'événement est écrit dans l'outbox *avec* le contexte de trace courant (colonne `trace_context`, format W3C `traceparent`). Le relais le republie en en-tête Kafka ; le consommateur continue la même trace. Sans cela, la trace s'arrêterait à l'outbox, puisque la publication se fait plus tard, dans un autre fil.

**Résilience (Resilience4j) sur les appels de Payment vers le Ledger et vers Account.** Un coupe-circuit et une cloison par destination, nommés `ledger` et `account`. Un Ledger muet coûte le délai de lecture aux premiers appels, puis le circuit s'ouvre : les paiements suivants échouent aussitôt en `FAILED`, sans solliciter un service déjà en difficulté, et la saga les reprend quand le circuit se referme.
- **Pas de nouvel essai local.** La reprise de la saga est déjà bornée et persistée (ADR-015) ; un second mécanisme de nouvel essai en mémoire multiplierait les appels sans rien garantir de plus.
- **Un refus métier n'est pas une panne.** « Fonds insuffisants » est une réponse normale du Ledger, rendue comme une valeur : elle ne compte jamais dans le taux d'échec, et ne peut donc pas ouvrir le circuit.
- **Le circuit n'altère pas la cohérence.** Un appel écarté ne laisse aucune écriture ; une reprise utilise la même clé comptable, donc ne double rien.

**Quota par organisation à la Gateway.** Un limiteur Resilience4j par `tenant_id` du jeton validé (jamais d'un en-tête ; à défaut, le sujet du jeton). Au-delà, 429 avec `Retry-After`, sans atteindre le service. Une requête rejetée par l'authentification ne consomme aucun quota. Valeurs réglables (`finflow.gateway.rate-limit.*`).

**Pannes provoquées.** Toxiproxy (profil `chaos`) s'intercale entre Payment et le Ledger / Account. `scripts/chaos.py` rend le Ledger muet, vérifie que le circuit s'ouvre et que les appels deviennent immédiats, rétablit le Ledger, attend la reprise de tous les paiements et contrôle que chaque paiement est écrit une seule fois.

**Charge (k6).** `load/fumee.js` (un paiement de bout en bout et son rejeu) et `load/paiements.js` (débit constant, rejeux, seuils de latence et d'erreurs). Après la charge, le test vérifie l'invariant qui compte : la somme des soldes est exactement celle qui a été déposée.

**Alertes et tableau de bord.** Règles Prometheus (`infrastructure/observability/prometheus/alerts.yml`) sur ce qui menace l'argent ou la disponibilité : saga bloquée, outbox qui ne se vide pas, file morte alimentée, circuit ouvert, service muet, 5xx, latence. Le tableau de bord Grafana est *généré* par un script (`generer-tableau-de-bord.py`) plutôt qu'écrit à la main en JSON.

## Conséquences

- La pile complète d'observabilité (Prometheus, Grafana, Jaeger) ne se lance qu'avec un profil, pour tenir dans les 15 Go du poste.
- Le circuit protège le Ledger mais allonge, par construction, la durée pendant laquelle un paiement reste `FAILED` après un incident : jusqu'à la durée d'ouverture, puis la reprise de la saga.
- Les budgets de latence des seuils k6 sont ceux d'un poste de développement (cinq JVM, Kafka et Docker sur la même machine) : ils se resserrent en environnement dédié.
- Les traces échantillonnent tout en développement ; en production, la probabilité se règle par `TRACING_SAMPLING`.
- Au-delà de 20 paiements par seconde sur ce poste, la latence se dégrade nettement (le seuil de 30 par seconde dépasse 1 s au p95) : c'est un résultat mesuré de l'environnement, pas une limite du design.
