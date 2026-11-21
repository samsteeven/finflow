import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { expect, test } from '@playwright/test';
import { deposit, login, openAccount, transfer, uniqueName } from './support';

/**
 * Démonstration de panne : le Ledger s'arrête, le paiement échoue proprement, puis la saga est reprise
 * seule (ADR-015) quand le Ledger revient, sans double écriture.
 * Ce test arrête un vrai service : il n'est lancé que sur demande (E2E_PANNES=1) et avec les services
 * démarrés par scripts/lancer-services.sh.
 */
const script = path.resolve(__dirname, '../../scripts/lancer-services.sh');
const bash = process.env['BASH'] ?? 'bash';
const service = (action: 'start' | 'stop', nom: string) =>
  execFileSync(bash, [script, action, nom], { env: process.env, stdio: 'pipe' });

test.describe('Pannes', () => {
  test.skip(!process.env['E2E_PANNES'], 'Arrête un service réel : à lancer avec E2E_PANNES=1');
  test.setTimeout(240_000);

  test.afterAll(() => {
    service('start', 'ledger'); // la pile est toujours remise d'aplomb
  });

  test('le Ledger tombe : le paiement échoue, puis se termine seul à son retour', async ({ page }) => {
    await login(page, 'alice');
    const source = uniqueName('Panne source');
    const cible = uniqueName('Panne cible');
    const sourceId = await openAccount(page, source);
    const cibleId = await openAccount(page, cible);
    await deposit(page, sourceId, '400');

    service('stop', 'ledger');
    await transfer(page, source, cible, '75');
    await expect(page.getByTestId('payment-status')).toHaveText('Échec technique', { timeout: 30_000 });
    const paymentUrl = page.url();

    service('start', 'ledger');
    await expect(async () => {
      await page.goto(paymentUrl);
      await expect(page.getByTestId('payment-status')).toHaveText('Terminé');
    }).toPass({ timeout: 90_000, intervals: [3_000] });

    // L'historique montre l'échec puis la reprise, et un seul mouvement comptable.
    await expect(page.getByTestId('timeline')).toContainText('Échec technique');
    await expect(page.getByTestId('timeline')).toContainText('Reprise de la saga');
    await page.goto(`/accounts/${cibleId}`);
    await expect(page.getByTestId('ledger-balance')).toContainText('75,00');
    await page.goto(`/accounts/${sourceId}`);
    await expect(page.getByTestId('ledger-balance')).toContainText('325,00');
  });
});
