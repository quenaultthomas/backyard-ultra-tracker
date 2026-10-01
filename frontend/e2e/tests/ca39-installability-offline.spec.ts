import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA39 — Installabilité et hors ligne (RG44, RG45, RG47).
 *
 * Reprise du 2026-09-27 (action corrective 5, motif 3 du NO-GO) : l'assertion précédente acceptait trois
 * issues (« Hors ligne », bandeau de fraîcheur, ou données encore affichées comme fraîches), ce qui ne
 * prouvait jamais réellement l'affichage « Hors ligne » exigé par la spec. Ce test vérifie maintenant
 * explicitement ce texte, sans repli.
 *
 * Sous **Chromium**, chaque route est ouverte par une **navigation complète** (`page.goto`/`page.reload`),
 * réseau coupé (`context.setOffline(true)`). La réponse de navigation renvoyée par Playwright est vérifiée
 * avec `response.fromServiceWorker() === true` : la preuve que le document vient du service worker et non du
 * réseau (RG45), pas seulement une navigation interne du routeur.
 *
 * Sous **WebKit** (LIM-E2E-2, voir docs/tests/rapports/INC-4-e2e.md) : `page.goto()`/`page.reload()` avec
 * `context.setOffline(true)` actif échouent de façon reproductible avec une erreur interne du pilote
 * (« WebKit encountered an internal error »), même sur une page sans aucun service worker — limite de
 * l'outillage, pas de l'application. Seul le volet « ouverture complète hors ligne » (navigation ou
 * rechargement pendant la coupure) reste donc couvert par une navigation interne du routeur après une
 * première visite en ligne de chaque route (écart admis au titre de RG58, arbitrage du 2026-09-27). L'affichage
 * réel de « Hors ligne » sur `/courses/{id}`, lui, est vérifié sans aucun repli, comme sous Chromium.
 *
 * **Constat Chromium (reprise du 2026-09-27), confirmé par diagnostic réseau** : malgré `context.setOffline(true)`
 * et une navigation complète prouvée servie par le service worker (`fromServiceWorker() === true` sur les
 * quatre routes), les requêtes E4 émises par `/courses/{id}` reçoivent quand même une réponse 200 du vrai
 * serveur (observé deux fois, ~1,1 s et ~11,1 s après la coupure). C'est LIM-E2E-1 (service worker actif qui
 * fait échapper une requête `/api/**` à l'émulation réseau de Playwright), déjà documentée pour d'autres CA,
 * qui se manifeste ici alors même que la navigation elle-même est bien coupée du réseau. Conformément à
 * l'énoncé amendé de CA39 (« si l'outil ne peut pas faire échouer E4 sous Chromium avec le service worker
 * actif, le rapport le déclare comme écart à arbitrer — ce n'est jamais un succès »), cette assertion reste
 * intacte et échoue réellement : elle n'est ni affaiblie ni contournée. Voir `INC-4-e2e.md` pour l'écart
 * consigné à l'intention de l'agent fonctionnel.
 */
