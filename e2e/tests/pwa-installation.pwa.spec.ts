import { expect, test } from '@playwright/test';
import { PNG } from 'pngjs';
import { analyserPng, attendreServiceWorkerPret } from './aide-pwa';

interface IconeManifeste {
  src: string;
  sizes: string;
  type: string;
  purpose: string;
}

const CSP_ATTENDUE =
  "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

test.describe('Application installable : manifeste, icônes et en-têtes (5.2)', () => {
  test('5.2 CA1 - le manifeste est servi en application/manifest+json avec le contenu attendu et l\'application est installable', async ({ page, request }) => {
    await page.goto('/');
    await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', 'manifest.webmanifest');

    const reponse = await request.get('/manifest.webmanifest');
    expect(reponse.status()).toBe(200);
    expect(reponse.headers()['content-type']).toContain('application/manifest+json');
    const manifeste = await reponse.json();
    expect(manifeste).toMatchObject({
      name: 'Backyard Ultra Tracker',
      short_name: 'Backyard',
      lang: 'fr',
      display: 'standalone',
      start_url: '/benevole',
      scope: '/',
      theme_color: '#14352A',
      background_color: '#F7F1E3',
    });
    expect(manifeste.short_name).toHaveLength(8);
    const icones = manifeste.icons as IconeManifeste[];
    expect(icones).toHaveLength(3);
    expect(icones.map((i) => [i.sizes, i.purpose, i.type])).toEqual([
      ['192x192', 'any', 'image/png'],
      ['512x512', 'any', 'image/png'],
      ['512x512', 'maskable', 'image/png'],
    ]);
    for (const icone of icones) expect(icone.src).toMatch(/^\/icones\/.+\.png$/);

    await attendreServiceWorkerPret(page);

    // Une session CDP ouverte neutralise `setOffline` : elle est fermée dans tous les cas.
    const cdp = await page.context().newCDPSession(page);
    try {
      await cdp.send('Page.enable');
      const application = await cdp.send('Page.getAppManifest');
      expect(application.errors, 'erreurs et avertissements du manifeste').toEqual([]);
      await expect
        .poll(async () => (await cdp.send('Page.getInstallabilityErrors')).installabilityErrors, { timeout: 15_000 })
        .toEqual([]);
    } finally {
      await cdp.detach();
    }
  });

  test('5.2 CA2 - les icônes ont les bons types et dimensions, l\'apple-touch-icon est sans alpha et un fichier absent répond 404', async ({ page, request }) => {
    await page.goto('/');
    const manifeste = await (await request.get('/manifest.webmanifest')).json();
    const icones = manifeste.icons as IconeManifeste[];
    const lire = async (chemin: string) => {
      const reponse = await request.get(chemin);
      expect(reponse.status(), chemin).toBe(200);
      expect(reponse.headers()['content-type'], chemin).toBe('image/png');
      return analyserPng(await reponse.body(), (b) => PNG.sync.read(b));
    };

    const tailles: Array<[number, number]> = [
      [192, 192],
      [512, 512],
      [512, 512],
    ];
    for (const [i, icone] of icones.entries()) {
      const info = await lire(icone.src);
      expect([info.largeur, info.hauteur], icone.src).toEqual(tailles[i]);
      expect(info.coinsOpaques, `${icone.src} : coins opaques`).toBe(true);
      if (icone.purpose === 'maskable') expect([2, 6], 'maskable : sans transparence').toContain(info.typeCouleur);
    }

    const hrefApple = await page.locator('link[rel="apple-touch-icon"]').getAttribute('href');
    expect(hrefApple).toBeTruthy();
    const apple = await lire(new URL(hrefApple!, page.url()).pathname);
    expect([apple.largeur, apple.hauteur]).toEqual([180, 180]);
    expect(apple.typeCouleur, 'apple-touch-icon sans canal alpha (type de couleur 2)').toBe(2);

    const favicon = await request.get('/favicon.ico');
    expect(favicon.status()).toBe(200);
    expect(favicon.headers()['content-type']).toMatch(/^image\/(x-icon|vnd\.microsoft\.icon)/);

    const absente = await request.get('/icones/inexistante.png');
    expect(absente.status(), 'une icône absente ne se replie pas sur index.html').toBe(404);
    expect(absente.headers()['content-type'] ?? '').not.toContain('text/html');

    await expect(page.locator('link[rel="icon"][type="image/svg+xml"]')).toHaveCount(1);
    await expect(page.locator('meta[name="apple-mobile-web-app-capable"]')).toHaveAttribute('content', 'yes');
    await expect(page.locator('meta[name="mobile-web-app-capable"]')).toHaveAttribute('content', 'yes');
    await expect(page.locator('meta[name="apple-mobile-web-app-title"]')).toHaveAttribute('content', 'Backyard');
    await expect(page.locator('meta[name="apple-mobile-web-app-status-bar-style"]')).toHaveAttribute('content', 'default');
  });

  test('5.2 CA3 - en-têtes de cache et de sécurité, ngsw.json sans chemin /api, aucune erreur de CSP', async ({ page, request }) => {
    const violations: string[] = [];
    page.on('console', (m) => {
      if (m.type() === 'error' || m.type() === 'warning') violations.push(m.text());
    });
    const exceptions: Error[] = [];
    page.on('pageerror', (e) => exceptions.push(e));

    await page.goto('/');
    const srcMain = await page.locator('script[src*="main-"]').first().getAttribute('src');
    expect(srcMain).toMatch(/main-[A-Z0-9]{8}\.js$/);
    await attendreServiceWorkerPret(page);

    const sansMaxAge = async (chemin: string) => {
      const reponse = await request.get(chemin);
      expect(reponse.status(), chemin).toBe(200);
      const cache = reponse.headers()['cache-control'] ?? '';
      expect(cache, `${chemin} : no-cache`).toContain('no-cache');
      expect(cache, `${chemin} : jamais max-age`).not.toContain('max-age');
      return reponse;
    };
    await sansMaxAge('/ngsw.json');
    await sansMaxAge('/ngsw-worker.js');
    await sansMaxAge('/manifest.webmanifest');
    const index = await sansMaxAge('/index.html');
    await sansMaxAge('/icones/icone-192.png');

    const script = await request.get(new URL(srcMain!, page.url()).pathname);
    expect(script.status()).toBe(200);
    expect(script.headers()['cache-control']).toBe('public, max-age=31536000, immutable');

    const entetes = index.headers();
    expect(entetes['content-security-policy']).toBe(CSP_ATTENDUE);
    expect(entetes['content-security-policy']).not.toContain('unsafe-eval');
    expect(entetes['content-security-policy']).not.toContain('blob:');
    expect(entetes['x-content-type-options']).toBe('nosniff');

    const ngsw = (await (await request.get('/ngsw.json')).json()) as { hashTable: Record<string, string>; assetGroups: Array<{ urls: string[] }> };
    expect(Object.keys(ngsw.hashTable).length).toBeGreaterThan(0);
    expect(Object.keys(ngsw.hashTable).filter((u) => u.includes('/api'))).toEqual([]);
    expect(ngsw.assetGroups.flatMap((g) => g.urls).filter((u) => u.includes('/api'))).toEqual([]);

    expect(violations.filter((m) => /content security policy|refused to/i.test(m)), 'erreurs de CSP en console').toEqual([]);
    expect(exceptions, 'exceptions non gérées').toEqual([]);
  });
});
