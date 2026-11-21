-- Transactional Outbox (ADR-013) : l'événement est écrit dans la transaction métier, puis publié
-- vers Kafka par le relais. Table technique, volontairement sans RLS : le relais lit les
-- événements de toutes les organisations ; le tenant voyage dans l'enveloppe, et aucune API ne
-- l'expose.
CREATE TABLE outbox (
    id           bigserial    PRIMARY KEY,
    event_id     uuid         NOT NULL UNIQUE,
    topic        varchar(100) NOT NULL,
    message_key  varchar(100) NOT NULL,
    payload      text         NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts     integer      NOT NULL DEFAULT 0,
    last_error   text
);

CREATE INDEX outbox_en_attente ON outbox (id) WHERE published_at IS NULL;

GRANT SELECT, INSERT, DELETE ON outbox TO ${app_role};
GRANT UPDATE (published_at, attempts, last_error) ON outbox TO ${app_role};
GRANT USAGE ON SEQUENCE outbox_id_seq TO ${app_role};
