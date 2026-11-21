#!/bin/sh
# Une base logique par service, chacune avec deux rôles (ADR-005, ADR-006) :
#   finflow_<service>_owner : propriétaire du schéma, exécute les migrations Flyway ;
#   finflow_<service>_app   : connexion du service, sans privilège particulier (pas de BYPASSRLS).
# Aucun rôle ne peut se connecter à la base d'un autre service.
# Mots de passe de développement uniquement ; en production, ils viennent de Secrets Manager.
set -eu

for service in tenant account payment ledger; do
  owner="finflow_${service}_owner"
  app="finflow_${service}_app"
  db="finflow_${service}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE ${owner} LOGIN PASSWORD '${owner}_dev';
CREATE ROLE ${app} LOGIN PASSWORD '${app}_dev';
CREATE DATABASE ${db} OWNER ${owner};
REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
GRANT CONNECT ON DATABASE ${db} TO ${app};
SQL
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<SQL
ALTER SCHEMA public OWNER TO ${owner};
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
done
