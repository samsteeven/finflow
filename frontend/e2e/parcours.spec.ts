import { expect, test } from '@playwright/test';
import { choose, deposit, login, openAccount, transfer, uniqueName } from './support';

test.describe('Parcours de bout en bout (Alice, administratrice d\'Alpha Industries)', () => {
  test('ouvrir deux comptes, déposer, virer, consulter le journal puis rembourser', async ({ page }) => {
    await login(page, 'alice');
    const source = uniqueName('Courant');
    const cible = uniqueName('Épargne');
    const sourceId = await openAccount(page, source);
    const cibleId = await openAccount(page, cible);

    // Dépôt simulé : le Ledger crédite le compte, le solde affiché vient du Ledger.
    await deposit(page, sourceId, '500');
    await expect(page.getByTestId('ledger-balance')).toContainText('500,00');

    // Virement : la saga comptabilise et le paiement finit « Terminé ».
    const paymentId = await transfer(page, source, cible, '120,50', 'Loyer');
    await expect(page.getByTestId('payment-status')).toHaveText('Terminé');
    await expect(page.getByTestId('payment-amount-value')).toContainText('120,50');

    // Traçabilité : le journal d'événements du paiement, dans l'ordre, avec la corrélation.
    const timeline = page.getByTestId('timeline').getByRole('listitem');
    await expect(timeline.first()).toContainText('Paiement initié');
    await expect(timeline.last()).toContainText('Comptabilisé au Ledger');
    await expect(page.getByTestId('timeline')).toContainText('corrélation');

    // Les soldes du Ledger reflètent le virement, sans aucun calcul côté front.
    await page.goto(`/accounts/${sourceId}`);
    await expect(page.getByTestId('ledger-balance')).toContainText('379,50');
    await page.goto(`/accounts/${cibleId}`);
    await expect(page.getByTestId('ledger-balance')).toContainText('120,50');
    await expect(page.getByTestId('entries-table')).toContainText('Virement');
    await expect(page.getByTestId('entries-table')).toContainText('Crédit');

    // Le solde projeté par Kafka finit par rejoindre celui du Ledger (cohérence à terme).
    await expect(async () => {
      await page.reload();
      await expect(page.getByTestId('projected-balance')).toContainText('120,50');
    }).toPass({ timeout: 30_000 });

    // Remboursement : contrepassation, les deux soldes reviennent à l'état d'avant.
    await page.goto(`/payments/${paymentId}`);
    await page.getByTestId('refund').click();
    await expect(page.getByTestId('payment-status')).toHaveText('Remboursé');
    await expect(page.getByTestId('timeline')).toContainText('Remboursement comptabilisé');
    await page.goto(`/accounts/${sourceId}`);
    await expect(page.getByTestId('ledger-balance')).toContainText('500,00');
    await expect(page.getByTestId('entries-table')).toContainText('Contrepassation');
  });

  test('un virement au-delà du solde est refusé et le motif est affiché', async ({ page }) => {
    await login(page, 'alice');
    const source = uniqueName('Vide');
    const cible = uniqueName('Cible');
    await openAccount(page, source);
    await openAccount(page, cible);

    await transfer(page, source, cible, '50');

    await expect(page.getByTestId('payment-status')).toHaveText('Refusé');
    await expect(page.getByTestId('payment-rejection')).toBeVisible();
  });

  test('un montant au-delà du plafond par virement est refusé', async ({ page }) => {
    await login(page, 'alice');
    const source = uniqueName('Plafonné');
    const cible = uniqueName('Cible');
    const sourceId = await openAccount(page, source, '100', '1000');
    await openAccount(page, cible);
    await deposit(page, sourceId, '900');

    await transfer(page, source, cible, '150');

    await expect(page.getByTestId('payment-status')).toHaveText('Refusé');
    await expect(page.getByTestId('payment-rejection')).toContainText('LIMITE_PAR_OPERATION');
  });

  test('un compte gelé disparaît des comptes proposés pour un virement, puis revient une fois dégelé', async ({ page }) => {
    await login(page, 'alice');
    const nom = uniqueName('Gelable');
    const id = await openAccount(page, nom);

    await page.getByTestId('freeze').click();
    await expect(page.getByTestId('account-status')).toHaveText('Gelé');
    await page.goto('/payments');
    await page.getByTestId('new-payment').click();
    await page.getByTestId('payment-source').click();
    await expect(page.getByRole('option', { name: nom, exact: true })).toHaveCount(0);
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');

    await page.goto(`/accounts/${id}`);
    await page.getByTestId('unfreeze').click();
    await expect(page.getByTestId('account-status')).toHaveText('Actif');
  });

  test('le formulaire refuse un montant invalide et deux fois le même compte', async ({ page }) => {
    await login(page, 'alice');
    const a = uniqueName('Unique');
    await openAccount(page, a);
    await page.goto('/payments');
    await page.getByTestId('new-payment').click();
    await choose(page, 'payment-source', a);
    await choose(page, 'payment-destination', a);
    await expect(page.getByTestId('same-account')).toBeVisible();
    await page.getByTestId('payment-amount').fill('12,345');
    await page.getByTestId('payment-amount').blur();

    await expect(page.getByTestId('payment-submit')).toBeDisabled();
    await expect(page.getByText('Montant invalide')).toBeVisible();
  });
});
