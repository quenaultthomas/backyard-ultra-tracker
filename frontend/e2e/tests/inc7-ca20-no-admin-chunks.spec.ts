import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from '@playwright/test';
import {
  ADMIN_BLOCK_MARKERS, buildDirectory, findAdminBlocks, isAdminBlockUrl, trackAdminChunks,
} from '../fixtures/admin-separation';
import { chooseStaffEntry } from '../fixtures/ui';

/** Fichiers `.js` du build contenant le texte donné (repère le bloc d'un écran par un texte qui lui est propre). */
function buildFilesContaining(text: string): string[] {
  const directory = buildDirectory();
  return readdirSync(directory).filter((name) => name.endsWith('.js')
    && readFileSync(resolve(directory, name), 'utf-8').includes(text));
}

/**
 * CA20 (inc. 7) — Aucun bloc admin sur `/connexion` et `/inscription` (RG10, RG11, reprise de CA6 inc. 6). Test
 * exécuté sur le build de production AVEC service worker actif et Cache Storage réel (comme INC6-CA6) :
 *  1. les blocs admin du build sont repérés par les textes de RG5 (liste non vide), ainsi que les blocs des deux
 *     nouveaux écrans ; aucun bloc d'écran nouveau n'est un bloc admin ni ne contient de texte admin ;
 *  2. la visite des deux écrans (entrées « Coureur » et « Bénévole », `retour` sous `/admin`), après la fin du
 *     préchargement (sinon un test vide passerait), ne charge ni ne met en cache aucun bloc admin
 *     (`admin-*.js` ou fichier contenant un texte de RG5) ;
 *  3. les deux blocs des nouveaux écrans sont, eux, préchargés (voulu, RG11).
 */
test.describe('@INC-7 @INC7-CA20 Aucun bloc admin sur /connexion et /inscription', () => {
  test('réseau et Cache Storage sans bloc admin après la visite des deux écrans, service worker actif', async ({
    page, context,
  }, testInfo) => {
    test.setTimeout(150_000);
    const baseUrl = testInfo.project.use.baseURL as string;

    // 1. Blocs du build.
    const adminBlocks = findAdminBlocks();
    testInfo.annotations.push({ type: 'blocs-admin', description: adminBlocks.join(', ') });
    const loginBlocks = buildFilesContaining('Je me connecte en tant que');
    const creationBlocks = buildFilesContaining("Bénévole : votre compte est créé par l'organisateur.");
    expect(loginBlocks, 'bloc de l\'écran de connexion').toHaveLength(1);
    expect(creationBlocks, 'bloc de l\'écran d\'inscription').toHaveLength(1);
    for (const name of [...loginBlocks, ...creationBlocks]) {
      expect(name, 'nom de fichier de l\'écran').not.toMatch(/^admin-/);
      expect(adminBlocks, `${name} n'est pas un bloc admin`).not.toContain(name);
      const text = readFileSync(resolve(buildDirectory(), name), 'utf-8');
      for (const marker of ADMIN_BLOCK_MARKERS) {
        expect(text, `${name} : texte admin « ${marker} »`).not.toContain(marker);
      }
      const served = await page.request.get(`${baseUrl}/${name}`);
      expect(served.status(), `${name} servi par le backend`).toBe(200);
    }

    const ngsw = (await (await page.request.get(`${baseUrl}/ngsw.json`)).json()) as {
      assetGroups: { name: string; installMode: string; urls: string[] }[];
    };
    const prefetched = ngsw.assetGroups.filter((group) => group.installMode === 'prefetch')
      .flatMap((group) => group.urls);
    expect(prefetched.length, 'fichiers préchargés selon ngsw.json').toBeGreaterThan(0);

    const requested: string[] = [];
    context.on('request', (request) => requested.push(request.url()));
    const adminChunkRequests = trackAdminChunks(context);

    // 2. Visite de l'écran de connexion, service worker installé puis actif, préchargement terminé.
    await page.goto('/connexion');
    await page.waitForFunction(() => navigator.serviceWorker.getRegistrations().then((regs) => regs.length > 0),
      null, { timeout: 60_000 });
    // Service worker activé avant le rechargement (sinon, sous WebKit, le rechargement peut précéder l'activation
    // et la page rechargée n'est jamais contrôlée).
    await page.evaluate(() => navigator.serviceWorker.ready.then(() => true));
    await page.reload();
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, { timeout: 60_000 });
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    await expect.poll(async () => missingFromCache(await page.evaluate(listCachedUrls), prefetched), {
      message: 'fichiers préchargés absents de Cache Storage', timeout: 90_000, intervals: [500, 1_000, 2_000],
    }).toEqual([]);

    await chooseStaffEntry(page);
    await page.goto(`/connexion?retour=${encodeURIComponent('/admin/comptes')}`);
    await expect(page.getByRole('radio', { name: 'Bénévole' })).toBeChecked();
    await page.goto('/inscription');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Créer un compte');
    await page.goto('/compte/connexion');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');

    // Réseau : aucun bloc admin demandé par ces visites.
    expect(requested.filter((url) => isAdminBlockUrl(url, adminBlocks)), 'blocs admin dans le journal réseau')
      .toEqual([]);
    expect(adminChunkRequests.urls(), 'fichiers admin-*.js demandés').toEqual([]);
    expect(requested.filter((url) => new URL(url).pathname.startsWith('/api/admin/')), 'requêtes /api/admin/**')
      .toEqual([]);

    // Cache Storage : aucun bloc admin, mais les blocs des deux nouveaux écrans y sont (préchargés, RG11).
    const cached = await page.evaluate(listCachedUrls);
    expect(cached.length, 'Cache Storage non vide').toBeGreaterThan(0);
    expect(cached.filter((url) => isAdminBlockUrl(url, adminBlocks)), 'blocs admin dans Cache Storage').toEqual([]);
    expect(cached.filter((url) => /\/admin-[^/]*\.js$/.test(new URL(url).pathname)), 'admin-*.js en cache')
      .toEqual([]);
    const cachedPaths = new Set(cached.map((url) => new URL(url).pathname));
    for (const name of [...loginBlocks, ...creationBlocks]) {
      expect(cachedPaths.has(`/${name}`), `${name} préchargé`).toBe(true);
    }
    testInfo.annotations.push({ type: 'cache-storage', description: `${cachedPaths.size} entrées, aucun bloc admin` });
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
