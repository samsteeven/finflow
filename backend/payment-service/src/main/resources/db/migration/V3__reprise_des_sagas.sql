-- Reprise automatique des sagas (ADR-015). Le worker n'a pas d'organisation : il doit repérer les
-- paiements en suspens de toutes les organisations, ce que la RLS forcée interdit, même à un
-- propriétaire ou à une fonction SECURITY DEFINER. On ne lui donne pas non plus de rôle qui la
-- contourne. À la place, une table technique ne garde que de quoi reprendre : identifiants,
-- statut, nombre de reprises et date, jamais de contenu métier. Elle est tenue à jour par la
-- transaction de l'event store, comme la projection des paiements. Chaque reprise s'exécute
-- ensuite sous l'organisation du paiement, RLS appliquée.
CREATE TABLE saga_watch (
    payment_id uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    status     varchar(20) NOT NULL CHECK (status IN ('PROCESSING', 'FAILED', 'REFUND_PENDING')),
    retries    integer     NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL
);

CREATE INDEX saga_watch_par_date ON saga_watch (updated_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON saga_watch TO ${app_role};
