import { expect, test } from '@playwright/test';
import { login, USERS } from './support';

test.describe('Connexion OIDC', () => {
  test('un visiteur anonyme est envoyé vers Keycloak avec PKCE, puis revient connecté', async ({ page }) => {
    const redirections: string[] = [];
    page.on('request', (r) => {
      if (r.url().includes('/protocol/openid-connect/auth')) {
        redirections.push(r.url());
      }
    });

    await login(page, 'alice');

    expect(redirections).toHaveLength(1);
    const url = new URL(redirections[0]);
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('code_challenge')).toBeTruthy();
    expect(url.searchParams.get('client_id')).toBe('finflow-web');

    await expect(page).toHaveURL(/\/accounts$/);
    await expect(page.getByTestId('tenant-name')).toHaveText(USERS.alice.tenant);
  });

  test("le jeton n'est stocké que dans l'onglet (sessionStorage), jamais en localStorage", async ({ page }) => {
    await login(page, 'alice');

    const storage = await page.evaluate(() => ({
      local: Object.keys(localStorage).filter((k) => k.startsWith('oidc.')),
      session: Object.keys(sessionStorage).filter((k) => k.startsWith('oidc.')),
    }));
    expect(storage.local).toEqual([]);
    expect(storage.session.length).toBeGreaterThan(0);
  });

  test('la déconnexion ferme la session : revenir demande à nouveau le mot de passe', async ({ page }) => {
    await login(page, 'alice');

    await page.getByTestId('logout').click();
    await page.goto('/accounts');

    await expect(page.locator('#username')).toBeVisible();
  });

  test('une page profonde est restituée après la connexion', async ({ page }) => {
    await login(page, 'alice', '/payments');

    await expect(page).toHaveURL(/\/payments$/);
    await expect(page.getByRole('heading', { name: 'Paiements' })).toBeVisible();
  });
});
