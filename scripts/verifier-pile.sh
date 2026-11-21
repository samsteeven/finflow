#!/usr/bin/env bash
# Vérifie la pile locale de bout en bout : vrais jetons Keycloak → Gateway → tenant-service → RLS.
# Prérequis : docker compose up -d, puis tenant-service (profil demo) sur 8081, account-service,
# payment-service, ledger-service (8082 à 8084) et la Gateway sur 8080 (voir README). Dépend de curl et jq.
set -euo pipefail

KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8180}"
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
ALPHA="a1a1a1a1-0000-4000-8000-000000000001"
BETA="b2b2b2b2-0000-4000-8000-000000000002"
echecs=0

jeton() {
  curl -sf "$KEYCLOAK/realms/finflow/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=finflow-dev-cli \
    -d username="$1" -d password=finflow-dev | jq -r .access_token
}

claims() {
  local payload
  payload=$(cut -d. -f2 <<<"$1" | tr '_-' '/+')
  while (( ${#payload} % 4 )); do payload+="="; done
  base64 -d <<<"$payload" 2>/dev/null
}

verifier() { # description, statut attendu, statut obtenu
  if [[ "$2" == "$3" ]]; then
    echo "  ok      $1 ($3)"
  else
    echo "  ÉCHEC   $1 : attendu $2, obtenu $3"
    echecs=$((echecs + 1))
  fi
}

statut() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "Jetons Keycloak"
alice=$(jeton alice)
bruno=$(jeton bruno)
plateforme=$(jeton plateforme)
verifier "claim tenant_id d'alice" "$ALPHA" "$(claims "$alice" | jq -r .tenant_id)"
verifier "audience finflow-api" "true" "$(claims "$alice" | jq '[.aud] | flatten | index("finflow-api") != null')"
verifier "rôle tenant-admin d'alice" "true" "$(claims "$alice" | jq '.realm_access.roles | index("tenant-admin") != null')"

echo "Gateway et tenant-service"
verifier "sans jeton" 401 "$(statut "$GATEWAY/api/v1/tenants/current")"
verifier "alice lit son organisation" "Alpha Industries" \
  "$(curl -sf -H "Authorization: Bearer $alice" "$GATEWAY/api/v1/tenants/current" | jq -r .name)"
verifier "bruno lit son organisation" "Beta Logistique" \
  "$(curl -sf -H "Authorization: Bearer $bruno" "$GATEWAY/api/v1/tenants/current" | jq -r .name)"
verifier "alice lit Beta par son identifiant" 404 \
  "$(statut -H "Authorization: Bearer $alice" "$GATEWAY/api/v1/tenants/$BETA")"
verifier "alice se fait passer pour Beta par en-tête" "Alpha Industries" \
  "$(curl -sf -H "Authorization: Bearer $alice" -H "X-Tenant-Id: $BETA" "$GATEWAY/api/v1/tenants/current" | jq -r .name)"
verifier "alice crée une organisation" 403 \
  "$(statut -X POST -H "Authorization: Bearer $alice" -H 'Content-Type: application/json' \
      -d '{"name":"Intrus","slug":"intrus","plan":"SANDBOX","adminUserId":"x"}' "$GATEWAY/api/v1/tenants")"
slug="gamma-$(date +%s)"
verifier "la plateforme crée une organisation" 201 \
  "$(statut -X POST -H "Authorization: Bearer $plateforme" -H 'Content-Type: application/json' \
      -d "{\"name\":\"Gamma\",\"slug\":\"$slug\",\"plan\":\"SANDBOX\",\"adminUserId\":\"admin-gamma\"}" \
      "$GATEWAY/api/v1/tenants")"

echo "Comptes et paiements"
solde() { # jeton, compte
  curl -sf -H "Authorization: Bearer $1" "$GATEWAY/api/v1/ledger/accounts/$2/balance" | jq -r .balanceCents
}
deposer() { # jeton, clé, compte, montant
  curl -s -X POST -H "Authorization: Bearer $1" -H "Idempotency-Key: $2" -H 'Content-Type: application/json' \
    -d "{\"accountId\":\"$3\",\"amountCents\":$4}" "$GATEWAY/api/v1/ledger/deposits"
}
ouvrir() { # jeton, nom
  curl -sf -X POST -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$2\",\"type\":\"CURRENT\",\"perTransactionLimitCents\":200000,\"dailyLimitCents\":500000}" \
    "$GATEWAY/api/v1/accounts" | jq -r .id
}
payer() { # jeton, clé, source, destination, montant
  curl -s -X POST -H "Authorization: Bearer $1" -H "Idempotency-Key: $2" -H 'Content-Type: application/json' \
    -d "{\"sourceAccountId\":\"$3\",\"destinationAccountId\":\"$4\",\"amountCents\":$5}" "$GATEWAY/api/v1/payments"
}
compte_a=$(ouvrir "$alice" "Compte A")
compte_b=$(ouvrir "$alice" "Compte B")
cle="verif-$(date +%s)-$RANDOM"
paiement=$(payer "$alice" "$cle" "$compte_a" "$compte_b" 150000)
id=$(jq -r .id <<<"$paiement")
verifier "transfert sans fonds : refusé par le Ledger" "REJECTED" "$(jq -r .status <<<"$paiement")"
verifier "motif du refus" "FONDS_INSUFFISANTS" "$(jq -r .rejection.rule <<<"$paiement")"
verifier "rejeu de la même Idempotency-Key" "$id" "$(payer "$alice" "$cle" "$compte_a" "$compte_b" 150000 | jq -r .id)"
verifier "historique du paiement rejeté" "PaymentCreated PaymentValidationStarted PaymentValidated PaymentRejected" \
  "$(curl -sf -H "Authorization: Bearer $alice" "$GATEWAY/api/v1/payments/$id/events" | jq -r '[.[].eventType] | join(" ")')"
verifier "limite par opération" "LIMITE_PAR_OPERATION" \
  "$(payer "$alice" "verif-$RANDOM-$RANDOM" "$compte_a" "$compte_b" 250000 | jq -r .rejection.rule)"
verifier "bruno lit le paiement d'Alpha" 404 \
  "$(statut -H "Authorization: Bearer $bruno" "$GATEWAY/api/v1/payments/$id")"
verifier "bruno débite un compte d'Alpha" "COMPTE_SOURCE_INTROUVABLE" \
  "$(payer "$bruno" "verif-$RANDOM-$RANDOM" "$compte_a" "$compte_b" 100 | jq -r .rejection.rule)"

echo "Grand livre"
verifier "écritures internes non routées" 404 \
  "$(statut -X POST -H "Authorization: Bearer $alice" -H 'Content-Type: application/json' \
      -d '{}' "$GATEWAY/internal/v1/ledger/transfers")"
verifier "bruno ne dépose pas sur un compte d'Alpha" 404 \
  "$(statut -X POST -H "Authorization: Bearer $bruno" -H "Idempotency-Key: verif-$RANDOM-$RANDOM" \
      -H 'Content-Type: application/json' -d "{\"accountId\":\"$compte_a\",\"amountCents\":100}" \
      "$GATEWAY/api/v1/ledger/deposits")"
cle_depot="depot-$(date +%s)-$RANDOM"
depot=$(deposer "$alice" "$cle_depot" "$compte_a" 300000)
verifier "dépôt simulé de 3 000 €" "DEPOSIT" "$(jq -r .kind <<<"$depot")"
verifier "rejeu du dépôt (même clé)" "$(jq -r .id <<<"$depot")" "$(deposer "$alice" "$cle_depot" "$compte_a" 300000 | jq -r .id)"
verifier "solde après dépôt" "300000" "$(solde "$alice" "$compte_a")"

cle2="verif-$(date +%s)-$RANDOM"
paiement2=$(payer "$alice" "$cle2" "$compte_a" "$compte_b" 150000)
id2=$(jq -r .id <<<"$paiement2")
verifier "transfert de 1 500 € comptabilisé" "COMPLETED" "$(jq -r .status <<<"$paiement2")"
verifier "solde du compte source" "150000" "$(solde "$alice" "$compte_a")"
verifier "solde du compte destinataire" "150000" "$(solde "$alice" "$compte_b")"
verifier "rejeu du transfert sans double écriture" "150000" \
  "$(payer "$alice" "$cle2" "$compte_a" "$compte_b" 150000 >/dev/null; solde "$alice" "$compte_a")"
verifier "historique du transfert" "PaymentCreated PaymentValidationStarted PaymentValidated PaymentCompleted" \
  "$(curl -sf -H "Authorization: Bearer $alice" "$GATEWAY/api/v1/payments/$id2/events" | jq -r '[.[].eventType] | join(" ")')"
verifier "bruno ne lit pas le solde d'Alpha" 404 \
  "$(statut -H "Authorization: Bearer $bruno" "$GATEWAY/api/v1/ledger/accounts/$compte_a/balance")"
verifier "remboursement par contrepassation" "REFUNDED" \
  "$(curl -s -X POST -H "Authorization: Bearer $alice" -H 'Content-Type: application/json' -d '{"reason":"verification"}' \
      "$GATEWAY/api/v1/payments/$id2/refunds" | jq -r .status)"
verifier "solde source rétabli" "300000" "$(solde "$alice" "$compte_a")"
verifier "solde destinataire rétabli" "0" "$(solde "$alice" "$compte_b")"
verifier "second remboursement refusé" 409 \
  "$(statut -X POST -H "Authorization: Bearer $alice" -H 'Content-Type: application/json' -d '{}' \
      "$GATEWAY/api/v1/payments/$id2/refunds")"

if (( echecs > 0 )); then
  echo "$echecs vérification(s) en échec"
  exit 1
fi
echo "Pile vérifiée."
