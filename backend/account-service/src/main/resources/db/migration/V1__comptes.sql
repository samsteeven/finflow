-- Comptes financiers d'une organisation (ADR-005 pour l'isolation).
-- Le solde n'est pas ici : la vérité comptable appartient au Ledger.

CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE TABLE accounts (
    id                          uuid PRIMARY KEY,
    tenant_id                   uuid         NOT NULL,
    name                        varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    type                        varchar(20)  NOT NULL CHECK (type IN ('CURRENT', 'SAVINGS')),
    -- Premier périmètre : transferts internes en EUR uniquement.
    currency                    varchar(3)   NOT NULL CHECK (currency = 'EUR'),
    status                      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    -- Montants en centimes, jamais en flottant.
    per_transaction_limit_cents bigint       NOT NULL CHECK (per_transaction_limit_cents > 0),
    daily_limit_cents           bigint       NOT NULL CHECK (daily_limit_cents > 0),
    created_by                  varchar(64)  NOT NULL,
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    updated_at                  timestamptz  NOT NULL DEFAULT now(),
    version                     bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CONSTRAINT limite_journaliere_au_moins_limite_par_operation
        CHECK (daily_limit_cents >= per_transaction_limit_cents)
);

CREATE INDEX accounts_tenant_created ON accounts (tenant_id, created_at);

-- Un compte clôturé est définitif : la base refuse toute modification, quel que soit le code.
CREATE FUNCTION refuser_modification_compte_cloture() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'Le compte % est clôturé : il ne peut plus être modifié', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER compte_cloture_immuable
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION refuser_modification_compte_cloture();

ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Un compte se clôture, il ne se supprime pas.
GRANT USAGE ON SCHEMA public TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON accounts TO ${app_role};
GRANT EXECUTE ON FUNCTION current_tenant_id() TO ${app_role};
