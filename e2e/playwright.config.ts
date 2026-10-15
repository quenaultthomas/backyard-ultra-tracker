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
    { name: 'chromium', testIgnore: [/.*\.horloge\.spec\.ts/, /.*\.camera\.spec\.ts/], use: { ...devices['Desktop Chrome'] } },
    {
      name: 'camera',
      testMatch: /.*\.camera\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        permissions: ['camera'],
        launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
      },
    },
    {
      name: 'horloge',
      testMatch: /.*\.horloge\.spec\.ts/,
      dependencies: ['chromium', 'camera'],
      fullyParallel: false,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
