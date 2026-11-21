-- Organisations (tenants) et appartenances, isolées par Row-Level Security (ADR-005).
-- Exécuté par le rôle propriétaire ; ${app_role} est le rôle de connexion de l'application.

CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

COMMENT ON FUNCTION current_tenant_id() IS
    'Tenant de la transaction courante, posé par set_config(..., true) ; NULL sans contexte : aucune ligne visible';

CREATE TABLE tenants (
    id         uuid PRIMARY KEY,
    name       varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    slug       varchar(40)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    status     varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    plan       varchar(20)  NOT NULL CHECK (plan IN ('SANDBOX', 'BUSINESS', 'ENTERPRISE')),
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    user_id    varchar(64) NOT NULL CHECK (length(btrim(user_id)) > 0),
    role       varchar(20) NOT NULL CHECK (role IN ('TENANT_ADMIN', 'TENANT_MEMBER')),
    status     varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id),
    UNIQUE (tenant_id, id)
);

-- FORCE : la politique s'applique aussi au propriétaire des tables.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = current_tenant_id())
    WITH CHECK (id = current_tenant_id());

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memberships
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Le rôle applicatif n'est propriétaire de rien et ne peut rien supprimer :
-- une organisation se suspend, une appartenance se révoque.
GRANT USAGE ON SCHEMA public TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON tenants, memberships TO ${app_role};
GRANT EXECUTE ON FUNCTION current_tenant_id() TO ${app_role};
