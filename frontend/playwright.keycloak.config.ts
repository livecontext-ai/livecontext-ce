import { defineConfig, devices } from '@playwright/test';

/**
 * Keycloak realm e2e: runs the realm's real login pages (the livecontext theme) against a
 * Keycloak configured by deploy/scripts/configure-keycloak.sh. Separate from the app suite
 * (playwright.config.ts): no Next.js server, no app auth setup, only a Keycloak and the
 * mailpit it sends mail to. How to start both: e2e/keycloak-realm/README.md.
 */
export default defineConfig({
  testDir: './e2e/keycloak-realm',
  testMatch: /.*\.kc\.ts$/,
  fullyParallel: false,
  workers: 1,
  timeout: 180_000,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.KC_E2E_URL ?? 'http://localhost:18180',
    trace: 'retain-on-failure',
  },
});
