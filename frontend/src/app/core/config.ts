/** Adresses de la pile locale (docker-compose et scripts/verifier-pile.sh). */
export const config = {
  /** Gateway : seul point d'entrée des appels métier. */
  apiUrl: 'http://localhost:8080',
  /** Realm Keycloak et client public du front (code d'autorisation + PKCE S256). */
  issuer: 'http://localhost:8180/realms/finflow',
  clientId: 'finflow-web',
} as const;
