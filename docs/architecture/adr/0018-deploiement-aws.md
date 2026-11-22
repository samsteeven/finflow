# ADR-018 : Déploiement sur AWS, au plus juste, détruit après usage

**État** : acceptée (incrément 7). L'infrastructure est écrite, validée et répétée en local ; **elle n'a jamais été appliquée sur AWS** : rien n'est créé sans accord explicite après chiffrage (voir [docs/exploitation/aws.md](../../exploitation/aws.md)). S'appuie sur l'[ADR-006](0006-pile-locale-sobre-en-memoire.md) (pile locale) et l'[ADR-017](0017-observabilite-resilience-et-charge.md).

## Contexte

Montrer que FinFlow tourne ailleurs que sur un poste : vraie base gérée, cinq services, un courtier de messages, un fournisseur d'identité, un point d'entrée unique, et la possibilité de tout détruire en une commande. Sur le compte AWS personnel de l'auteur : chaque euro compte, et rien ne doit rester allumé sans raison. Le blueprint laissait le choix « MSK ou alternative moins chère ».

## Options étudiées

- **Kubernetes (EKS)** : 73 $/mois pour le seul plan de contrôle, avant le premier conteneur. Hors de proportion.
- **Une machine virtuelle avec docker compose** : la moins chère, mais ni base gérée, ni sauvegardes, ni remplacement automatique d'une tâche, ni déploiement progressif : rien de ce qu'on veut montrer.
- **MSK Serverless** : ≈ 0,75 $/heure pour le cluster seul (≈ 550 $/mois), avant les partitions. Écarté.
- **MSK provisionné (2 courtiers `kafka.t3.small`)** : ≈ 0,10 à 0,12 $/heure (estimation), soit environ **+ 60 $/mois** par rapport à la solution retenue, et du code à changer : il impose TLS ou IAM entre les services et Kafka, alors que `shared/messaging` parle en clair dans le VPC. Le registre de schémas Confluent resterait à héberger de toute façon (le registre de Glue est un autre contrat). Garde son intérêt en production (réplication, mises à jour gérées) : c'est la première évolution.
- **Un courtier KRaft sur Fargate, données sur EFS** : la même image Kafka que la pile locale, ≈ 0,03 $/heure. **Retenu.**

## Décision

```
Navigateur ──HTTPS──► CloudFront ─┬─► S3 : front Angular (privé, accès par OAC)
                                  ├─► /api/*  ┐ origine VPC ─► ALB interne ─► Gateway ×2 ─┬─► tenant · account · payment · ledger
                                  └─► /auth/* ┘                            └► Keycloak    │      (Fargate, DNS Cloud Map *.finflow.internal)
                                                                                            └─► Kafka (EFS) · Schema Registry
                                    sous-réseaux privés : RDS PostgreSQL 18 (cinq bases) · disque EFS de Kafka
```

