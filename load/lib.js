// Fonctions communes aux scénarios k6 : jeton Keycloak, ouverture de comptes, dépôt, lecture de solde.
import http from 'k6/http';
import { check, fail } from 'k6';

export const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
export const KEYCLOAK = __ENV.KEYCLOAK_URL || 'http://localhost:8180';

export function jeton(utilisateur) {
  const r = http.post(`${KEYCLOAK}/realms/finflow/protocol/openid-connect/token`, {
    grant_type: 'password', client_id: 'finflow-dev-cli', username: utilisateur, password: 'finflow-dev',
  });
  if (r.status !== 200) fail(`jeton ${utilisateur} : ${r.status} ${r.body}`);
  return r.json('access_token');
}

export function entetes(token, cle) {
  const h = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  if (cle) h['Idempotency-Key'] = cle;
  return { headers: h };
}

export function ouvrirCompte(token, nom) {
  const r = http.post(`${GATEWAY}/api/v1/accounts`, JSON.stringify({
    name: nom, type: 'CURRENT', perTransactionLimitCents: 1000000, dailyLimitCents: 100000000000,
  }), entetes(token));
  if (!check(r, { 'compte ouvert': (x) => x.status === 201 })) fail(`ouverture ${nom} : ${r.status} ${r.body}`);
  return r.json('id');
}

export function deposer(token, compte, montant, cle) {
  const r = http.post(`${GATEWAY}/api/v1/ledger/deposits`, JSON.stringify({ accountId: compte, amountCents: montant }),
    entetes(token, cle));
  if (r.status >= 300) fail(`dépôt ${compte} : ${r.status} ${r.body}`);
}

export function solde(token, compte) {
  const r = http.get(`${GATEWAY}/api/v1/ledger/accounts/${compte}/balance`, entetes(token));
  if (r.status !== 200) fail(`solde ${compte} : ${r.status} ${r.body}`);
  return r.json('balanceCents');
}
