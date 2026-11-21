-- Données de démonstration (profil Spring « demo » uniquement) : les deux organisations dont les
-- utilisateurs alice et bruno du realm Keycloak local portent l'identifiant (attribut tenant_id).
-- La RLS est forcée même pour le propriétaire : chaque insertion déclare son tenant.

SELECT set_config('app.tenant_id', 'a1a1a1a1-0000-4000-8000-000000000001', true);
INSERT INTO tenants (id, name, slug, plan)
VALUES ('a1a1a1a1-0000-4000-8000-000000000001', 'Alpha Industries', 'alpha', 'BUSINESS');

SELECT set_config('app.tenant_id', 'b2b2b2b2-0000-4000-8000-000000000002', true);
INSERT INTO tenants (id, name, slug, plan)
VALUES ('b2b2b2b2-0000-4000-8000-000000000002', 'Beta Logistique', 'beta', 'SANDBOX');

SELECT set_config('app.tenant_id', '', true);
