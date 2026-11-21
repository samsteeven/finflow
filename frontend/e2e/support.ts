import { expect, Page } from '@playwright/test';

/** Comptes du realm de développement (infrastructure/keycloak/generer-realm.py). */
export const USERS = {
  alice: { name: 'alice', display: 'Alice Alpha', tenant: 'Alpha Industries', admin: true },
  bruno: { name: 'bruno', display: 'Bruno Beta', tenant: 'Beta Logistique', admin: true },
  clara: { name: 'clara', display: 'Clara Alpha', tenant: 'Alpha Industries', admin: false },
} as const;

/** Connexion complète par Keycloak (code d'autorisation + PKCE), comme un utilisateur. */
export async function login(page: Page, user: keyof typeof USERS, target = '/accounts'): Promise<void> {
  await page.goto(target);
  await page.locator('#username').fill(USERS[user].name);
  await page.locator('#password').fill('finflow-dev');
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('user-name')).toHaveText(USERS[user].display);
}

export function uniqueName(prefix: string): string {
  return `${prefix} ${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
}

/** Ouvre un compte par l'écran et revient sur la liste. Renvoie son identifiant. */
export async function openAccount(page: Page, name: string, perTransaction = '2000', daily = '10000'): Promise<string> {
  await page.goto('/accounts');
  await page.getByTestId('open-account').click();
  await page.getByLabel('Nom du compte').fill(name);
  await page.getByLabel('Plafond par virement (€)').fill(perTransaction);
  await page.getByLabel('Plafond journalier (€)').fill(daily);
  await page.getByRole('button', { name: 'Ouvrir', exact: true }).click();
  const row = page.getByRole('row').filter({ hasText: name });
  await expect(row).toBeVisible();
  await row.getByRole('link', { name }).click();
  await expect(page.getByTestId('account-name')).toHaveText(name);
  return page.url().split('/').pop()!;
}

export async function deposit(page: Page, accountId: string, euros: string): Promise<void> {
  await page.goto(`/accounts/${accountId}`);
  await page.getByTestId('deposit').click();
  await page.getByTestId('deposit-amount').fill(euros);
  await page.getByTestId('deposit-submit').click();
  await expect(page.getByTestId('deposit-amount')).toBeHidden();
}

/** Émet un virement par l'écran ; renvoie l'identifiant du paiement affiché. */
export async function transfer(page: Page, from: string, to: string, euros: string, label = 'Test'): Promise<string> {
  await page.goto('/payments');
  await page.getByTestId('new-payment').click();
  await choose(page, 'payment-source', from);
  await choose(page, 'payment-destination', to);
  await page.getByTestId('payment-amount').fill(euros);
  await page.getByTestId('payment-description').fill(label);
  await page.getByTestId('payment-submit').click();
  await expect(page).toHaveURL(/\/payments\/[0-9a-f-]{36}$/);
  return page.url().split('/').pop()!;
}

/**
 * Choisit une option d'une liste Material. La liste des comptes se charge à l'ouverture du dialogue :
 * si elle se rafraîchit pendant le clic, on rouvre la liste plutôt que d'échouer.
 */
export async function choose(page: Page, testId: string, option: string): Promise<void> {
  await expect(async () => {
    if (!(await page.getByRole('listbox').isVisible())) {
      await page.getByTestId(testId).click();
    }
    await page.getByRole('option', { name: option, exact: true }).click({ timeout: 2_000 });
  }).toPass({ timeout: 20_000 });
  // Attendre la fermeture : la liste suivante ne doit pas se confondre avec celle-ci.
  await expect(page.getByRole('listbox')).toBeHidden();
}
