import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { ouvrirEcranDeScan, preparerCourseDeScan } from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA13 - la vraie chaîne de capture (caméra factice du navigateur) fournit des images, sans requête de Passage, et s\'arrête', async ({ page, playwright }) => {
  const erreursPage: string[] = [];
  page.on('pageerror', (e) => erreursPage.push(e.message));
  const passages: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/passages')) passages.push(r.url());
  });
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
  await ouvrirEcranDeScan(page, course);
  await expect(page.getByTestId('scan-camera-etat')).toHaveText('Caméra arrêtée');

  await page.getByTestId('scan-camera-activer').click();
  await expect(page.getByTestId('scan-camera-etat')).toHaveText('Caméra active');
  const video = page.getByTestId('scan-camera-video');
  await expect(video).toBeVisible();
  await expect.poll(() => video.evaluate((v: HTMLVideoElement) => v.videoWidth)).toBeGreaterThan(0);
  await expect(page.getByTestId('scan-camera-message')).toHaveCount(0);
  // Fenêtre d'observation : plusieurs analyses de l'image factice (aucun QR) ne doivent rien envoyer.
  await page.waitForTimeout(1500);
  expect(passages).toEqual([]);

  await video.evaluate((v: HTMLVideoElement) => {
    (window as unknown as Record<string, MediaStreamTrack>)['__piste'] = (v.srcObject as MediaStream).getVideoTracks()[0];
  });
  expect(await page.evaluate(() => (window as unknown as Record<string, MediaStreamTrack>)['__piste'].readyState)).toBe('live');

  await page.getByTestId('scan-camera-arreter').click();
  await expect(page.getByTestId('scan-camera-etat')).toHaveText('Caméra arrêtée');
  await expect(video).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => (window as unknown as Record<string, MediaStreamTrack>)['__piste'].readyState)).toBe('ended');
  expect(erreursPage).toEqual([]);
});