- **Région Paris** (eu-west-3), deux zones de disponibilité.
- **Un seul domaine** (celui de CloudFront) pour le front, l'API (`/api`) et Keycloak (`/auth`) : pas de CORS, le front est construit sans adresse en dur (`ng build --configuration aws`) et l'émetteur des jetons est l'adresse publique. Le répartiteur est **interne** : CloudFront l'atteint par une origine VPC, et un en-tête secret est exigé en plus ; un accès direct reçoit 403.
- **Keycloak est exposé sous `/auth` mais sa console d'administration et le realm `master` sont refusés par le répartiteur** (règle de priorité 5). Le realm `finflow` est importé au premier démarrage ; ses secrets (compte de service de Payment, mot de passe des comptes de démonstration, adresse du front) sont des `${VARIABLES}` que Keycloak remplace à l'import, depuis Secrets Manager. Aucun mot de passe de développement n'existe dans le realm AWS : ni le client de scripts locaux, ni celui de Prometheus.
- **Services** : même image, un `ARG SERVICE` (`backend/Dockerfile`), base Temurin 25 JRE épinglée par empreinte, utilisateur non root, **système de fichiers en lecture seule** (`/tmp` éphémère), 0,5 vCPU et 1 Go. La Gateway tourne en deux instances ; le quota par organisation y est local à chaque instance (ADR-017) : avec deux instances, le quota effectif est doublé. Les services se trouvent par DNS (Cloud Map) et la Gateway ne route toujours pas `/internal`.
- **Bases** : une instance PostgreSQL, cinq bases logiques, deux rôles par service. Une **tâche ponctuelle** (`init-bases`, lancée par Terraform à chaque déploiement, rejouable) crée rôles et bases à partir des mots de passe de Secrets Manager, **avant** le démarrage des services : c'est le même schéma d'isolation que la pile locale (ADR-005), vérifié par la répétition. Les migrations Flyway s'exécutent au démarrage de chaque service avec le rôle propriétaire ; elles restent **rétrocompatibles** (ajouter avant de retirer) pour que le retour arrière ne touche pas la base.
- **Kafka** : un seul courtier, `auto.create.topics` désactivé (les services déclarent leurs topics), réplication 1. Ses données sont sur EFS chiffré (point d'accès limité à `/kafka`, uid 1000) : remplacer la tâche ne perd rien. Jamais deux courtiers à la fois (`maximum_percent = 100`).
- **Pas de passerelle NAT** (≈ 35 $/mois) : les tâches ont une adresse publique pour sortir (ECR, Secrets Manager, CloudWatch), aucune entrée hors du répartiteur et des autres tâches. Base, répartiteur et EFS sont dans des sous-réseaux privés sans route vers Internet.
- **Images** construites une fois par le workflow, étiquetées par l'empreinte git, **immuables** dans ECR, analysées par Trivy (critiques corrigeables bloquantes). Kafka et le registre sont **copiés** dans ECR à version et empreinte fixes (`scripts/aws-miroir-images.sh`) : Fargate ne tire rien de Docker Hub.
- **Secrets** générés par Terraform, rangés dans Secrets Manager, injectés au démarrage des tâches. Ils sont aussi dans l'état Terraform, chiffré, dans un compartiment privé réservé au rôle de déploiement.
- **Déploiement** depuis GitHub Actions par **OIDC** (aucune clé AWS stockée), dans un environnement à approbation obligatoire, **jamais déclenché par un push**. Terraform attend l'état stable de chaque service ; un disjoncteur ECS fait un **retour automatique** si une version ne devient pas saine. Ordre imposé par les dépendances : bases → Keycloak, Kafka, registre → services.
- **Sauvegardes** RDS quotidiennes et restauration à la seconde près sur 7 jours.
- **Surveillance** : journaux dans CloudWatch, alarmes (5xx, latence p95, instances saines de la Gateway, santé de Keycloak, CPU et stockage de la base, erreurs dans les journaux de Payment, Ledger et Account) envoyées par e-mail, budget mensuel avec alertes. **Pas de Prometheus, de Grafana ni de Jaeger sur AWS** (coût et mémoire) : ils restent dans la pile locale, les métriques et les traces sont produites par les services mais pas collectées.

## Répétition générale locale

Comme l'infrastructure ne peut pas être appliquée sans coût, `scripts/repetition-aws.sh` lance en local **les mêmes images et la même configuration que les tâches ECS** (mêmes noms DNS, mêmes variables, même initialisation des bases, mêmes contraintes : non root, lecture seule, 1 Go) et vérifie le démarrage de la pile complète, les migrations avec le rôle propriétaire, l'isolation entre bases, l'acceptation d'un jeton Keycloak par la Gateway (émetteur, clés, audience) et la création des topics. Elle ne remplace pas un déploiement : elle attrape les erreurs de configuration, pas celles du réseau AWS.

## Coût

Estimation pour la région Paris, hors offre gratuite, en laissant tout allumé. Les tarifs Fargate (≈ 0,047 $/vCPU-heure, 0,005 $/Go-heure) sont déduits de l'ADR de StockPilot et d'un tarif européen relevé sur un site tiers, **pas lus sur la grille officielle** : à vérifier dans le calculateur AWS avant le premier `apply`.

| Poste                                                    | Par heure    | Par mois (730 h) |
| -------------------------------------------------------- | ------------ | ---------------- |
| Fargate : Gateway ×2 et quatre services (0,5 vCPU, 1 Go) | 0,172 $      | 125 $            |
| Fargate : Keycloak (1 vCPU, 2 Go)                        | 0,057 $      | 42 $             |
| Fargate : Kafka (0,5 vCPU, 1 Go) et registre (0,25, 0,5) | 0,043 $      | 31 $             |
| Répartiteur (ALB)                                        | 0,030 $      | 22 $             |
| RDS db.t4g.small et 20 Go gp3                            | 0,040 $      | 29 $             |
| Adresses IPv4 publiques des tâches (× 9)                 | 0,045 $      | 33 $             |
| Secrets Manager, CloudWatch, ECR, EFS, CloudFront, S3    | ≈ 0,02 $     | ≈ 15 $           |
| **Total**                                                | **≈ 0,41 $** | **≈ 300 $**      |

**Usage prévu** : déployer, vérifier, montrer, puis **détruire** (workflow « Destruction AWS »). Quatre heures ≈ 1,6 $ ; une journée complète ≈ 10 $. Le budget alerte à 50, 80 et 100 % de 30 $ par mois. Laisser l'environnement allumé un mois coûterait environ 300 $ : c'est la raison d'être de la destruction systématique.

## Conséquences et limites assumées

- **Jamais appliqué sur AWS.** `terraform validate` et l'analyse Trivy passent (sévérités moyenne et plus : aucun défaut, les exceptions sont écrites à côté de la ressource) ; la répétition locale passe. Restent à constater sur AWS, au premier déploiement : le démarrage de Kafka sur EFS, le comportement de Keycloak derrière CloudFront (cookies, redirections, en-têtes de proxy), la résolution Cloud Map, et les durées de démarrage sous 0,5 vCPU (Keycloak met ≈ 65 s sur le poste de développement).
- **Un seul courtier Kafka, une seule instance de base, un seul Keycloak** : une panne de zone coupe le service jusqu'au remplacement. Pas de réplication des messages ; les événements non publiés sont dans l'outbox et repartent au retour du courtier.
- **TLS vers la base sans vérification du certificat** (`sslmode=require` : chiffré, mais un intermédiaire dans le VPC ne serait pas détecté) ; **Kafka et le registre en clair** dans le VPC, protégés par les groupes de sécurité ; **HTTP entre CloudFront et le répartiteur**, dans le réseau d'AWS (sans nom de domaine, pas de certificat).
- **Un seul groupe de sécurité pour toutes les tâches** : tout service peut joindre les ports internes de tous les autres. Le détail par service (la Gateway seule vers les services, Payment seul vers Ledger) est l'étape suivante.
- **Pas de WAF** (≈ 6 $/mois) : le quota par organisation est dans la Gateway. **Pas de points de terminaison privés** : sortie HTTPS ouverte depuis les tâches. **Pas de journaux d'accès CloudFront ni de journaux de flux du VPC** ; journaux CloudWatch non chiffrés par une clé dédiée. Chaque exception est écrite à côté de la ressource et acceptée explicitement par l'analyse Trivy.
- **Aucune métrique ni trace collectée** (voir plus haut) : ce que l'incrément 6 montre en local n'est pas reproduit sur AWS.
- **Le realm n'est importé qu'une fois** (`IGNORE_EXISTING`) : changer le realm demande de détruire et recréer l'environnement.
- **Parcours authentifié non automatisé sur AWS** : `scripts/verifier-deploiement.sh` contrôle ce qu'un visiteur anonyme voit ; la connexion avec un compte de démonstration se fait à la main (le test Playwright de bout en bout reste sur la pile locale, qui a un client dédié aux scripts, absent du realm AWS).
