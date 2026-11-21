# ADR-005 : Isolation par Row-Level Security forcée et contexte tenant posé à chaque transaction

**État** : acceptée.

## Contexte

Le blueprint retient un schéma partagé avec `tenant_id` et la RLS PostgreSQL. Il impose aussi trois règles : le contexte est défini par transaction et non sur une connexion réutilisée, le rôle applicatif ne peut pas contourner les politiques, et la RLS ne dispense pas des contrôles applicatifs.

Le piège classique est un `SET app.tenant_id = …` au niveau de la session : la connexion retourne dans le pool avec la valeur du tenant précédent, et la requête suivante, d'un autre utilisateur, lit les données de quelqu'un d'autre.

## Décision

- **Deux rôles PostgreSQL par service**. Le rôle propriétaire (`finflow_<service>_owner`) exécute les migrations Flyway. Le rôle applicatif (`finflow_<service>_app`) ne reçoit que `SELECT`, `INSERT`, `UPDATE` et `DELETE`, n'est propriétaire de rien et n'a pas `BYPASSRLS`. Les rôles sont créés par l'infrastructure (script d'initialisation Compose, Terraform), et les droits sont accordés par les migrations.
- Chaque table métier a `tenant_id uuid NOT NULL`, `ENABLE` **et** `FORCE ROW LEVEL SECURITY`, et une politique `USING` / `WITH CHECK` :
  `tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`.
  Sans contexte, l'expression vaut `NULL` : **aucune ligne n'est visible et aucune insertion n'est acceptée** (refus par défaut).
- Le gestionnaire de transactions (`TenantAwareJpaTransactionManager`, dans `security-starter`) exécute `select set_config('app.tenant_id', ?, true)` au début de **chaque** transaction, avec le tenant du jeton ou une chaîne vide. Avec le paramètre `true`, la valeur ne dure que le temps de la transaction : elle ne peut pas fuiter vers l'utilisateur suivant du pool.
- `spring.jpa.open-in-view=false` : aucun accès à la base hors transaction.
- Les traitements sans utilisateur (création d'une organisation, et plus tard les consommateurs Kafka) déclarent explicitement leur tenant avec `TenantContext.callAs(tenantId, …)` ou `runAs`.
- Les contrôles applicatifs restent en place : rôles, et comparaison de l'identifiant demandé avec le tenant du jeton.

## Conséquences

- Les tests d'intégration se connectent avec le **rôle applicatif** sur un vrai PostgreSQL (Testcontainers). Un superutilisateur contournerait la RLS et rendrait les tests trompeurs.
- Les tests vérifient : lecture croisée impossible, même par identifiant ; aucune ligne visible sans contexte ; insertion refusée pour un autre tenant ; aucune fuite d'une transaction à la suivante sur une connexion unique du pool.
- La contrainte d'unicité globale (par exemple le slug d'une organisation) n'est pas soumise à la RLS : un conflit révèle qu'une valeur existe ailleurs. C'est accepté pour le slug, et ce sera évité pour les données sensibles.
