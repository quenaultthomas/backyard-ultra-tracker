import { expect, test } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';

/**
 * CA23 — Inscription : erreurs (RG11, RG13, CL14).
 * Adaptation INC-5 (RG17, RG7 inc. 5) : « nom » devient « pseudo » (champ, message d'erreur), avec mot de passe
 * et confirmation.
 */
test.describe('@INC-4 @INC4-CA23 Inscription — erreurs', () => {
  let raceId: number;
  const run = uniqueRun();

  test.beforeAll(async ({}, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const race = await api.createRace({
      name: `E2E-INSERR-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    raceId = race.id;
  });

  test('pseudo blanc : message sous le champ, aucune requête E3', async ({ page }) => {
    let registrationRequests = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().includes('/registrations')) {
        registrationRequests += 1;
      }
    });
    await page.goto(`/inscription/${raceId}`);
    await page.getByLabel('Pseudo').fill('   ');
    await page.getByLabel('Mot de passe', { exact: true }).fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.locator('#registration-pseudo-error')).not.toBeEmpty();
    expect(registrationRequests).toBe(0);
  });

  test('double clic rapide sur "S\'inscrire" : une seule requête E3', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const before = await api.adminRunners(raceId);
    let registrationRequests = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().includes('/registrations')) {
        registrationRequests += 1;
      }
    });
    await page.goto(`/inscription/${raceId}`);
    await page.getByLabel('Pseudo').fill(`Dan-${run}`);
    await page.getByLabel('Mot de passe', { exact: true }).fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    // Deux clics quasi simultanés : `dispatchEvent` déclenche l'événement directement, sans attendre
    // l'actionabilité (un `.click()` classique échouerait sur le second appel dès que le premier a désactivé
    // ou fait disparaître le bouton).
    const button = page.getByRole('button', { name: "S'inscrire" });
    await Promise.all([button.dispatchEvent('click'), button.dispatchEvent('click')]);
    await expect(page.locator('.bib')).toBeVisible();
    expect(registrationRequests).toBe(1);
    const after = await api.adminRunners(raceId);
    expect(after.length).toBe(before.length + 1);
  });

  test('inscriptions fermées après démarrage de la course', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    await api.startRace(raceId);
    await page.goto(`/inscription/${raceId}`);
    await expect(page.getByText('Inscriptions fermées')).toBeVisible();
    await expect(page.getByLabel('Pseudo')).toHaveCount(0);
  });

  test('course inexistante : "Course introuvable"', async ({ page }) => {
    await page.goto('/inscription/999999');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Course introuvable');
  });
});
