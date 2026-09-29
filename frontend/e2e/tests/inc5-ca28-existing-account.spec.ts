import { expect, test } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { countRequests, expectAccountPage, loginRunner } from '../fixtures/ui';

const E3 = /\/api\/public\/races\/\d+\/registrations$/;
const E20 = /\/api\/account\/races\/\d+\/registrations$/;

/** CA28 (inc. 5) — Parcours d'inscription avec un compte existant (RG8, RG17, CL1). */
test.describe('@INC-5 @smoke @INC5-CA28 Inscription avec un compte existant', () => {
  test.use({ serviceWorkers: 'block' });

  test('« J\'ai déjà un compte », connexion, retour, une seule requête E20 et aucune E3', async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const raceA = await api.createRace({ name: `E2E-C28A-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const raceB = await api.createRace({ name: `E2E-C28B-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(raceA.id, typed, DEFAULT_RUNNER_PASSWORD);
    const e3 = countRequests(page, 'POST', E3);
    const e20 = countRequests(page, 'POST', E20);

    await page.goto(`/inscription/${raceB.id}`);
    await page.getByRole('link', { name: "J'ai déjà un compte" }).click();
    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD, { open: false });

    // Retour sur l'inscription à la course B, connecté.
    await expect(page.getByRole('heading', { level: 1 })).toContainText(`Inscription — E2E-C28B-${run}`);
    await expect(page.getByText(`Vous êtes connecté en tant que ${pseudo}`)).toBeVisible();
    expect(new URL(page.url()).pathname).toBe(`/inscription/${raceB.id}`);

    const [response] = await Promise.all([
      page.waitForResponse((r) => E20.test(r.url()) && r.request().method() === 'POST'),
      page.getByRole('button', { name: "M'inscrire à cette course" }).click(),
    ]);
    expect(response.status()).toBe(201);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Inscription confirmée');
    expect(e20.count()).toBe(1);
    expect(e3.count()).toBe(0);

    // « Mes inscriptions » liste les deux courses (navigation interne : la connexion n'est qu'en mémoire).
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Mes inscriptions' }).click();
    await expectAccountPage(page, pseudo);
    await expect(page.getByRole('heading', { level: 2, name: `E2E-C28A-${run}` })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: `E2E-C28B-${run}` })).toBeVisible();
  });
});
