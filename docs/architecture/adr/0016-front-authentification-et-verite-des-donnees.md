# ADR-016 : Front Angular, jeton dans l'onglet, serveur seul source de vérité

**État** : acceptée (incrément 5). Précise l'[ADR-007](0007-front-angular-material-et-e2e-playwright.md).

## Contexte

Le front est le premier client réel de la Gateway. Il doit se connecter à Keycloak, présenter des écrans de comptes, de paiements et de journal, et rester honnête sur ce qu'il sait : un solde, un statut ou un droit viennent toujours d'un service, jamais d'un calcul local.

## Décision

- **Connexion** : flux *authorization code* avec PKCE S256, par `oidc-client-ts`, client public `finflow-web`. Un visiteur anonyme est renvoyé vers Keycloak par un garde de routes, et retrouve la page demandée au retour. La destination de retour n'est acceptée que si elle commence par `/` et non par `//` ou `/\` (pas de redirection ouverte).
- **Jeton** : conservé dans `sessionStorage`, donc limité à l'onglet et perdu à sa fermeture, jamais dans `localStorage`. Le renouvellement est automatique ; un jeton expiré n'est jamais envoyé. Le jeton ne part que vers l'adresse de la Gateway (intercepteur), et un 401 renvoie vers Keycloak.
- **Rôles** : lus dans le jeton pour **masquer** les actions qui seraient refusées (ouvrir un compte, déposer, geler, rembourser). Ce n'est qu'un confort : les services restent les seuls juges, et un test de bout en bout vérifie qu'un appel direct d'un simple membre reçoit 403.
- **État** : NgRx SignalStore pour ce qui se partage entre écrans (liste des comptes avec leurs soldes, liste des paiements et son filtre), signaux locaux pour le reste. Les soldes affichés sont ceux du Ledger ; le solde projeté par Kafka est affiché à côté, étiqueté comme asynchrone, pour rendre visible la cohérence à terme.
- **Montants** : le serveur parle en centimes entiers. La saisie en euros est convertie par analyse de texte (pas de nombre à virgule), à deux décimales au plus, strictement positive.
- **Idempotence** : un dépôt ou un virement reçoit une clé `Idempotency-Key` tirée à l'ouverture du formulaire. Un double clic ou un nouvel envoi après une coupure rejoue la même clé et ne débite pas deux fois.
- **Paiement en cours** : après l'envoi, la page relit le paiement jusqu'à un statut définitif (la saga peut encore tourner), avec un nombre d'essais borné.
- **Traçabilité** : la page d'un paiement affiche son journal d'événements (type, version, corrélation, cause) tel que renvoyé par le service.
- **Tests** : Vitest pour les utilitaires, l'authentification et les stores ; Playwright contre la **vraie pile** (Keycloak, Gateway, services, PostgreSQL, Kafka), jamais contre des réponses simulées. Un parcours d'arrêt réel du Ledger n'est lancé que sur demande (`E2E_PANNES=1`).

## Conséquences

- Les tests de bout en bout sont plus lents et supposent la pile démarrée (`scripts/lancer-services.sh`), mais ils attrapent les vraies erreurs d'intégration : ils ont par exemple révélé que le nom de l'organisation n'était lu qu'au chargement de la page, pas après la connexion.
- Pas de rafraîchissement temps réel : la liste se relit à la demande. Les notifications poussées (SSE) viendront avec l'observabilité.
- Une session de plusieurs onglets demande de se connecter dans chacun, par choix.
