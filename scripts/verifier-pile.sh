#!/usr/bin/env bash
# Vérifie la pile locale de bout en bout : vrais jetons Keycloak → Gateway → tenant-service → RLS.
# Prérequis : docker compose up -d, puis tenant-service (profil demo) sur 8081 et la Gateway
# sur 8080 (voir README). Dépend de curl et jq.
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

if (( echecs > 0 )); then
  echo "$echecs vérification(s) en échec"
  exit 1
fi
echo "Pile vérifiée."
