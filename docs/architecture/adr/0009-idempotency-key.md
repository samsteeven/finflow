# ADR-009 : Idempotency-Key sur la création d'un paiement

**État** : acceptée.

## Contexte

Un client qui n'a pas reçu de réponse, par exemple après un délai dépassé, renvoie sa requête. Le blueprint exige que dix envois de la même demande produisent un seul paiement, avec la contrainte `UNIQUE (tenant_id, idempotency_key)`.

## Décision

- `POST /api/v1/payments` **exige** l'en-tête `Idempotency-Key` (8 à 100 caractères). Sans lui, la requête est refusée en 400.
- La table `idempotency_keys` a pour clé `(tenant_id, idempotency_key)` et conserve une **empreinte SHA-256 du corps** de la requête et l'identifiant du paiement créé.
- La clé est insérée **dans la transaction** qui écrit `PaymentCreated`. Deux requêtes identiques et simultanées se heurtent à l'index unique : PostgreSQL fait attendre la seconde jusqu'à la validation de la première, puis lui signale le doublon. La seconde relit alors le paiement existant.
- Le **rejeu** répond `202` avec l'état **courant** du paiement et l'en-tête `Idempotent-Replayed: true`. Ce n'est pas une copie figée de la première réponse : un client qui reprend voit où en est son paiement.
- Si le paiement est resté en `VALIDATING`, parce qu'Account était indisponible lors du premier envoi, le rejeu **relance la validation**. Un client qui réessaie fait ainsi avancer le paiement, sans risque de doublon (la version optimiste de l'ADR-008 départage deux reprises simultanées).
- Une même clé réutilisée avec un **corps différent** est refusée en `422`. Elle ne crée rien et ne révèle rien du premier paiement.
- La clé est propre à chaque tenant : deux organisations peuvent utiliser la même valeur.

## Conséquences

- Les clés ne sont pas encore purgées. Une expiration (par exemple 24 heures) viendra avec les tâches planifiées.
- Le Ledger aura sa propre clé d'idempotence par opération comptable (incrément 3) : l'idempotence de l'API ne protège pas une comptabilisation rejouée par la saga.
