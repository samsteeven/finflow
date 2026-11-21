# ADR-011 : Grand livre en partie double, garanti par PostgreSQL

**État** : acceptée.

## Contexte

Le Ledger est la source de vérité des mouvements financiers. Le blueprint exige trois choses :
- chaque transaction est équilibrée (total des débits = total des crédits) ;
- une écriture validée ne se modifie ni ne se supprime ;
- le contrôle est fait **en base**, parce qu'une validation Java ne protège pas des traitements concurrents.

Il prévoit aussi un event store et des snapshots pour le Ledger.

## Décision

- **Trois tables** :
  - `ledger_accounts` : un compte du grand livre par compte financier, plus un compte de compensation `CLEARING` par organisation ;
  - `journal_transactions` : l'en-tête de chaque opération ;
  - `journal_entries` : les lignes au débit et au crédit, montants en centimes strictement positifs.
- **Le journal est le journal d'événements du Ledger.** Une transaction comptabilisée est l'événement `LedgerTransactionPosted`. Il n'existe pas de table d'événements séparée qui doublerait le journal. Le rôle applicatif n'a que `SELECT` et `INSERT` sur le journal : rien ne se modifie, rien ne se supprime.
- **Équilibre vérifié à la validation** : deux déclencheurs de contrainte différés (`DEFERRABLE INITIALLY DEFERRED`) contrôlent au `COMMIT` que chaque transaction concernée a au moins deux lignes, une seule devise et des débits égaux aux crédits. Une ligne ajoutée plus tard à une transaction déjà validée la déséquilibre : elle est refusée de la même façon.
- **Soldes tenus par la base** : un déclencheur `AFTER INSERT` sur `journal_entries` met à jour `ledger_accounts.balance_cents`. Le rôle applicatif n'a **aucun droit de modification sur les soldes** : il n'a pas `UPDATE` sur `balance_cents` (seule la colonne `created_at` est modifiable, car `SELECT … FOR UPDATE` exige un droit de modification), et son droit `INSERT` ne couvre pas cette colonne. Un solde ne bouge donc que par une écriture. Ce solde courant joue le rôle de snapshot : il évite de relire tout le journal, et une fonction de contrôle vérifie qu'il est toujours égal à la somme des écritures.
- **Pas de découvert** : la contrainte `solde_non_negatif` refuse tout solde négatif, sauf sur le compte de compensation. Avant d'écrire, le service verrouille les comptes concernés (`SELECT … FOR UPDATE`, **toujours dans l'ordre des identifiants**, pour éviter les interblocages entre A→B et B→A). Il vérifie ensuite le solde pour renvoyer un refus explicite (`FONDS_INSUFFISANTS`). La contrainte reste le filet de sécurité.
- **Convention de signe** : du point de vue d'un compte client, un crédit augmente le solde et un débit le diminue. Un dépôt simulé débite le compte de compensation et crédite le compte client. Un transfert débite la source et crédite la destination.
- **Correction par contrepassation** : une `REVERSAL` inverse les lignes d'une transaction sans y toucher. `reversal_of` est unique, donc une transaction ne se contrepasse qu'une fois, et une contrepassation ne se contrepasse pas.
- **Idempotence comptable** : chaque opération porte une clé unique par organisation (`payment:<id>` pour un transfert, `refund:<id>` pour un remboursement) et l'empreinte de son contenu. Un rejeu renvoie la transaction existante, et une même clé avec un autre contenu est refusée. Cette protection est distincte de l'`Idempotency-Key` de l'API Payment (ADR-009).
- Mêmes règles d'isolation que les autres services : RLS forcée, rôle applicatif sans privilège, contexte tenant posé à chaque transaction.

## Conséquences

- Les garanties tiennent même si le code Java se trompe ; des tests les vérifient directement en SQL avec le rôle applicatif.
- Le solde exposé par account-service (vue de consultation) sera alimenté par `ledger.events` à l'incrément 4. En attendant, le solde se lit dans le Ledger.
- Les comptes du grand livre sont créés à la première opération, après une vérification auprès d'Account (dépôt) ou de Payment (transfert déjà validé).
