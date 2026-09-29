import { expect, test, type Page } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { decodeQrImage } from '../fixtures/qr-decode';

/**
 * CA22 — Inscription (RG11, RG12, RG2).
 * Adaptation INC-5 (RG17, RG7, RG18 inc. 5) : le formulaire porte un pseudo, un mot de passe et sa confirmation ;
 * les pseudos sont uniques globalement (suffixe `-{run}`) et affichés en minuscules.
 */
test.describe('@INC-4 @smoke @INC4-CA22 Inscription', () => {
  let raceId: number;
  const run = uniqueRun();

  async function register(page: Page, pseudo: string): Promise<void> {
    await page.goto(`/inscription/${raceId}`);
    await page.getByLabel('Pseudo').fill(pseudo);
    await page.getByLabel('Mot de passe', { exact: true }).fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByRole('button', { name: "S'inscrire" }).click();
  }

  test.beforeAll(async ({}, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const race = await api.createRace({
      name: `E2E-INS-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    raceId = race.id;
  });

  test("la page affiche les paramètres de la course et le formulaire", async ({ page }) => {
    await page.goto(`/inscription/${raceId}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(`E2E-INS-${run}`);
    await expect(page.getByText('1,00 km', { exact: false })).toBeVisible();
    await expect(page.getByText('1:00:00', { exact: false })).toBeVisible();
    await expect(page.getByText('10 m D+', { exact: false })).toBeVisible();
    await expect(page.getByLabel('Pseudo')).toBeVisible();
  });

  test("l'inscription d'Alice puis Bob puis Chloé attribue les dossards 1, 2 et 3", async ({ page }) => {
    await register(page, `Alice-${run}`);
    await expect(page.getByText('Dossard', { exact: false })).toBeVisible();
    await expect(page.locator('.bib')).toContainText('1');
    await expect(page.getByText(`alice-${run}`, { exact: true })).toBeVisible();

    const qrToken = await page.locator('p.token').textContent();
    expect(qrToken).toBeTruthy();
    expect(qrToken!.trim()).toHaveLength(36);
    expect(qrToken!.trim()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);

    await register(page, `Bob-${run}`);
    await expect(page.locator('.bib')).toContainText('2');

    await register(page, `Chloe-${run}`);
    await expect(page.locator('.bib')).toContainText('3');
  });

  test('le QR code affiché décode exactement le qrToken du coureur', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    await register(page, `Decodage-QR-${run}`);
    const shownToken = (await page.locator('p.token').textContent())?.trim();

    const runners = await api.adminRunners(raceId);
    const created = runners.find((runner) => runner.name === `decodage-qr-${run}`);
    expect(created).toBeTruthy();
    expect(shownToken).toBe(created!.qrToken);

    const decoded = await decodeQrImage(page, 'img.qr-image');
    expect(decoded).toBe(created!.qrToken);
  });

  test.afterAll(async ({}, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const runners = await api.adminRunners(raceId);
    expect(runners.length).toBeGreaterThanOrEqual(4);
  });
});
