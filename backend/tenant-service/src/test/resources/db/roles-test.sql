-- Même découpage qu'en local et sur AWS : un rôle propriétaire pour Flyway, un rôle applicatif
-- sans privilège particulier pour le service (ADR-005). Exécuté par le superutilisateur du conteneur.
CREATE ROLE finflow_tenant_owner LOGIN PASSWORD 'owner-test';
CREATE ROLE finflow_tenant_app LOGIN PASSWORD 'app-test';
ALTER SCHEMA public OWNER TO finflow_tenant_owner;
