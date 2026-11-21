# ADR-006 : Une pile locale sobre en mémoire

**État** : acceptée.

## Contexte

Le poste de développement a 15 Go de RAM, partagés avec d'autres projets : un cluster Hadoop de cours tourne parfois dans Docker. Le blueprint prévoit en local Kafka, Schema Registry, PostgreSQL, Redis, Keycloak et jusqu'à dix JVM. Avec les réglages par défaut, une JVM Spring Boot prend 300 à 500 Mo, Kafka environ 1 Go et Keycloak environ 700 Mo.

## Décision

- **Profils Docker Compose** :
  - sans profil : PostgreSQL et Keycloak ;
  - `kafka` : Kafka ;
  - `services` : les services et la Gateway en conteneurs, quand leurs images existeront (incrément 7).

  En développement, les services se lancent depuis l'IDE, avec `./mvnw spring-boot:run` ou leur jar, un ou deux à la fois.
- **Kafka 4 en mode KRaft, un seul nœud**, qui joue à la fois les rôles de broker et de contrôleur. Il a un facteur de réplication de 1 et une heap de 512 Mo au plus.
- **Un seul PostgreSQL** pour toutes les bases logiques (`finflow_tenant`, `finflow_account`, `finflow_payment`, `finflow_ledger`), chacune avec ses propres rôles.
- **Keycloak en mode `start-dev`** avec une base H2 interne et une heap de 512 Mo au plus. Le realm est réimporté à chaque démarrage.
- **JVM des services** : `-XX:MaxRAMPercentage` dans les images, `-Xmx256m` en local, `-XX:+UseSerialGC` pour les petits tas, `-XX:TieredStopAtLevel=1` en développement.
- **Schema Registry et Redis** n'arrivent qu'avec l'incrément qui s'en sert (Kafka pour l'un, cache ou limitation de débit pour l'autre).

## Conséquences

- La pile de base (PostgreSQL et Keycloak) tient sous 1,5 Go, et l'ensemble du noyau avec Kafka vise moins de 4 Go.
- Les tests d'intégration démarrent leurs propres conteneurs avec Testcontainers. Ils ne doivent pas tourner en même temps qu'une pile complète déjà lancée.
