import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA30 — Backoff sur erreur serveur (RG22, RG24). Service worker désactivé pour une interception réseau
 * fiable (voir LIM-E2E-1, `INC-4-e2e.md`).
 */
test.describe('@INC-4 @INC4-CA30 Backoff sur erreur serveur', () => {
  test.use({ serviceWorkers: 'block' });

  test('deux 503 puis succès : 3 requêtes, backoff croissant, aucun abandon', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-BACKOFF-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const eve = await api.register(race.id, 'Eve Backoff');
    await api.startRace(race.id);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    let attempt = 0;
    const requestTimestamps: number[] = [];
    const bodies: string[] = [];
    await page.route('**/api/scan/passages', async (route) => {
      attempt += 1;
      requestTimestamps.push(Date.now());
      bodies.push(route.request().postData() ?? '');
      if (attempt <= 2) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' });
        return;
      }
      await route.continue();
    });

    await page.getByLabel('Code du QR').fill(eve.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();

    // Pendant les 503 : élément toujours en attente, indicateur « Serveur injoignable ».
    await expect(page.locator('.indicator').filter({ hasText: 'Serveur injoignable' })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('1 en attente')).toBeVisible();

    await expect(page.getByText(`Dossard ${eve.bib} — Eve Backoff`, { exact: false }))
      .toBeVisible({ timeout: 30_000 });

    expect(attempt).toBe(3);
    expect(new Set(bodies).size).toBe(1); // corps identiques (RG23)
    expect(requestTimestamps[1] - requestTimestamps[0]).toBeGreaterThanOrEqual(1_000 - 50);
    expect(requestTimestamps[2] - requestTimestamps[1]).toBeGreaterThanOrEqual(2_000 - 50);

    const detail = await api.runnerDetail(eve.runnerId);
    expect(detail.passages).toHaveLength(1);
  });
});
