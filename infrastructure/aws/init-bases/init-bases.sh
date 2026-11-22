#!/bin/sh
# Bases et rôles de l'environnement AWS (ADR-018, ADR-005). Reprend 01-bases-et-roles.sh (pile locale) avec trois
# différences : les mots de passe viennent de l'environnement (Secrets Manager), le script peut être rejoué sans effet
# de bord (chaque déploiement le relance et resynchronise les mots de passe), et le rôle d'administration de RDS
# n'étant pas super-utilisateur, il devient membre d'un rôle avant de créer une base à son nom.
#
# Variables : PGHOST, PGUSER, PGPASSWORD (administrateur RDS), PGSSLMODE ; pour chaque service
# (TENANT, ACCOUNT, PAYMENT, LEDGER) : <SERVICE>_OWNER_PASSWORD et <SERVICE>_APP_PASSWORD ; KEYCLOAK_PASSWORD.
set -eu

admin() { psql -v ON_ERROR_STOP=1 --no-psqlrc --quiet "$@"; }

# $1 base, $2 propriétaire, $3 mot de passe du propriétaire, $4 rôle applicatif (ou vide), $5 mot de passe applicatif
creer_base() {
  db="$1"; owner="$2"; owner_pw="$3"; app="${4:-}"; app_pw="${5:-}"
  admin --dbname postgres -v db="$db" -v owner="$owner" -v owner_pw="$owner_pw" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'owner') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'owner') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'owner', :'owner_pw') \gexec
SELECT format('GRANT %I TO CURRENT_USER', :'owner') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'owner') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db') \gexec
SQL
  if [ -n "$app" ]; then
    admin --dbname postgres -v db="$db" -v app="$app" -v app_pw="$app_pw" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'app') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'app', :'app_pw') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db', :'app') \gexec
SQL
  fi
  admin --dbname "$db" -v owner="$owner" <<'SQL'
SELECT format('ALTER SCHEMA public OWNER TO %I', :'owner') \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
  echo "base ${db} : prête"
}

for service in tenant account payment ledger; do
  upper=$(echo "$service" | tr '[:lower:]' '[:upper:]')
  eval "owner_pw=\${${upper}_OWNER_PASSWORD}"
  eval "app_pw=\${${upper}_APP_PASSWORD}"
  creer_base "finflow_${service}" "finflow_${service}_owner" "$owner_pw" "finflow_${service}_app" "$app_pw"
done

# Keycloak gère son propre schéma : un seul rôle, propriétaire de sa base.
creer_base "finflow_keycloak" "finflow_keycloak" "${KEYCLOAK_PASSWORD}"
