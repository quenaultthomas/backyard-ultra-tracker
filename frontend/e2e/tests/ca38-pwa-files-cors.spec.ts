import { createServer, Server } from 'node:http';
import { expect, test } from '@playwright/test';

const FOREIGN_ORIGIN_PORT = 5999;

/** CA38 — Fichiers de la PWA et absence de CORS depuis le navigateur (RG53, RG54, RG44). */
test.describe('@INC-4 @smoke @INC4-CA38 Fichiers de la PWA et absence de CORS', () => {
  test('fichiers PWA accessibles sans connexion, service worker actif, CORS refusé depuis une autre origine', async ({
    page, context,
  }, testInfo) => {
    const baseUrl = testInfo.project.use.baseURL as string;

    const manifestResponse = await page.request.get(`${baseUrl}/manifest.webmanifest`);
    expect(manifestResponse.status()).toBe(200);
    const workerResponse = await page.request.get(`${baseUrl}/ngsw-worker.js`);
    expect(workerResponse.status()).toBe(200);
    const rootResponse = await page.request.get(`${baseUrl}/`);
    expect(rootResponse.status()).toBe(200);

    await page.goto('/');
    await page.waitForFunction(() => navigator.serviceWorker.getRegistrations().then((regs) => regs.length > 0),
      null, { timeout: 15_000 });
    await page.reload();
    // Sous WebKit, la prise de contrôle par le service worker après rechargement peut prendre un peu plus de
    // temps que sous Chromium : on l'attend explicitement plutôt que de lire l'état une seule fois.
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, { timeout: 15_000 });
    const controlled = await page.evaluate(() => navigator.serviceWorker.controller !== null);
    expect(controlled).toBe(true);

    // Page statique servie par une origine étrangère (RG54).
    const server = await startStaticServer();
    try {
      const foreignPage = await context.newPage();
      await foreignPage.goto(`http://localhost:${FOREIGN_ORIGIN_PORT}/`);
      const foreignResult = await foreignPage.evaluate(async (apiBaseUrl: string) => {
        try {
          const response = await fetch(`${apiBaseUrl}/api/public/races`);
          return { ok: true, hasCors: response.headers.get('access-control-allow-origin') !== null };
        } catch (error: unknown) {
          return { ok: false, message: String(error) };
        }
      }, baseUrl);
      expect(foreignResult.ok).toBe(false);
      await foreignPage.close();
    } finally {
      server.close();
    }

    // Depuis la PWA (même origine), la même requête réussit.
    const sameOriginResult = await page.evaluate(async () => {
      const response = await fetch('/api/public/races');
      return response.ok;
    });
    expect(sameOriginResult).toBe(true);
  });
});

function startStaticServer(): Promise<Server> {
  return new Promise((resolve) => {
    const server = createServer((_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end('<!doctype html><html><body>foreign origin</body></html>');
    });
    server.listen(FOREIGN_ORIGIN_PORT, () => resolve(server));
  });
}
