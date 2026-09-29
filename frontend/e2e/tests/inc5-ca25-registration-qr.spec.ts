import { expect, test } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { decodeQrImage } from '../fixtures/qr-decode';
import { countRequests, expectAccountPage, loginRunner } from '../fixtures/ui';

const E3 = /\/api\/public\/races\/\d+\/registrations$/;

/**
 * CA25 (inc. 5) — Parcours d'inscription et de réaffichage du QR (RG17, RG7, RG11). Le pseudo est saisi avec une
 * majuscule (`Lievre-{run}`) et affiché en minuscules (RG2, révision 5).
 */
test.describe('@INC-5 @smoke @INC5-CA25 Inscription avec compte et réaffichage du QR', () => {
  test.use({ serviceWorkers: 'block' });

  test('mots de passe différents, création, connexion, QR décodé, pseudo déjà pris', async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const raceA = await api.createRace({ name: `E2E-C25A-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const raceB = await api.createRace({ name: `E2E-C25B-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const e3 = countRequests(page, 'POST', E3);

    await page.goto(`/inscription/${raceA.id}`);
    await expect(page.getByText("N'utilisez pas votre nom réel", { exact: false })).toBeVisible();

    // Mots de passe différents : message sous le champ, aucune requête E3.
    await page.getByLabel('Pseudo').fill(typed);
    await page.getByLabel('Mot de passe', { exact: true }).fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill('motdepasse-2');
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.locator('#registration-confirmation-error')).not.toBeEmpty();
    expect(e3.count()).toBe(0);

    // Mots de passe identiques : confirmation avec dossard, pseudo en minuscules et QR.
    await page.getByLabel('Confirmer le mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Inscription confirmée');
    await expect(page.locator('.bib')).toContainText('1');
    await expect(page.getByText(pseudo, { exact: true })).toBeVisible();
    const confirmedToken = (await page.locator('p.token').textContent())?.trim();
    expect(confirmedToken).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(e3.count()).toBe(1);
    const created = (await api.adminRunners(raceA.id)).find((runner) => runner.pseudo === pseudo);
    expect(created?.qrToken).toBe(confirmedToken);
    expect(created?.name).toBe(pseudo);

    // Connexion coureur puis « Mes inscriptions » : le QR décodé égale le qrToken de la confirmation.
    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD);
    await expectAccountPage(page, pseudo);
    await expect(page.locator('p.token')).toHaveText(confirmedToken!);
    await expect.poll(() => decodeQrImage(page, 'img.qr-image')).toBe(confirmedToken);

    // Seconde création du même pseudo sur une autre course : detail 409 et lien « J'ai déjà un compte ».
    await page.goto(`/inscription/${raceB.id}`);
    await page.getByLabel('Pseudo').fill(typed);
    await page.getByLabel('Mot de passe', { exact: true }).fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    const [conflict] = await Promise.all([
      page.waitForResponse((response) => E3.test(response.url()) && response.request().method() === 'POST'),
      page.getByRole('button', { name: "S'inscrire" }).click(),
    ]);
    expect(conflict.status()).toBe(409);
    const detail = ((await conflict.json()) as { detail: string }).detail;
    expect(detail).toBeTruthy();
    await expect(page.getByRole('alert')).toContainText(detail);
    // Observation OBS-E2E-1 (rapport INC-5-e2e) : après le 409, le lien apparaît deux fois (celui du formulaire
    // et celui du bloc d'erreur) ; on vérifie sa présence, sans figer ce doublon.
    await expect(page.getByRole('link', { name: "J'ai déjà un compte" }).first()).toBeVisible();
  });
});
