-- Event store des paiements (ADR-008), idempotence (ADR-009), isolation (ADR-005).

CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- Journal en ajout seul : la clé (payment_id, version) refuse deux écritures concurrentes de la
-- même version (concurrence optimiste).
CREATE TABLE payment_events (
    payment_id     uuid         NOT NULL,
    version        integer      NOT NULL CHECK (version > 0),
    tenant_id      uuid         NOT NULL,
    event_id       uuid         NOT NULL UNIQUE,
    event_type     varchar(60)  NOT NULL,
    event_version  smallint     NOT NULL CHECK (event_version > 0),
    payload        jsonb        NOT NULL,
    correlation_id varchar(100),
    causation_id   uuid,
    occurred_at    timestamptz  NOT NULL,
    recorded_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, version)
);

CREATE TABLE payment_snapshots (
    payment_id uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    version    integer     NOT NULL CHECK (version > 0),
    state      jsonb       NOT NULL,
    taken_at   timestamptz NOT NULL DEFAULT now()
);

-- Modèle de lecture, mis à jour dans la transaction qui écrit les événements.
CREATE TABLE payment_summaries (
    payment_id             uuid        PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    source_account_id      uuid        NOT NULL,
    destination_account_id uuid        NOT NULL,
    amount_cents           bigint      NOT NULL CHECK (amount_cents > 0),
    currency               varchar(3)  NOT NULL CHECK (currency = 'EUR'),
    status                 varchar(20) NOT NULL CHECK (status IN ('CREATED', 'VALIDATING', 'REJECTED', 'PROCESSING',
                                                                  'COMPLETED', 'FAILED', 'REFUND_PENDING', 'REFUNDED')),
    version                integer     NOT NULL CHECK (version > 0),
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX payment_summaries_source_jour ON payment_summaries (tenant_id, source_account_id, created_at);
CREATE INDEX payment_summaries_recents ON payment_summaries (tenant_id, created_at DESC);

CREATE TABLE idempotency_keys (
    tenant_id       uuid         NOT NULL,
    idempotency_key varchar(100) NOT NULL,
    request_hash    varchar(64)  NOT NULL,
    payment_id      uuid         NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key)
);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['payment_events', 'payment_snapshots', 'payment_summaries', 'idempotency_keys'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) '
                       'WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END
$$;

GRANT USAGE ON SCHEMA public TO ${app_role};
-- Un événement ou une clé d'idempotence ne se modifie ni ne se supprime.
GRANT SELECT, INSERT ON payment_events, idempotency_keys TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON payment_snapshots, payment_summaries TO ${app_role};
GRANT EXECUTE ON FUNCTION current_tenant_id() TO ${app_role};
