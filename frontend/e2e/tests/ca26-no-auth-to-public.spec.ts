import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/** CA26 — Pas d'Authorization vers le public (RG8). */
test.describe('@INC-4 @INC4-CA26 Authorization jamais envoyé au public', () => {
  test('connecté SCANNER, aucune requête /api/public/** ne porte Authorization ni ne reçoit 401', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-NOAUTH-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const registration = await api.register(race.id, 'Public Runner');

    const publicRequestsWithAuth: string[] = [];
    const publicUnauthorized: string[] = [];
    page.on('request', (request) => {
      if (request.url().includes('/api/public/')) {
        if (request.headers()['authorization'] !== undefined) {
          publicRequestsWithAuth.push(request.url());
        }
      }
    });
    page.on('response', (response) => {
      if (response.url().includes('/api/public/') && response.status() === 401) {
        publicUnauthorized.push(response.url());
      }
    });

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByLabel('Rester connecté 24 h', { exact: false }).check();
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');

    await page.goto(`/courses/${race.id}`);
    await page.waitForTimeout(10_000); // laisse le polling du tableau de bord faire plusieurs appels E4

    await page.goto(`/coureurs/${registration.runnerId}`);
    await page.waitForTimeout(500);

    await page.goto(`/inscription/${race.id}`);
    await page.waitForTimeout(500);

    expect(publicRequestsWithAuth).toEqual([]);
    expect(publicUnauthorized).toEqual([]);
  });
});
