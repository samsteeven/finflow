# ADR-002 : Monorepo Maven, un module par service sous un parent commun

**État** : acceptée.

## Contexte

Le blueprint recommande un monorepo avec « un projet Maven indépendant par service » et prévient que le monorepo ne doit pas devenir un monolithe technique. Dix projets Maven totalement séparés dupliqueraient pourtant les versions (Spring Boot, Spring Cloud, Testcontainers) et rendraient la CI plus lente.

## Décision

- `backend/pom.xml` est un **parent agrégateur** : il hérite de `spring-boot-starter-parent`, importe le BOM Spring Cloud, fixe les versions et la configuration des plugins, et liste les modules.
- Chaque service (`backend/<nom>-service`, `backend/api-gateway`) est un module qui produit **son propre jar exécutable**. On le construit et on le teste seul avec `./mvnw -pl <module> -am verify`, et il a sa propre image et son propre déploiement.
- Les modules partagés sont rangés sous `backend/shared/` et se limitent au technique : `security-starter` (jeton, rôles, contexte tenant), `test-support` (émission de jetons signés pour les tests) et, plus tard, `event-contracts` et `observability-starter`. **Aucune entité ni aucun repository n'est partagé.**
- Un service ne dépend jamais d'un autre service ; seuls les modules `shared/` sont autorisés comme dépendances.
- Le Maven Wrapper est versionné : la CI et le poste utilisent la même version de Maven.

## Conséquences

- Mettre à jour Spring Boot se fait en un seul endroit. Le revers : tous les services montent de version ensemble, ce qu'on assume pour un projet de cette taille.
- La CI construit tout le réacteur. Elle pourra ne reconstruire que les modules modifiés quand ce sera utile.
