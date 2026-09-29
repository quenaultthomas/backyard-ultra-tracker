import { expect, test } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { countRequests } from '../fixtures/ui';

const E3 = /\/api\/public\/races\/\d+\/registrations$/;
const E21 = /\/api\/account\/me$/;
const MESSAGE = 'Trop de tentatives. Réessayez dans une minute.';

/** CA41 (inc. 5) — Affichage d'un 429 du reverse proxy, à corps HTML (RG23, CL20). */
test.describe('@INC-5 @INC5-CA41 Affichage d\'un 429', () => {
  // Le service worker est bloqué : l'interception réseau de Playwright doit voir toutes les requêtes (LIM-E2E-1).
  test.use({ serviceWorkers: 'block' });

  const tooManyAttempts = { status: 429, contentType: 'text/html', body: '<html><body>429 Too Many Requests</body></html>' };

  test('inscription : message dédié et une seule requête E3', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-C41A-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await page.route(E3, (route) => route.fulfill(tooManyAttempts));
    const e3 = countRequests(page, 'POST', E3);

    await page.goto(`/inscription/${race.id}`);
    await page.getByLabel('Pseudo').fill(`Lievre-${run}`);
    await page.getByLabel('Mot de passe', { exact: true }).fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByRole('button', { name: "S'inscrire" }).click();

    await expect(page.getByRole('alert')).toContainText(MESSAGE);
    await expect(page.getByRole('button', { name: "S'inscrire" })).toBeEnabled();
    expect(e3.count()).toBe(1);
    // Aucun compte n'a été créé (la réponse était simulée, la requête n'a jamais atteint le backend).
    expect(await api.adminRunners(race.id)).toHaveLength(0);
  });

  test('connexion coureur : message dédié et une seule requête E21', async ({ page }) => {
    await page.route(E21, (route) => route.fulfill(tooManyAttempts));
    const e21 = countRequests(page, 'GET', E21);

    await page.goto('/compte/connexion');
    await page.getByLabel('Pseudo').fill('quelquun');
    await page.getByLabel('Mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByRole('button', { name: 'Se connecter' }).click();

    await expect(page.getByRole('alert')).toContainText(MESSAGE);
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeEnabled();
    expect(e21.count()).toBe(1);
  });
});
