import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/** CA23 — Inscription : erreurs (RG11, RG13, CL14). */
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

  test('nom blanc : message sous le champ, aucune requête E3', async ({ page }) => {
    let registrationRequests = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().includes('/registrations')) {
        registrationRequests += 1;
      }
    });
    await page.goto(`/inscription/${raceId}`);
    await page.getByLabel('Nom').fill('   ');
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.locator('#runner-name-error')).not.toBeEmpty();
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
    await page.getByLabel('Nom').fill('Dan');
    const button = page.getByRole('button', { name: "S'inscrire" });
    await Promise.all([button.click(), button.click({ force: true })]);
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
    await expect(page.getByLabel('Nom')).toHaveCount(0);
  });

  test('course inexistante : "Course introuvable"', async ({ page }) => {
    await page.goto('/inscription/999999');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Course introuvable');
  });
});
