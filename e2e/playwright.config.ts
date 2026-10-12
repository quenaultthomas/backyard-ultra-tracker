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
    { name: 'chromium', testIgnore: /.*\.horloge\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    {
      name: 'horloge',
      testMatch: /.*\.horloge\.spec\.ts/,
      dependencies: ['chromium'],
      fullyParallel: false,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