test.describe('@INC-4 @INC4-CA39 @INC-6 @INC6-CA7 Installabilité et hors ligne', () => {
  test('manifeste correct ; hors ligne réel, prouvé par le service worker', async ({ page, context, browserName }, testInfo) => {
    test.setTimeout(90_000);
    const baseUrl = testInfo.project.use.baseURL as string;
    const api = new Api(baseUrl);
    const run = uniqueRun();
    const raceName = `E2E-OFFLINE-${run}`;
    const race = await api.createRace({
      name: raceName, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    await api.register(race.id, 'Runner Offline');

    const manifestResponse = await page.request.get(`${baseUrl}/manifest.webmanifest`);
    const manifest = await manifestResponse.json();
    expect(manifest.name).toBeTruthy();
    expect(manifest.short_name).toBeTruthy();
    expect(manifest.start_url).toBe('/');
    expect(manifest.display).toBe('standalone');
    const sizes: string[] = manifest.icons.map((icon: { sizes: string }) => icon.sizes);
    expect(sizes).toContain('192x192');
    expect(sizes).toContain('512x512');
    expect(manifest.icons.some((icon: { purpose?: string }) => icon.purpose?.includes('maskable'))).toBe(true);

    // Visite en ligne préalable (RG45) : installe et active le service worker.
    await page.goto('/');
    await page.waitForFunction(() => navigator.serviceWorker.getRegistrations().then((regs) => regs.length > 0),
      null, { timeout: 15_000 });
    await page.reload();
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, { timeout: 15_000 });
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');

    if (browserName === 'chromium') {
      await context.setOffline(true);

      // Chaque route est ouverte par une navigation complète, réseau coupé : la réponse doit venir du
      // service worker (RG45), pas du réseau (qui est coupé et échouerait sinon).
      const homeResponse = await page.goto('/');
      expect(homeResponse?.fromServiceWorker()).toBe(true);
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');

      const scanResponse = await page.goto('/scan');
      expect(scanResponse?.fromServiceWorker()).toBe(true);
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
      await page.getByLabel('Code du QR').fill('11111111-1111-1111-1111-111111111111');
      await page.getByRole('button', { name: 'Valider' }).click();
      await expect(page.getByText('1 en attente')).toBeVisible();

      const adminResponse = await page.goto('/admin');
      expect(adminResponse?.fromServiceWorker()).toBe(true);
      await expect(page.locator('h1, h2').first()).toBeVisible();

      const boardResponse = await page.goto(`/courses/${race.id}`);
      expect(boardResponse?.fromServiceWorker()).toBe(true);
      await expect(page.getByRole('heading', { level: 1 })).toContainText(raceName);

      // Échec réel d'E4 (réseau coupé, y compris pour le service worker qui ne met jamais /api/** en cache,
      // RG45) : le bandeau « Hors ligne » doit apparaître, sans aucun repli sur une autre issue.
      await expect(page.getByText('Hors ligne', { exact: false })).toBeVisible({ timeout: 15_000 });
    } else {
      // WebKit (LIM-E2E-2, voir en-tête) : navigation interne uniquement, après une première visite en ligne
      // de chaque route, pour que son bloc de route soit déjà chargé dans le document avant la coupure.
      //
      // Évolution inc. 6 (adaptation A, spec inc. 6 section 4, CA7) : l'en-tête n'a plus de lien « Administration »
      // (RG2 inc. 6). `/admin` est donc ouvert par navigation directe en ligne (`page.goto`, servie par le service
      // worker ; « Page introuvable » pour un anonyme, un titre h1 reste visible) AVANT les autres écrans : la
      // navigation complète remplace le document, et chaque bloc de route doit être importé dans le document
      // courant avant la coupure (LIM-E2E-2). L'ordre des visites passe donc de Scan, Administration, accueil à
      // Administration, accueil, Scan, accueil ; les assertions sont les mêmes.
      const mainNav = page.getByLabel('Navigation principale');
      await page.goto('/admin');
      await expect(page.locator('h1, h2').first()).toBeVisible();
      await page.getByRole('link', { name: 'Backyard Ultra Tracker' }).click();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');
      await mainNav.getByRole('link', { name: 'Scan' }).click();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
      await page.getByRole('link', { name: 'Backyard Ultra Tracker' }).click();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');
      await page.locator('li.card').filter({ hasText: raceName }).getByRole('link', { name: 'Tableau de bord' }).click();
      await expect(page.getByRole('heading', { level: 1 })).toContainText(raceName);
      await expect(page.getByText('Mis à jour à', { exact: false })).toBeVisible({ timeout: 10_000 });

      await context.setOffline(true);

      // Échec réel d'E4, vérifié sans aucun repli (le tableau de bord reste ouvert pendant la coupure : son
      // propre polling, RG29, doit échouer et afficher « Hors ligne »).
      await expect(page.getByText('Hors ligne', { exact: false })).toBeVisible({ timeout: 15_000 });

      // /scan (bloc déjà chargé, navigation interne, aucune requête réseau pour la navigation elle-même).
      await mainNav.getByRole('link', { name: 'Scan' }).click();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
      await page.getByLabel('Code du QR').fill('11111111-1111-1111-1111-111111111111');
      await page.getByRole('button', { name: 'Valider' }).click();
      await expect(page.getByText('1 en attente')).toBeVisible();

      // /admin (bloc déjà chargé). Évolution inc. 6 (adaptation A) : plus de lien « Administration » à cliquer ;
      // navigation du routeur sans lien ni rechargement (pushState + popstate), donc sans requête réseau pour la
      // navigation elle-même, comme le clic d'origine. Même assertion : un titre h1 ou h2 est visible.
      await page.evaluate(() => {
        window.history.pushState(null, '', '/admin');
        window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
      });
      // R6-3 : le h1 « Scan » étant déjà affiché, seule l'assertion sur « Page introuvable » prouve la navigation.
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable');
    }

    const cachedApiUrls = await page.evaluate(async () => {
      const cacheNames = await caches.keys();
      const urls: string[] = [];
      for (const name of cacheNames) {
        const cache = await caches.open(name);
        const requests = await cache.keys();
        urls.push(...requests.map((request) => request.url));
      }
      return urls.filter((url) => url.includes('/api/'));
    });
    expect(cachedApiUrls).toEqual([]);

    await context.setOffline(false);
  });
});
