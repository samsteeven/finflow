export interface TokenClaims {
  tenant_id?: string;
  preferred_username?: string;
  name?: string;
  realm_access?: { roles?: string[] };
}

/** Lit les revendications d'un jeton d'accès, sans en vérifier la signature (c'est le rôle des services). */
export function decodeClaims(token: string | undefined | null): TokenClaims {
  if (!token) {
    return {};
  }
  const payload = token.split('.')[1];
  if (!payload) {
    return {};
  }
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as TokenClaims;
  } catch {
    return {};
  }
}
