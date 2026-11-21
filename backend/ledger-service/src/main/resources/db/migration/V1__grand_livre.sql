-- Grand livre en partie double (ADR-011) : l'équilibre, l'immuabilité et les soldes sont tenus
-- par la base, pas seulement par le code Java.

CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- Un compte du grand livre par compte financier, plus un compte de compensation par organisation
-- (la contrepartie des dépôts simulés). Le solde n'est modifié que par le déclencheur ci-dessous.
CREATE TABLE ledger_accounts (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    kind          varchar(10) NOT NULL CHECK (kind IN ('CUSTOMER', 'CLEARING')),
    currency      varchar(3)  NOT NULL CHECK (currency = 'EUR'),
    balance_cents bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    -- Aucun découvert sur un compte client ; la compensation, elle, porte les dépôts simulés.
    CONSTRAINT solde_non_negatif CHECK (kind = 'CLEARING' OR balance_cents >= 0)
);

CREATE UNIQUE INDEX ledger_accounts_une_compensation ON ledger_accounts (tenant_id) WHERE kind = 'CLEARING';

CREATE TABLE journal_transactions (
    id              uuid         PRIMARY KEY,
    tenant_id       uuid         NOT NULL,
    kind            varchar(10)  NOT NULL CHECK (kind IN ('TRANSFER', 'DEPOSIT', 'REVERSAL')),
    idempotency_key varchar(100) NOT NULL,
    request_hash    varchar(64)  NOT NULL,
    reversal_of     uuid         REFERENCES journal_transactions (id),
    description     varchar(140),
    posted_at       timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key),
    -- Une transaction ne se contrepasse qu'une fois ; une contrepassation ne se contrepasse pas
    -- (ce second point est contrôlé dans la fonction d'équilibre).
    CONSTRAINT une_seule_contrepassation UNIQUE (reversal_of),
    CONSTRAINT contrepassation_coherente CHECK ((kind = 'REVERSAL') = (reversal_of IS NOT NULL))
);

CREATE TABLE journal_entries (
    transaction_id uuid        NOT NULL REFERENCES journal_transactions (id),
    line_no        smallint    NOT NULL CHECK (line_no > 0),
    tenant_id      uuid        NOT NULL,
    account_id     uuid        NOT NULL REFERENCES ledger_accounts (id),
    side           varchar(6)  NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_cents   bigint      NOT NULL CHECK (amount_cents > 0),
    currency       varchar(3)  NOT NULL CHECK (currency = 'EUR'),
    PRIMARY KEY (transaction_id, line_no)
);

CREATE INDEX journal_entries_par_compte ON journal_entries (tenant_id, account_id, transaction_id);

-- Le solde suit les écritures : un crédit augmente le solde d'un compte, un débit le diminue.
-- La fonction s'exécute avec les droits du propriétaire, car le rôle applicatif n'a aucun droit
-- de modification sur les soldes.
CREATE FUNCTION appliquer_ecriture() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
BEGIN
    UPDATE ledger_accounts
       SET balance_cents = balance_cents + CASE NEW.side WHEN 'CREDIT' THEN NEW.amount_cents
                                                         ELSE -NEW.amount_cents END
     WHERE id = NEW.account_id AND tenant_id = NEW.tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'compte du grand livre introuvable pour cette organisation : %', NEW.account_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ecriture_met_a_jour_le_solde
    AFTER INSERT ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION appliquer_ecriture();

-- Vérifie, au COMMIT, qu'une transaction est équilibrée : au moins deux lignes, une seule devise,
-- débits = crédits, et pour une contrepassation exactement l'inverse de l'original. Le contrôle
-- est refait pour chaque ligne insérée : une ligne ajoutée après coup à une transaction validée
-- la déséquilibre et fait échouer la transaction qui l'ajoute.
CREATE FUNCTION verifier_equilibre(p_transaction uuid) RETURNS void
    LANGUAGE plpgsql
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_lignes integer;
    v_devises integer;
    v_debits bigint;
    v_credits bigint;
    v_original uuid;
    v_kind varchar;
