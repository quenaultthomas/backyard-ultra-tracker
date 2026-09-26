import { defineConfig, devices } from '@playwright/test';

/**
 * Suite E2E de l'incrément 4 (PO3, RG57, RG58) : parcours utilisateur dans un vrai navigateur, contre un
 * backend réel (jar Spring Boot construit par `mvn verify`, profil `test`, H2 en mode PostgreSQL, PO24).
 * Hors de `mvn verify` (RG59). Chromium et WebKit obligatoires (RG58) ; seule CA27 (volet caméra) est admise
 * en écart sous WebKit (pas de flux caméra simulé disponible), voir le rapport `INC-4-e2e.md`.
 *
 * Démarrage du backend (à faire avant `npm test`, séparément, voir docs/tests/rapports/INC-4-e2e.md) :
 *   SPRING_DATASOURCE_URL="jdbc:h2:mem:e2edb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE" \
 *   SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver SPRING_DATASOURCE_USERNAME=sa SPRING_DATASOURCE_PASSWORD= \
 *   BACKYARD_YARD_CLOSING_FIXED_DELAY_MS=500 \
 *   BACKYARD_SECURITY_ADMIN_USERNAME=admin-test BACKYARD_SECURITY_ADMIN_PASSWORD_HASH=... \
 *   BACKYARD_SECURITY_SCANNER_USERNAME=scanner-test BACKYARD_SECURITY_SCANNER_PASSWORD_HASH=... \
 *   mvn -B -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=test -Dspring-boot.run.useTestClasspath=true
 *
 * Suite E2E (dans frontend/e2e, après `npm install` et `npx playwright install chromium webkit`) :
 *   npm test               (suite complète, deux projets : chromium, webkit)
 *   npm run test:smoke     (parcours critiques @smoke, < 5 min)
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['json', { outputFile: 'test-results/results.json' }],
  ],
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
  ],
});
