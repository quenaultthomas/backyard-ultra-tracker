import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.BASE_URL ?? 'http://localhost';

export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.ts',
  fullyParallel: true,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [
    // Les tests qui avancent l'horloge (globale) sont isolés dans le projet `horloge` (RG11).
    // Ceux qui exigent la caméra factice du navigateur (4.5 CA13) sont isolés dans le projet `camera`.
    // Service worker bloqué (5.2 RG13) : il perturberait `page.route` et `setOffline` dans tous les tests hors `pwa`.
    {
      name: 'chromium',
      testIgnore: [/.*\.horloge\.spec\.ts/, /.*\.camera\.spec\.ts/, /.*\.pwa\.spec\.ts/],
      use: { ...devices['Desktop Chrome'], serviceWorkers: 'block' },
    },
    {
      name: 'camera',
      testMatch: /.*\.camera\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        serviceWorkers: 'block',
        permissions: ['camera'],
        launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
      },
    },
    // Application installable (5.2) : service worker actif, un contexte neuf par test, un seul worker.
    {
      name: 'pwa',
      testMatch: /.*\.pwa\.spec\.ts/,
      dependencies: ['chromium', 'camera'],
      fullyParallel: false,
      use: { ...devices['Desktop Chrome'], serviceWorkers: 'allow' },
    },
    {
      name: 'horloge',
      testMatch: /.*\.horloge\.spec\.ts/,
      dependencies: ['chromium', 'camera', 'pwa'],
      fullyParallel: false,
      use: { ...devices['Desktop Chrome'], serviceWorkers: 'block' },
    },
  ],
});
