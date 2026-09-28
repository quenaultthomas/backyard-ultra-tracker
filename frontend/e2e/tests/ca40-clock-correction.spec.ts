import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA40 — Correction d'horloge (RG4, RG19, CL9). Horloge du navigateur seul avancée de 60 s (horloge simulée de
 * l'outil E2E, `page.clock`) ; l'horloge serveur reste réelle.
 */
test.describe('@INC-4 @INC4-CA40 Correction d\'horloge', () => {
  test('avertissement de décalage, scan accepté sans rejet "dans le futur"', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-CLOCK-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const runner = await api.register(race.id, 'Runner Clock');
    await api.startRace(race.id);

    // Horloge de l'appareil avancée de 60 s, uniquement pour ce qui dépend du front (RG57.5) ; les échanges
    // réseau (fetch) restent réels, `setSystemTime` ne décale que ce que l'appareil rapporte comme heure.
    await page.clock.install({ time: Date.now() });
    await page.clock.setSystemTime(Date.now() + 60_000);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    const beforeLogin = Date.now();
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await expect(page.locator('.indicator').filter({ hasText: 'Horloge de l\'appareil décalée' }))
      .toContainText(/60 s|59 s|61 s|58 s|62 s/, { timeout: 10_000 });

    await page.getByLabel('Code du QR').fill(runner.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText(`Dossard ${runner.bib} — Runner Clock`, { exact: false }))
      .toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('QR non reconnu')).toHaveCount(0);
    await expect(page.locator('.rejections li')).toHaveCount(0);

    const detail = await api.runnerDetail(runner.runnerId);
    expect(detail.passages).toHaveLength(1);
    const scannedAtMs = Date.parse(detail.passages[0].scannedAt!);
    expect(Math.abs(scannedAtMs - beforeLogin)).toBeLessThan(3_000);
  });
});
