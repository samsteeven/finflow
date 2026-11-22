/**
 * Adresses de l'environnement AWS (ADR-018). Front, API (/api) et Keycloak (/auth) sont servis par le même domaine
 * CloudFront : le front n'a donc aucune adresse écrite en dur, et le même build fonctionne quel que soit le domaine.
 */
export const config = {
  apiUrl: location.origin,
  issuer: `${location.origin}/auth/realms/finflow`,
  clientId: 'finflow-web',
} as const;
