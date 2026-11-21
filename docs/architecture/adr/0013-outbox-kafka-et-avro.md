# ADR-013 : Transactional Outbox, relais Kafka et contrats Avro

**État** : acceptée.

## Contexte

Payment et Ledger changent d'état dans PostgreSQL et doivent le faire savoir aux autres services. Écrire dans la base puis publier sur Kafka sont deux opérations sans transaction commune : un arrêt entre les deux perd l'événement, ou publie un événement dont le changement a été annulé.

Le blueprint demande des contrats versionnés, avec Avro et Schema Registry, et des événements qui portent le tenant, la version d'agrégat et les identifiants de corrélation.

## Décision

- **Outbox transactionnelle.** Chaque service producteur (Payment, Ledger) a une table `outbox`. L'événement y est écrit **dans la transaction qui change l'état** : l'event store pour Payment, les écritures du journal pour le Ledger. Les deux sont validés ensemble ou annulés ensemble. `Outbox.append` exige une transaction en cours (`MANDATORY`).
- **Relais.** Un composant du service lit l'outbox dans l'ordre d'insertion et publie sur Kafka (`acks=all`, producteur idempotent), puis marque `published_at`. La livraison est **au moins une fois** : un arrêt entre l'envoi et la marque republie l'événement, et les consommateurs sont idempotents (ADR-014). Un verrou consultatif PostgreSQL ne laisse travailler qu'une instance à la fois, ce qui préserve l'ordre. Au premier échec d'envoi le lot s'arrête : un événement ne dépasse jamais un plus ancien. Les événements publiés sont purgés après sept jours.
- **Kafka n'est pas la source de vérité.** Si Kafka est arrêté, les services continuent de fonctionner : les événements s'accumulent dans l'outbox et partent à son retour.
- **Contrats Avro.** Une enveloppe commune `EventEnvelope` (`eventId`, `eventType`, `eventVersion`, `aggregateId`, `aggregateVersion`, `tenantId`, `correlationId`, `causationId`, `occurredAt`, `producer`, `payload`) et deux charges utiles : `PaymentChanged` (statut après la transition, comptes, montant) et `TransactionPosted` (lignes débit/crédit). Les schémas sont dans `backend/shared/messaging/src/main/avro` et les classes sont générées à la compilation.
- **Schema Registry** (Confluent) en profil `kafka` de Docker Compose. Sujets par topic (`payment.events-value`). L'enregistrement automatique des schémas reste actif en développement ; la compatibilité par défaut (`BACKWARD`) protège les consommateurs d'une évolution cassante.
- **Représentation dans l'outbox.** L'événement est stocké en JSON Avro, pas en binaire : la transaction métier n'appelle jamais le Schema Registry. La sérialisation binaire (identifiant de schéma, puis données) se fait à la publication.
- **Clé de partition** `tenantId:aggregateId` : l'ordre est garanti par agrégat, pas globalement.
- **Topics** : `payment.events` et `ledger.events`, 3 partitions, plus une file morte `.dlq` pour chacun. Le broker n'en crée aucun tout seul (ADR-006) ; le relais les crée au premier besoin, sans que le démarrage d'un service dépende de Kafka.
- **La table outbox n'a pas de RLS.** Le relais lit les événements de toutes les organisations, ce que la RLS forcée interdirait. La table est technique : aucune API ne l'expose, et le tenant voyage dans l'enveloppe. Le rôle applicatif n'y a que `SELECT`, `INSERT`, `DELETE` et la mise à jour des colonnes de suivi.

## Conséquences

- Aucun événement perdu ni fantôme : l'événement existe si et seulement si le changement est validé.
- Un délai (une demi-seconde par défaut) sépare le changement de sa publication. Un consommateur lit donc un état légèrement en retard : c'est une cohérence à terme assumée.
- Un événement peut être publié deux fois, et un consommateur doit le tolérer.
- Le relais est interne à chaque service plutôt qu'un connecteur de capture des changements (Debezium) : moins de composants à faire tourner en local et sur AWS, au prix d'un sondage de la table.
- Les schémas ne sont pas encore enregistrés par la CI ; c'est à reprendre avant un déploiement partagé.

## Alternatives écartées

- **Publier après le commit** : un arrêt au mauvais moment perd l'événement.
- **Transactions Kafka et base** : pas de transaction distribuée fiable entre les deux.
- **Debezium** : ajoute Kafka Connect, un composant lourd pour une machine de 15 Go.
- **JSON Schema ou JSON libre** : pas de vérification de compatibilité entre versions.
