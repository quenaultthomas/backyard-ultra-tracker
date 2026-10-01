import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';
import { findAdminBlocks, isAdminBlockUrl } from '../fixtures/admin-separation';
import { loginAdmin } from '../fixtures/ui';

/**
 * CA6 (inc. 6) — Code admin non préchargé ni mis en cache par le public (RG5). Test exécuté sur le build de
 * production AVEC service worker actif (comme CA39 inc. 4) : les autres tests E2E bloquent le service worker
 * (LIM-E2E-1) et ne conviennent pas ici. Le test inspecte le journal réseau et Cache Storage ; il n'utilise pas
 * l'émulation hors ligne (R5-3).
 *
 *  1. Les blocs admin sont repérés dans le répertoire du build par les textes de RG5 ; la liste n'est jamais vide.
 *  2. En anonyme, on ouvre `/`, `/courses/{id}`, `/inscription/{id}`, `/scan` et `/admin`.
 *  3. Une fois le préchargement du service worker terminé (tous les fichiers du groupe `app` de `ngsw.json` sont
 *     dans Cache Storage : sans cette attente, un test vide passerait), ni le journal réseau ni Cache Storage ne
 *     contiennent de bloc admin.
 *  4. Après une connexion ADMIN et l'ouverture de `/admin`, au moins un bloc admin est chargé.
 */
test.describe('@INC-6 @INC6-CA6 Code admin non préchargé ni mis en cache par le public', () => {
  test('aucun bloc admin dans le réseau ni dans Cache Storage en anonyme ; chargé après connexion ADMIN', async ({
    page, context,
  }, testInfo) => {
    test.setTimeout(150_000);
    const baseUrl = testInfo.project.use.baseURL as string;
    const api = new Api(baseUrl);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I6C6-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });

    // 1. Blocs admin du build (jamais vide) ; chacun est bien servi sous ce nom (le build testé est le build servi).
    const adminBlocks = findAdminBlocks();
    for (const name of adminBlocks) {
      const served = await page.request.get(`${baseUrl}/${name}`);
      expect(served.status(), `${name} servi par le backend`).toBe(200);
    }
    testInfo.annotations.push({ type: 'blocs-admin', description: adminBlocks.join(', ') });

    // Fichiers que le service worker doit précharger (ngsw.json servi, groupes en installMode prefetch).
    const ngsw = (await (await page.request.get(`${baseUrl}/ngsw.json`)).json()) as {
      assetGroups: { name: string; installMode: string; updateMode: string; urls: string[] }[];
    };
    const prefetched = ngsw.assetGroups.filter((group) => group.installMode === 'prefetch')
      .flatMap((group) => group.urls);
    expect(prefetched.length, 'fichiers préchargés selon ngsw.json').toBeGreaterThan(0);

    const requested: string[] = [];
    context.on('request', (request) => requested.push(request.url()));

    // 2. Parcours anonyme, service worker actif.
    await page.goto('/');
    await page.waitForFunction(() => navigator.serviceWorker.getRegistrations().then((regs) => regs.length > 0),
      null, { timeout: 60_000 });
    await page.reload();
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, { timeout: 60_000 });
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');

    // 3a. Préchargement terminé : tous les fichiers attendus sont dans Cache Storage.
    await expect.poll(async () => missingFromCache(await page.evaluate(listCachedUrls), prefetched), {
      message: 'fichiers préchargés absents de Cache Storage', timeout: 90_000, intervals: [500, 1_000, 2_000],
    }).toEqual([]);

    await page.goto(`/courses/${race.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(race.name);
    await page.goto(`/inscription/${race.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Inscription');
    await page.goto('/scan');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await page.goto('/admin');
    // Assertion souple : le contrôle de CA6 (réseau et Cache Storage) doit se poursuivre même si l'écran affiché
    // n'est pas « Page introuvable » (CA3 le vérifie à part), pour que les deux écarts éventuels soient visibles.
    await expect.soft(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable');

    // 3b. Ni réseau ni Cache Storage ne contiennent de bloc admin.
    expect(requested.filter((url) => isAdminBlockUrl(url, adminBlocks)), 'blocs admin dans le journal réseau')
      .toEqual([]);
    const cached = await page.evaluate(listCachedUrls);
    expect(cached.length, 'Cache Storage non vide').toBeGreaterThan(0);
    expect(cached.filter((url) => isAdminBlockUrl(url, adminBlocks)), 'blocs admin dans Cache Storage').toEqual([]);

    // 4. Connexion ADMIN et ouverture de /admin : au moins un bloc admin est chargé.
    await loginAdmin(page, '/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');
    expect(requested.filter((url) => isAdminBlockUrl(url, adminBlocks)).length, 'blocs admin chargés après connexion')
      .toBeGreaterThan(0);
  });
});

/** URL de toutes les entrées de tous les caches du navigateur (clés seulement, pas les contenus). */
async function listCachedUrls(): Promise<string[]> {
  const urls: string[] = [];
  for (const name of await caches.keys()) {
    const cache = await caches.open(name);
    urls.push(...(await cache.keys()).map((request) => request.url));
  }
  return urls;
}

/** Chemins attendus (`/main-XXXX.js`...) absents des URL en cache. */
function missingFromCache(cached: readonly string[], expectedPaths: readonly string[]): string[] {
  const cachedPaths = new Set(cached.map((url) => new URL(url).pathname));
  return expectedPaths.filter((path) => !cachedPaths.has(path));
}