BEGIN
    SELECT count(*), count(DISTINCT currency),
           coalesce(sum(amount_cents) FILTER (WHERE side = 'DEBIT'), 0),
           coalesce(sum(amount_cents) FILTER (WHERE side = 'CREDIT'), 0)
      INTO v_lignes, v_devises, v_debits, v_credits
      FROM journal_entries WHERE transaction_id = p_transaction;

    IF v_lignes < 2 THEN
        RAISE EXCEPTION 'transaction % : au moins deux lignes sont nécessaires', p_transaction
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_devises <> 1 THEN
        RAISE EXCEPTION 'transaction % : une seule devise par transaction', p_transaction
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_debits <> v_credits THEN
        RAISE EXCEPTION 'transaction % déséquilibrée : débits % <> crédits %', p_transaction, v_debits, v_credits
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT kind, reversal_of INTO v_kind, v_original FROM journal_transactions WHERE id = p_transaction;
    IF v_kind = 'REVERSAL' THEN
        IF (SELECT kind FROM journal_transactions WHERE id = v_original) = 'REVERSAL' THEN
            RAISE EXCEPTION 'transaction % : une contrepassation ne se contrepasse pas', p_transaction
                USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (
            (SELECT account_id, CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END, amount_cents
               FROM journal_entries WHERE transaction_id = v_original
             EXCEPT
             SELECT account_id, side, amount_cents FROM journal_entries WHERE transaction_id = p_transaction)
            UNION ALL
            (SELECT account_id, side, amount_cents FROM journal_entries WHERE transaction_id = p_transaction
             EXCEPT
             SELECT account_id, CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END, amount_cents
               FROM journal_entries WHERE transaction_id = v_original)
        ) THEN
            RAISE EXCEPTION 'transaction % : la contrepassation n''est pas l''inverse de %', p_transaction, v_original
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
END
$$;

CREATE FUNCTION controle_equilibre_ligne() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = public, pg_temp
AS $$
BEGIN
    PERFORM verifier_equilibre(NEW.transaction_id);
    RETURN NULL;
END
$$;

CREATE FUNCTION controle_equilibre_entete() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = public, pg_temp
AS $$
BEGIN
    PERFORM verifier_equilibre(NEW.id);
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ligne_equilibree
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION controle_equilibre_ligne();

-- Une transaction sans aucune ligne est refusée aussi.
CREATE CONSTRAINT TRIGGER entete_equilibree
    AFTER INSERT ON journal_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION controle_equilibre_entete();

-- Contrôle d'intégrité : les comptes dont le solde ne correspond pas à la somme des écritures.
-- Doit toujours renvoyer zéro ligne ; utilisé par les tests et la vérification de la pile.
CREATE FUNCTION comptes_incoherents() RETURNS TABLE (account_id uuid, balance_cents bigint, ecritures_cents bigint)
    LANGUAGE sql STABLE
AS $$
    SELECT a.id, a.balance_cents,
           coalesce(sum(CASE e.side WHEN 'CREDIT' THEN e.amount_cents ELSE -e.amount_cents END), 0)::bigint
      FROM ledger_accounts a LEFT JOIN journal_entries e ON e.account_id = a.id
     GROUP BY a.id, a.balance_cents
    HAVING a.balance_cents <> coalesce(sum(CASE e.side WHEN 'CREDIT' THEN e.amount_cents
                                                       ELSE -e.amount_cents END), 0)
$$;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['ledger_accounts', 'journal_transactions', 'journal_entries'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) '
                       'WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END
$$;

GRANT USAGE ON SCHEMA public TO ${app_role};
-- Le journal est en ajout seul. Pour les comptes, le solde n'est ni écrit à la création ni modifiable.
GRANT SELECT, INSERT ON journal_transactions, journal_entries TO ${app_role};
GRANT SELECT ON ledger_accounts TO ${app_role};
GRANT INSERT (id, tenant_id, kind, currency) ON ledger_accounts TO ${app_role};
-- SELECT ... FOR UPDATE exige un droit de modification : celui-ci ne porte que sur une colonne
-- sans effet sur les soldes.
GRANT UPDATE (created_at) ON ledger_accounts TO ${app_role};
GRANT EXECUTE ON FUNCTION current_tenant_id(), comptes_incoherents() TO ${app_role};
