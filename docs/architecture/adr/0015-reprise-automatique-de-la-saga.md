# ADR-015 : Reprise automatique de la saga par un compte de service

**État** : acceptée.

## Contexte

À l'incrément 3, un paiement `FAILED` (Ledger indisponible, réponse perdue) ne repart que si le client rejoue sa requête. Le blueprint veut que la saga attende et reprenne seule, avec la même clé comptable.

Deux obstacles : le worker de reprise n'a ni requête ni jeton d'utilisateur (le Ledger exige un jeton, et Payment relayait celui de l'appelant), et la RLS interdit de lister les paiements de toutes les organisations.

## Décision

- **Worker de reprise** dans Payment (désactivé par défaut, activé par `SAGA_RECOVERY_ENABLED`). Il cherche les paiements `PROCESSING`, `FAILED` ou `REFUND_PENDING` restés en suspens, puis rejoue l'étape comptable avec **la même clé** (`payment:<id>` ou `refund:<id>`). Le Ledger renvoie la transaction déjà passée si la tentative précédente avait réussi : pas de double écriture.
- **Délai exponentiel.** Un paiement n'est repris qu'après un délai (5 s par défaut), doublé à chaque reprise (`PaymentRetried`). Au bout de trois reprises, il reste `FAILED` et attend une décision humaine : la saga ne boucle pas.
- **Lister sans contourner la RLS.** La RLS étant forcée, même le propriétaire des tables ne voit rien sans organisation : une fonction `SECURITY DEFINER` n'y change rien. Le worker n'a pas non plus de rôle qui l'ignore. Une table technique `saga_watch` garde de quoi reprendre (identifiants, statut, nombre de reprises, date), jamais de contenu métier. L'event store la tient à jour dans la même transaction que la projection des paiements : un paiement y entre quand il attend le Ledger et en sort dès qu'il est terminé. Chaque reprise s'exécute ensuite sous l'organisation du paiement, RLS appliquée. Comme l'outbox, cette table n'a pas de RLS et n'est exposée par aucune API.
- **Compte de service.** Le client Keycloak `payment-service` obtient un jeton par `client_credentials`, avec le rôle `service` et **sans organisation**. Hors d'une requête utilisateur, Payment appelle le Ledger avec ce jeton et l'en-tête `X-Tenant-Id`.
- **Délégation d'organisation, limitée.** Le Ledger ne lit `X-Tenant-Id` que si le jeton porte le rôle `service` et aucune organisation, et seulement sous `/internal/`. Pour tout autre appelant l'en-tête n'a aucun effet, et la Gateway retire l'en-tête et ne route pas `/internal/`. Cela reste cohérent avec ADR-004 : le tenant vient d'une source de confiance, ici un compte technique authentifié.
- **Limite connue.** Une validation restée en `VALIDATING` parce qu'Account était indisponible n'est pas reprise automatiquement : Account n'accepte pas encore le compte de service. Elle reprend au rejeu de la requête.

## Conséquences

- Un paiement échoué pendant une panne du Ledger finit `COMPLETED` sans aucune action du client, avec une seule écriture comptable.
- Le secret du client de service est un secret de développement dans le realm local ; en production il vient d'un coffre.
- Un jeton de service volé permettrait d'agir au nom de n'importe quelle organisation sur les routes internes. Ces routes ne sont pas exposées par la Gateway, et le jeton vit cinq minutes ; un contrôle réseau (mTLS ou groupe de sécurité) est à prévoir à l'incrément AWS.
