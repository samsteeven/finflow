// Charge sur les paiements, avec vérification de l'invariant de conservation de l'argent.
// Usage : k6 run load/paiements.js   (variables : TAUX paiements/s, SEUIL_P95 en ms, DUREE, COMPTES paires de comptes)
//
// Seuils (le test échoue s'ils sont franchis) : latence p95 < 2 s sur un poste de développement (SEUIL_P95 pour l'ajuster), moins de 1 % d'erreurs HTTP,
// aucun paiement en échec technique. Après la charge, la somme des soldes doit être exactement
// celle qui a été déposée : aucun centime créé ni perdu, même sous concurrence et rejeux.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { GATEWAY, jeton, entetes, ouvrirCompte, deposer, solde } from './lib.js';

const TAUX = Number(__ENV.TAUX || 20);
const SEUIL_P95 = Number(__ENV.SEUIL_P95 || 2000); // ms ; le budget de production se règle à la baisse
const DUREE = __ENV.DUREE || '45s';
const COMPTES = Number(__ENV.COMPTES || 6);
const DEPOT = 10000000; // 100 000,00 par compte

const rejeusIncoherents = new Counter('rejeux_incoherents');
const echecsTechniques = new Counter('paiements_en_echec_technique');

export const options = {
  scenarios: {
    paiements: {
      executor: 'constant-arrival-rate', rate: TAUX, timeUnit: '1s', duration: DUREE,
      preAllocatedVUs: 20, maxVUs: 100,
    },
  },
  thresholds: {
    'http_req_duration{name:paiement}': [`p(95)<${SEUIL_P95}`],
    http_req_failed: ['rate<0.01'],
    rejeux_incoherents: ['count==0'],
    paiements_en_echec_technique: ['count==0'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const token = jeton('alice');
  const comptes = [];
  for (let i = 0; i < COMPTES * 2; i++) {
    const id = ouvrirCompte(token, `charge-${Date.now()}-${i}`);
    deposer(token, id, DEPOT, `charge-depot-${Date.now()}-${i}`);
    comptes.push(id);
  }
  return { comptes, depose: DEPOT * comptes.length };
}

export default function (data) {
  // Le jeton est redemandé rarement : il vit plusieurs minutes.
  const token = jetonCourant();
  const n = data.comptes.length;
  const i = (__VU * 7919 + __ITER) % n;
  const source = data.comptes[i];
  const destination = data.comptes[(i + 1 + (__ITER % (n - 1))) % n];
  const cle = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const corps = JSON.stringify({ sourceAccountId: source, destinationAccountId: destination,
                                 amountCents: 100 + (__ITER % 900), currency: 'EUR' });
  const p = entetes(token, cle);
  p.tags = { name: 'paiement' };

  const r = http.post(`${GATEWAY}/api/v1/payments`, corps, p);
  const ok = check(r, { 'paiement accepté': (x) => x.status === 202 || x.status === 200 });
  if (!ok) return;
  if (r.json('status') === 'FAILED') echecsTechniques.add(1);

  // Un paiement sur dix est rejoué avec la même clé : il doit renvoyer le même paiement, sans second débit.
  if (__ITER % 10 === 0) {
    const rejeu = http.post(`${GATEWAY}/api/v1/payments`, corps, p);
    if (rejeu.json('id') !== r.json('id')) rejeusIncoherents.add(1);
  }
}

let cache = { token: null, jusqu: 0 };
function jetonCourant() {
  const maintenant = Date.now();
  if (!cache.token || maintenant > cache.jusqu) {
    cache = { token: jeton('alice'), jusqu: maintenant + 120000 };
  }
  return cache.token;
}

export function teardown(data) {
  sleep(2); // laisser les dernières écritures se terminer
  const token = jeton('alice');
  let total = 0;
  for (const c of data.comptes) total += solde(token, c);
  check(null, { 'conservation : la somme des soldes est celle qui a été déposée': () => total === data.depose });
  console.log(`soldes : ${total} attendu ${data.depose}`);
}
