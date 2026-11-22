// Test de fumée : la pile répond, un paiement passe de bout en bout et se rejoue sans second débit.
// Usage : k6 run load/fumee.js
import http from 'k6/http';
import { check } from 'k6';
import { GATEWAY, jeton, entetes, ouvrirCompte, deposer, solde } from './lib.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1.0'], http_req_failed: ['rate<0.01'] },
};

export default function () {
  const token = jeton('alice');
  const a = ouvrirCompte(token, `fumee-a-${Date.now()}`);
  const b = ouvrirCompte(token, `fumee-b-${Date.now()}`);
  deposer(token, a, 50000, `fumee-depot-${Date.now()}`);

  const cle = `fumee-${Date.now()}`;
  const corps = JSON.stringify({ sourceAccountId: a, destinationAccountId: b, amountCents: 1200, currency: 'EUR' });
  const premier = http.post(`${GATEWAY}/api/v1/payments`, corps, entetes(token, cle));
  const rejeu = http.post(`${GATEWAY}/api/v1/payments`, corps, entetes(token, cle));

  check(premier, { 'paiement accepté': (r) => r.status === 202 || r.status === 200,
                   'paiement terminé': (r) => r.json('status') === 'COMPLETED' });
  check(rejeu, { 'rejeu : même paiement': (r) => r.json('id') === premier.json('id') });
  check(null, {
    'débit unique': () => solde(token, a) === 50000 - 1200,
    'crédit unique': () => solde(token, b) === 1200,
  });
}
