-- Projections alimentées par les événements Kafka (ADR-014). Account n'est pas la vérité
-- comptable : ces vues se consultent, le Ledger reste la source.

-- Événements déjà traités par un consommateur. Table technique sans RLS : la clé est l'événement.
CREATE TABLE inbox (
    consumer     varchar(60) NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE account_balance_projection (
    account_id    uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    balance_cents bigint      NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE payment_projection (
    payment_id             uuid        PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    source_account_id      uuid        NOT NULL,
    destination_account_id uuid        NOT NULL,
    amount_cents           bigint      NOT NULL,
    status                 varchar(20) NOT NULL,
    aggregate_version      bigint      NOT NULL,
    updated_at             timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX payment_projection_source ON payment_projection (tenant_id, source_account_id);
CREATE INDEX payment_projection_destination ON payment_projection (tenant_id, destination_account_id);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['account_balance_projection', 'payment_projection'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) '
                       'WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT ON inbox TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON account_balance_projection, payment_projection TO ${app_role};
