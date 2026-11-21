# ADR-004 : Keycloak comme fournisseur d'identité, tenant porté par le jeton

**État** : acceptée.

## Contexte

Le blueprint sépare l'identité technique (Keycloak : identifiants, sessions, OIDC) des données métier d'organisation (tenant-service). Il exige que le tenant vienne du contexte d'identité validé, jamais d'une valeur envoyée librement par le front.

## Décision

- Un realm Keycloak `finflow`, importé au démarrage depuis `infrastructure/keycloak/finflow-realm.json`. Le client `finflow-web` est public, utilise le flux *authorization code* avec PKCE (S256), et n'autorise ni le flux implicite ni les mots de passe directs.
- L'organisation de l'utilisateur est un **attribut `tenant_id`** déclaré dans le profil utilisateur Keycloak, **modifiable uniquement par un administrateur** : l'utilisateur ne peut pas se l'attribuer. Un mapper le place dans la claim `tenant_id` du jeton d'accès.
- Une audience dédiée `finflow-api` est ajoutée au jeton. Chaque service vérifie la signature (JWKS), l'émetteur, l'expiration et l'audience.
- Les rôles du realm (`platform-admin`, `tenant-admin`, `tenant-member`) deviennent des autorités `ROLE_*`. Les rôles et permissions fins par organisation, décrits dans le modèle du tenant-service, viendront plus tard.
- **Défense en profondeur** : la Gateway valide le jeton et le relaie, mais chaque service le valide à nouveau. Un service n'accorde donc aucune confiance à un en-tête posé par la Gateway. La Gateway supprime en plus tout en-tête `X-Tenant-Id` entrant, au cas où un service en aval le lirait par erreur.
- Le module `backend/shared/security-starter` contient la conversion du jeton (rôles, tenant) ; les services ne la recodent pas.

## Alternatives écartées

- **Keycloak Organizations** (claim `organization`) : c'est la bonne piste pour un utilisateur membre de plusieurs organisations (écran « sélection d'organisation »), mais l'identifiant porté est celui de Keycloak, pas celui du tenant-service. On y reviendra si le multi-appartenance devient nécessaire.
- **Tenant dans un en-tête choisi par le front** : contraire à la règle du blueprint.

## Conséquences

- Un utilisateur appartient à une seule organisation à la fois. Changer d'organisation passe par l'administrateur.
- Créer une organisation (tenant-service) et donner l'attribut `tenant_id` à son premier administrateur (Keycloak) sont deux étapes séparées. Leur automatisation, via l'API d'administration de Keycloak, viendra avec les invitations.
