-- Contexte de trace W3C de la requête qui a produit l'événement : le relais le recopie dans l'en-tête
-- Kafka, et la trace se poursuit chez le consommateur (ADR-017).
ALTER TABLE outbox ADD COLUMN trace_context text;
