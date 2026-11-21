# ADR-014 : Consommateurs idempotents, version d'agrégat et file morte

**État** : acceptée.

## Contexte

Avec une livraison au moins une fois (ADR-013), un consommateur reçoit parfois deux fois le même événement, et, après une reprise ou un rééquilibrage, il peut recevoir un événement ancien après un plus récent. Un message illisible ou mal formé ne doit pas bloquer une partition.

## Décision

- **Table `inbox`.** Un consommateur insère `(consommateur, event_id)` **dans la transaction qui applique l'effet**. Si l'insertion ne crée aucune ligne, l'événement a déjà été traité et il est ignoré. Un échec annule l'effet et la marque ensemble : l'événement sera retraité.
- **Version d'agrégat.** Les projections d'état (statut d'un paiement) n'avancent que si `aggregateVersion` est strictement supérieure à celle déjà appliquée (`ON CONFLICT … WHERE version <`). Un événement ancien arrivé après un récent ne fait pas régresser la projection. Les projections additives (variation d'un solde) sont protégées par l'inbox seule : leurs effets se cumulent dans n'importe quel ordre.
- **Tenant.** Le consommateur refuse un événement dont la clé de partition ne correspond pas à `tenantId:aggregateId`, puis exécute le traitement dans l'organisation déclarée, **RLS appliquée** : une projection ne peut pas être écrite dans une autre organisation.
- **Premier consommateur** : Account construit, depuis `payment.events` et `ledger.events`, un solde projeté par compte et l'activité des paiements (`GET /api/v1/accounts/{id}/activity`). C'est une vue de consultation ; le Ledger reste la vérité comptable.
- **Erreurs.** Un échec technique est retenté trois fois, à demi-seconde d'intervalle. Un événement mal formé (clé incohérente, tenant invalide, charge utile inconnue) n'est pas retenté. Dans les deux cas, après les reprises, le message part dans `<topic>.dlq`, avec les en-têtes d'exception, et la partition continue. Un message illisible est republié tel quel, en octets.
- **Pas de lecture automatique des files mortes.** Elles se consultent et se rejouent à la main, comme une anomalie à examiner.

## Conséquences

- Un événement rejoué cent fois produit un seul effet.
- Le solde projeté d'Account peut être en retard sur le Ledger de quelques secondes ; il ne le contredit pas durablement, et ne sert pas à décider.
- Un événement perdu côté consommateur ne se détecte pas tout seul : une réconciliation périodique entre projection et Ledger reste à faire (hors périmètre de cet incrément).
