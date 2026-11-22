#!/usr/bin/env bash
# Vérification d'un déploiement AWS, par l'adresse publique seulement (ADR-018) : ce que voit un visiteur.
#   scripts/verifier-deploiement.sh https://dxxxxxxxxxxxx.cloudfront.net
# Ne crée rien et n'utilise aucun identifiant : le parcours authentifié se fait à la main (docs/exploitation/aws.md).
set -euo pipefail

url="${1:?adresse publique requise (sortie « url » de Terraform)}"
url="${url%/}"
echec=0

code() { curl -s --max-time 20 -w '\n%{http_code}' "$@" | tail -n 1; }
verifier() { # $1 libellé, $2 attendu, $3 obtenu
  if [ "$2" = "$3" ]; then printf '  ✓ %s (%s)\n' "$1" "$3"; else printf '  ✗ %s : %s au lieu de %s\n' "$1" "$3" "$2" >&2; echec=1; fi
}

echo "Vérification de $url"
verifier "le front est servi" 200 "$(code "$url/")"
verifier "une route du front retombe sur l'application (SPA)" 200 "$(code "$url/accounts")"
verifier "l'API refuse un appel sans jeton" 401 "$(code "$url/api/v1/accounts")"
verifier "Keycloak publie sa configuration" 200 "$(code "$url/auth/realms/finflow/.well-known/openid-configuration")"
verifier "la console d'administration de Keycloak est fermée" 403 "$(code "$url/auth/admin/master/console/")"
verifier "le realm master est fermé" 403 "$(code "$url/auth/realms/master/.well-known/openid-configuration")"

emetteur=$(curl -s --max-time 20 "$url/auth/realms/finflow/.well-known/openid-configuration" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["issuer"])' 2>/dev/null || true)
verifier "l'émetteur des jetons est l'adresse publique" "$url/auth/realms/finflow" "$emetteur"

[ "$echec" = 0 ] && echo "Déploiement conforme." || { echo "Déploiement non conforme." >&2; exit 1; }
