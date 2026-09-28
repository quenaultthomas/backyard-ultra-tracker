import { chromium, expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';
import { generateQrCodeVideo } from '../fixtures/qr-video';

/**
 * CA27 — Scan nominal à la caméra (RG15, RG16, RG19, RG24, RG50).
 * Chromium : flux vidéo simulé (`--use-fake-device-for-media-stream`, `--use-file-for-fake-video-capture`)
 * diffusant le QR d'Alice (RG57.7, RG58). WebKit : l'outil ne fournit pas de flux caméra simulé (RG58) ; le
 * même parcours est exécuté par la saisie manuelle du token d'Alice (RG18), avec les mêmes attendus hors
 * anti-rebond de la caméra.
 */
test.describe('@INC-4 @INC4-CA27 Scan nominal', () => {
  test('scan nominal du QR d\'Alice, une seule capture malgré la lecture continue', async ({
    page, browserName,
  }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-SCAN-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const alice = await api.register(race.id, 'Alice');
    await api.register(race.id, 'Bob');
    await api.register(race.id, 'Eve');
    await api.startRace(race.id);

    // Écart mineur comblé (reprise du 2026-09-27) : corps exact de la requête E6, pour comparer son
    // `scannedAt` à celui enregistré côté serveur (égalité, pas seulement une fenêtre temporelle).
    let capturedScanBody: string | null = null;
    const captureScanBody = (request: import('@playwright/test').Request): void => {
      if (request.method() === 'POST' && request.url().endsWith('/api/scan/passages')) {
        capturedScanBody = request.postData();
      }
    };

    let beforeActivation: number;

    if (browserName === 'chromium') {
      // Flux caméra simulé : le chemin du fichier Y4M est fixé au lancement du navigateur (option Chromium),
      // donc propre à cette instance (RG57.7). Un navigateur dédié est lancé pour ce seul test.
      const videoPath = generateQrCodeVideo(alice.qrToken);
      const cameraBrowser = await chromium.launch({
        args: [
          '--use-fake-device-for-media-stream',
          '--use-fake-ui-for-media-stream',
          `--use-file-for-fake-video-capture=${videoPath}`,
        ],
      });
      const cameraContext = await cameraBrowser.newContext({
        baseURL: testInfo.project.use.baseURL as string,
        permissions: ['camera'],
      });
      const cameraPage = await cameraContext.newPage();
      cameraPage.on('request', captureScanBody);
      try {
        await cameraPage.goto('/scan');
        await cameraPage.getByRole('link', { name: 'Se connecter' }).click();
        await cameraPage.getByLabel("Nom d'utilisateur").fill('scanner-test');
        await cameraPage.getByLabel('Mot de passe').fill('scanner-secret');
        await cameraPage.getByRole('button', { name: 'Se connecter' }).click();
        await expect(cameraPage.getByRole('heading', { level: 1 })).toHaveText('Scan');

        beforeActivation = Date.now();
        await cameraPage.getByRole('button', { name: 'Activer la caméra' }).click();
        await expect(cameraPage.getByText(`Dossard ${alice.bib} — Alice — yard 1`, { exact: false }))
          .toBeVisible({ timeout: 5_000 });
        const afterDisplay = Date.now();
        expect(afterDisplay - beforeActivation).toBeLessThan(5_000);

        // Une seule capture malgré la lecture continue (anti-rebond, RG17) : un seul élément dans l'historique.
        await expect(cameraPage.locator('.history li')).toHaveCount(1);
      } finally {
        await cameraContext.close();
        await cameraBrowser.close();
      }
    } else {
      // WebKit (RG58) : pas de flux caméra simulé disponible, saisie manuelle du même token.
      page.on('request', captureScanBody);
      await page.goto('/scan');
      await page.getByRole('link', { name: 'Se connecter' }).click();
      await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
      await page.getByLabel('Mot de passe').fill('scanner-secret');
      await page.getByRole('button', { name: 'Se connecter' }).click();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

      beforeActivation = Date.now();
      await page.getByLabel('Code du QR').fill(alice.qrToken);
      await page.getByRole('button', { name: 'Valider' }).click();
      await expect(page.getByText(`Dossard ${alice.bib} — Alice — yard 1`, { exact: false })).toBeVisible({ timeout: 5_000 });
      const afterDisplay = Date.now();
      expect(afterDisplay - beforeActivation).toBeLessThan(5_000);
    }

    // Côté serveur (E5) : un passage yard 1, SCAN, dont scannedAt est compris entre l'activation et l'affichage,
    // et égal (à la milliseconde) à celui de la requête E6 émise (écart mineur comblé, reprise du 2026-09-27).
    const detail = await api.runnerDetail(alice.runnerId);
    expect(detail.passages).toHaveLength(1);
    expect(detail.passages[0].yardNumber).toBe(1);
    expect(detail.passages[0].source).toBe('SCAN');
    const scannedAtMs = Date.parse(detail.passages[0].scannedAt!);
    expect(scannedAtMs).toBeGreaterThanOrEqual(beforeActivation - 1_000);
    expect(scannedAtMs).toBeLessThanOrEqual(Date.now() + 1_000);

    expect(capturedScanBody).not.toBeNull();
    const emittedBody = JSON.parse(capturedScanBody as unknown as string) as { qrToken: string; scannedAt: string };
    expect(emittedBody.qrToken).toBe(alice.qrToken);
    expect(Date.parse(emittedBody.scannedAt)).toBe(scannedAtMs);
  });
});
