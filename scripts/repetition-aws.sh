#!/usr/bin/env bash
# Répétition générale locale de l'environnement AWS (ADR-018, infrastructure/aws/repetition-locale).
# Construit les images exactement comme la CI les publiera, lance la pile avec la même configuration que les tâches ECS,
# et vérifie ce qui ne peut pas l'être avant un vrai déploiement : démarrage non root, système de fichiers en lecture
# seule, 1 Go de mémoire, bases créées par l'initialisation, migrations avec le rôle propriétaire, isolation entre bases,
# jetons Keycloak acceptés par la Gateway (émetteur, clés, audience), topics Kafka créés.
#   scripts/repetition-aws.sh [--garder]     --garder laisse la pile en route (docker compose -p finflow-aws down -v pour l'arrêter)
# Prérequis : Docker, jars construits (cd backend && ./mvnw -DskipTests package), Python 3 pour générer le realm.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

compose="docker compose -f infrastructure/aws/repetition-locale/docker-compose.yml"
garder=false
[ "${1:-}" = "--garder" ] && garder=true

ok() { printf '  ✓ %s\n' "$1"; }
ko() { printf '  ✗ %s\n' "$1" >&2; $compose logs --tail 30 2>&1 | tail -60 >&2 || true; exit 1; }
arret() { $garder || $compose down -v --remove-orphans >/dev/null 2>&1; }
trap arret EXIT

for s in api-gateway tenant-service account-service ledger-service payment-service; do
  ls backend/$s/target/$s-*-SNAPSHOT.jar >/dev/null 2>&1 || { echo "jar absent : lancer d'abord (cd backend && ./mvnw -DskipTests package)" >&2; exit 1; }
done

echo "Images"
python infrastructure/keycloak/generer-realm.py --cloud >/dev/null
for s in api-gateway tenant-service account-service ledger-service payment-service; do
  docker build -q --build-arg SERVICE=$s -t finflow/$s:local backend >/dev/null
done
docker build -q -f infrastructure/aws/keycloak/Dockerfile -t finflow/keycloak:local infrastructure/aws/keycloak >/dev/null
docker build -q -t finflow/init-bases:local infrastructure/aws/init-bases >/dev/null
ok "7 images construites"

echo "Pile"
$compose down -v --remove-orphans >/dev/null 2>&1 || true
$compose up -d >/dev/null 2>&1

attendre() { # $1 service, $2 port
  for _ in $(seq 1 90); do
    if $compose exec -T "$1" wget -qO- "http://localhost:$2/actuator/health/readiness" 2>/dev/null | grep -q UP; then return 0; fi
    sleep 3
  done
  return 1
}
for pair in tenant-service:8081 account-service:8082 ledger-service:8084 payment-service:8083 api-gateway:8080; do
  attendre "${pair%%:*}" "${pair##*:}" && ok "${pair%%:*} prêt" || ko "${pair%%:*} ne devient pas prêt"
done

echo "Bases"
psql_owner() { # $1 base, $2 rôle, $3 mot de passe, $4 requête
  $compose exec -T -e PGPASSWORD="$3" postgres psql -h localhost -U "$2" -d "$1" -tA -c "$4" 2>&1
}
for pair in tenant:t account:a ledger:l payment:p; do
  svc="${pair%%:*}"; k="${pair##*:}"
  n=$(psql_owner "finflow_$svc" "finflow_${svc}_owner" "essai-$k-o" "select count(*) from flyway_schema_history where success" | tr -d '[:space:]')
  [ "${n:-0}" -ge 1 ] 2>/dev/null && ok "migrations de $svc appliquées ($n)" || ko "pas de migration pour $svc : $n"
done
refus=$(psql_owner finflow_ledger finflow_tenant_app essai-t-a "select 1" || true)
echo "$refus" | grep -qi "permission denied\|authentication failed\|FATAL" && ok "le rôle applicatif de tenant ne se connecte pas à la base ledger" || ko "isolation entre bases rompue : $refus"

echo "Kafka"
topics=$($compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | grep -c . || true)
[ "$topics" -ge 1 ] && ok "$topics topics créés par les services" || ko "aucun topic"

echo "Authentification"
token=$(curl -sf -X POST http://localhost:18180/auth/realms/finflow/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=payment-service -d client_secret=secret-de-paiement-essai \
  | python -c 'import json,sys; print(json.load(sys.stdin)["access_token"])') || ko "pas de jeton Keycloak"
ok "jeton obtenu du compte technique de Payment"
code_http() { curl -s -w '\n%{http_code}' "$@" | tail -n 1; }
sans=$(code_http http://localhost:18081/api/v1/accounts)
[ "$sans" = "401" ] && ok "sans jeton : 401" || ko "sans jeton : $sans au lieu de 401"
avec=$(code_http -H "Authorization: Bearer $token" http://localhost:18081/api/v1/accounts)
[ "$avec" != "401" ] && ok "avec jeton : $avec (signature, émetteur et audience acceptés ; pas d'organisation dans ce jeton)" || ko "jeton refusé par la Gateway (401)"

echo "Journaux"
if $compose logs payment-service account-service ledger-service 2>&1 | grep -E " ERROR " | head -3 | grep -q .; then
  $compose logs payment-service account-service ledger-service 2>&1 | grep -E " ERROR " | head -5 >&2
  ko "erreurs au démarrage"
fi
ok "aucune erreur au démarrage de Payment, Account, Ledger"

echo
echo "Répétition réussie."
