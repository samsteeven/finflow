import { defineConfig, devices } from '@playwright/test';

/**
 * Tests de bout en bout contre la vraie pile : Keycloak, Gateway, services, Postgres, Kafka.
 * Prérequis (voir le README) : docker compose --profile kafka up -d --wait, puis scripts/lancer-services.sh start.
 * Seul le front est lancé ici.
 */
export default defineConfig({
  testDir: './e2e',
  // Les parcours partagent la même pile : un à la fois, pour des échecs lisibles.
  fullyParallel: false,
  workers: 1,
  retries: process.env['CI'] ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env['CI'] ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: 'http://localhost:4200',
    locale: 'fr-FR',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npx ng serve --port 4200',
    url: 'http://localhost:4200',
    reuseExistingServer: true,
    timeout: 180_000,
  },
});
