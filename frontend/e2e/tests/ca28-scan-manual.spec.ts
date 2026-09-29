import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA28 — Caméra refusée et saisie manuelle (RG15, RG18, RG16).
 * Le refus de permission est simulé de façon déterministe par un script d'initialisation qui remplace
 * `getUserMedia` par un rejet `NotAllowedError` — un vrai prompt de permission de caméra n'est pilotable de
 * façon fiable ni sous Chromium ni sous WebKit en environnement headless (RG57.6 : « les erreurs ... sont
 * simulées par interception côté navigateur »). C'est le même chemin de code que RG15 exerce en pratique
 * (catch du `DOMException` par `CameraScanner.start()`), sur les deux navigateurs.
 */
test.describe('@INC-4 @smoke @INC4-CA28 Caméra refusée et saisie manuelle', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      const deny = (): Promise<never> => Promise.reject(new DOMException('Denied by test', 'NotAllowedError'));
      Object.defineProperty(window.navigator, 'mediaDevices', {
        configurable: true,
        value: { ...navigator.mediaDevices, getUserMedia: deny },
      });
    });
  });

  test('permission refusée : message de refus, champ visible ; saisie de Bob acceptée ; texte invalide rejeté', async ({
    page,
  }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-SCAN2-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    await api.register(race.id, 'Alice');
    const bob = await api.register(race.id, 'Bob');
    await api.startRace(race.id);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await page.getByRole('button', { name: 'Activer la caméra' }).click();
    await expect(page.getByText('Accès à la caméra refusé', { exact: false })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByLabel('Code du QR')).toBeVisible();

    let scanRequests = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/api/scan/passages')) {
        scanRequests += 1;
      }
    });

    await page.getByLabel('Code du QR').fill(bob.qrToken);
    await page.getByLabel('Code du QR').press('Enter');
    await expect(page.getByText(`Dossard ${bob.bib} — ${bob.name}`, { exact: false })).toBeVisible({ timeout: 5_000 });

    await page.getByLabel('Code du QR').fill('bonjour');
    await page.getByLabel('Code du QR').press('Enter');
    await expect(page.getByText('QR non reconnu', { exact: false })).toBeVisible();
    expect(scanRequests).toBe(1);
  });
});
