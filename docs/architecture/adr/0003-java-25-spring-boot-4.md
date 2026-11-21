# ADR-003 : Java 25, Spring Boot 4.2 et Spring Cloud 2026.0

**État** : acceptée.

## Contexte

Le blueprint laisse le choix entre Java 21 et Java 25 « selon compatibilité ». Spring Boot 4 et Spring Cloud 2026.0 prennent en charge Java 25, la LTS la plus récente.

## Décision

- **Java 25** (Temurin en CI et dans les images).
- **Spring Boot 4.2.0** avec **Spring Cloud 2026.0.0**, la version de Spring Cloud construite pour Boot 4.2.
- Threads virtuels activés (`spring.threads.virtual.enabled`) dans les services servlet : les appels REST de la saga et les accès JDBC sont bloquants, et les threads virtuels évitent de dimensionner des pools.

## Conséquences

- Ces deux versions sont sorties le 19 novembre 2026, la veille du démarrage. En cas de défaut bloquant, on redescend à Boot 4.1.x et Spring Cloud 2025.1.x : seul le parent Maven change.
- Les bibliothèques tierces doivent avoir une version compatible avec Boot 4 et Jakarta EE 11 ; on le vérifie avant chaque ajout.
