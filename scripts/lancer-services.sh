#!/usr/bin/env bash
# Lance les cinq services Java depuis les jars déjà construits (cd backend && ./mvnw -DskipTests package),
# avec Kafka et la reprise des sagas activés. Prérequis : docker compose --profile kafka up -d --wait.
#
# Usage : scripts/lancer-services.sh start | stop            tous les services
#         scripts/lancer-services.sh start <nom> | stop <nom>   un seul (tenant account payment ledger gateway)
# Journaux dans .logs/, identifiants de processus dans .logs/pids. Variable JAVA : chemin de l'exécutable java.
set -euo pipefail

racine="$(cd "$(dirname "$0")/.." && pwd)"
logs="$racine/.logs"
java="${JAVA:-java}"
export MESSAGING_ENABLED=true SAGA_RECOVERY_ENABLED=true

# nom : module port options
declare -A MODULE=([tenant]=tenant-service [account]=account-service [payment]=payment-service
                   [ledger]=ledger-service [gateway]=api-gateway)
declare -A PORT=([tenant]=8081 [account]=8082 [payment]=8083 [ledger]=8084 [gateway]=8080)
declare -A OPTIONS=([tenant]="-Dspring.profiles.active=demo")
ORDRE=(tenant account payment ledger gateway)

lancer() {
  if curl -sf "http://localhost:${PORT[$1]}/actuator/health" >/dev/null 2>&1; then
    echo "  $1 déjà lancé"
    return
  fi
  local nom="$1" jar="$racine/backend/${MODULE[$1]}/target/${MODULE[$1]}-0.1.0-SNAPSHOT.jar"
  [[ -f "$jar" ]] || { echo "jar absent : $jar (lancez ./mvnw -DskipTests package dans backend)" >&2; exit 1; }
  # shellcheck disable=SC2086
  nohup "$java" ${OPTIONS[$nom]:-} -jar "$jar" >>"$logs/$nom.log" 2>&1 &
  echo "$! $nom" >>"$logs/pids"
  echo "  $nom lancé (pid $!, port ${PORT[$nom]})"
}

attendre() {
  for _ in $(seq 1 90); do
    if curl -sf "http://localhost:${PORT[$1]}/actuator/health" >/dev/null 2>&1; then
      echo "  $1 prêt"
      return
    fi
    sleep 2
  done
  echo "  $1 n'a pas démarré : voir $logs/$1.log" >&2
  exit 1
}

arreter() {
  [[ -f "$logs/pids" ]] || return 0
  local reste=""
  while read -r pid nom; do
    if [[ -z "${1:-}" || "$1" == "$nom" ]]; then
      kill "$pid" 2>/dev/null && echo "  $nom arrêté" || true
    else
      reste+="$pid $nom"$'\n'
    fi
  done <"$logs/pids"
  printf '%s' "$reste" >"$logs/pids"
}

cibles() { if [[ -n "${1:-}" ]]; then echo "$1"; else echo "${ORDRE[@]}"; fi; }

mkdir -p "$logs"
touch "$logs/pids"
case "${1:-start}" in
  start)
    for nom in $(cibles "${2:-}"); do lancer "$nom"; done
    for nom in $(cibles "${2:-}"); do attendre "$nom"; done
    ;;
  stop)
    arreter "${2:-}"
    ;;
  *)
    echo "Usage : $0 start|stop [service]" >&2
    exit 2
    ;;
esac
