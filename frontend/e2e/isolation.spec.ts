import { expect, test } from '@playwright/test';
import { deposit, login, openAccount, transfer, uniqueName } from './support';

test.describe('Isolation entre organisations et droits par rôle', () => {
  test("Bruno ne voit rien d'Alpha Industries, même en connaissant les identifiants", async ({ browser }) => {
    // Alice crée un compte, une écriture et un paiement dans son organisation.
    const aliceContext = await browser.newContext();
    const alice = await aliceContext.newPage();
    await login(alice, 'alice');
    const secret = uniqueName('Secret Alpha');
    const autre = uniqueName('Autre Alpha');
    const secretId = await openAccount(alice, secret);
    await openAccount(alice, autre);
    await deposit(alice, secretId, '300');
    const paymentId = await transfer(alice, secret, autre, '10');

    // Bruno, autre organisation, dans une session distincte.
    const brunoContext = await browser.newContext();
    const bruno = await brunoContext.newPage();
    await login(bruno, 'bruno');
    await expect(bruno.getByTestId('tenant-name')).toHaveText('Beta Logistique');

    await expect(bruno.getByRole('heading', { name: 'Comptes' })).toBeVisible();
    await expect(bruno.getByText(secret)).toHaveCount(0);

    await bruno.goto(`/accounts/${secretId}`);
    await expect(bruno.getByTestId('account-error')).toBeVisible();
    await expect(bruno.getByTestId('account-name')).toHaveCount(0);
    await expect(bruno.getByText('300,00')).toHaveCount(0);

    await bruno.goto(`/payments/${paymentId}`);
    await expect(bruno.getByTestId('payment-error')).toBeVisible();
    await expect(bruno.getByTestId('payment-status')).toHaveCount(0);

    await bruno.goto('/payments');
    await expect(bruno.getByRole('heading', { name: 'Paiements' })).toBeVisible();
    await expect(bruno.getByRole('table')).toBeVisible();
    await expect(bruno.locator(`a[href$="${paymentId}"]`)).toHaveCount(0);

    await aliceContext.close();
    await brunoContext.close();
  });

  test('Clara, simple membre, consulte et émet des virements mais ne peut pas administrer', async ({ page }) => {
    await login(page, 'clara');
    await expect(page.getByTestId('tenant-name')).toHaveText('Alpha Industries');

    // Aucune action d'administration n'est proposée.
    await expect(page.getByTestId('open-account')).toHaveCount(0);
    const premier = page.getByRole('row').getByRole('link').first();
    await premier.click();
    await expect(page.getByTestId('account-name')).toBeVisible();
    for (const action of ['deposit', 'limits', 'freeze', 'close']) {
      await expect(page.getByTestId(action)).toHaveCount(0);
    }

    // Les droits sont de toute façon contrôlés côté serveur : un appel direct est refusé.
    const statut = await page.evaluate(async () => {
      const entry = Object.entries(sessionStorage).find(([k]) => k.startsWith('oidc.user'));
      const token = JSON.parse(entry![1]).access_token as string;
      const response = await fetch('http://localhost:8080/api/v1/accounts', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'Intrus', type: 'CURRENT', perTransactionLimitCents: 100, dailyLimitCents: 100 }),
      });
      return response.status;
    });
    expect(statut).toBe(403);
  });
});
