import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, type BrowserContext, type Page } from '@playwright/test';

/**
 * Utilitaires des parcours de l'incrément 6 (séparation admin / public). Aucune règle métier ici : lecture du
 * DOM, du journal réseau et du répertoire du build.
 */

/** Textes propres aux écrans admin (RG5) : repèrent les « blocs admin » dans le build de production. */
export const ADMIN_BLOCK_MARKERS: readonly string[] = [
  'Administration des courses',
  'Gérer la course et les coureurs',
  'Imprimer les QR codes',
];

/** Répertoire du build de production (surchargeable par `E2E_BUILD_DIR`). */
export function buildDirectory(): string {
  return process.env['E2E_BUILD_DIR'] ?? resolve(import.meta.dirname, '../../dist/backyard-pwa/browser');
}

/** Fichiers JavaScript du build qui contiennent le code des écrans admin (RG5). Jamais vide, sinon le test échoue. */
export function findAdminBlocks(directory: string = buildDirectory()): string[] {
  const files = readdirSync(directory).filter((name) => name.endsWith('.js'));
  expect(files.length, `aucun fichier .js dans ${directory} : build de production absent ?`).toBeGreaterThan(0);
  const blocks = files.filter((name) => {
    const text = readFileSync(resolve(directory, name), 'utf-8');
    return ADMIN_BLOCK_MARKERS.some((marker) => text.includes(marker));
  });
  expect(blocks.length, `aucun bloc admin repéré dans ${directory} par ${ADMIN_BLOCK_MARKERS.join(' / ')}`)
    .toBeGreaterThan(0);
  return blocks;
}

/** URL d'un bloc admin ? (comparaison sur le dernier segment du chemin). */
export function isAdminBlockUrl(url: string, blocks: readonly string[]): boolean {
  const name = new URL(url).pathname.split('/').pop() ?? '';
  return blocks.includes(name);
}

/** Journal des requêtes `/api/admin/**` de tout le contexte (pages et service worker confondus). */
export function trackAdminApi(context: BrowserContext): { readonly urls: () => string[] } {
  const seen: string[] = [];
  context.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/admin/')) {
      seen.push(request.url());
    }
  });
  return { urls: () => [...seen] };
}

/** Journal des requêtes du contexte vers des fichiers `admin-*.js` (blocs des écrans admin, build nommé). */
export function trackAdminChunks(context: BrowserContext): { readonly urls: () => string[] } {
  const seen: string[] = [];
  context.on('request', (request) => {
    if (/\/admin-[^/]*\.js$/.test(new URL(request.url()).pathname)) {
      seen.push(request.url());
    }
  });
  return { urls: () => [...seen] };
}

export interface PublicPageAuditOptions {
  /** `/scan` sans connexion staff : le seul lien « Se connecter » vers `/connexion` est admis (RG2, RG7). */
  readonly scanLoginLinkAllowed: boolean;
}

/**
 * Audit RG2 / CA2 de la page affichée, en-tête de navigation compris : aucun lien (`a[href]`, `area[href]`) vers
 * `/admin` ou `/admin/...`, aucun lien vers `/connexion` sauf le lien « Se connecter » de `/scan`, aucun texte
 * visible ni nom accessible de lien ou de bouton contenant « Administration ».
 */
export async function auditNoAdminLink(page: Page, label: string, options: PublicPageAuditOptions): Promise<void> {
  const links = await page.evaluate(() => Array.from(document.querySelectorAll('a[href], area[href]')).map((el) => ({
    path: new URL((el as HTMLAnchorElement).href).pathname,
    text: (el.textContent ?? '').trim(),
    label: el.getAttribute('aria-label') ?? '',
  })));
  const adminLinks = links.filter((link) => link.path === '/admin' || link.path.startsWith('/admin/'));
  expect(adminLinks, `${label} : lien vers /admin`).toEqual([]);

  const loginLinks = links.filter((link) => link.path === '/connexion');
  if (options.scanLoginLinkAllowed) {
    expect(loginLinks.map((link) => link.text), `${label} : liens vers /connexion`).toEqual(['Se connecter']);
  } else {
    expect(loginLinks, `${label} : lien vers /connexion`).toEqual([]);
  }

  const formActions = await page.evaluate(() => Array.from(document.querySelectorAll('form[action], [formaction]'))
    .map((el) => el.getAttribute('action') ?? el.getAttribute('formaction') ?? ''));
  expect(formActions.filter((action) => /\/admin|\/connexion/.test(action)), `${label} : action de formulaire`)
    .toEqual([]);

  const visibleText = await page.evaluate(() => document.body.innerText);
  expect(visibleText, `${label} : texte visible`).not.toMatch(/administration/i);
  await expect(page.getByRole('link', { name: /administration/i }), `${label} : lien « Administration »`).toHaveCount(0);
  await expect(page.getByRole('button', { name: /administration/i }), `${label} : bouton « Administration »`)
    .toHaveCount(0);
}

/**
 * Navigation du routeur Angular sans lien et sans rechargement (équivalent de « l'historique du navigateur » de
 * CL9) : indispensable pour ouvrir `/admin` par son URL tout en gardant les identifiants ADMIN, qui ne sont
 * conservés qu'en mémoire (RG7 inc. 4 : un `page.goto` les perdrait).
 */
export async function spaNavigate(page: Page, path: string): Promise<void> {
  await page.evaluate((target) => {
    window.history.pushState(null, '', target);
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }, path);
}

/** Valeur brute des deux emplacements d'identifiants d'IndexedDB (`scanner` pour ADMIN/SCANNER, `runner`). */
export async function credentialSlots(page: Page): Promise<{ scanner: unknown; runner: unknown }> {
  return page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolveDb, reject) => {
      const request = indexedDB.open('backyard-ultra-tracker');
      request.onsuccess = () => resolveDb(request.result);
      request.onerror = () => reject(request.error);
    });
    const read = (key: string): Promise<unknown> => new Promise((resolveValue, reject) => {
      if (!db.objectStoreNames.contains('credentials')) {
        resolveValue(null);
        return;
      }
      const request = db.transaction('credentials', 'readonly').objectStore('credentials').get(key);
      request.onsuccess = () => resolveValue(request.result ?? null);
      request.onerror = () => reject(request.error);
    });
    const slots = { scanner: await read('scanner'), runner: await read('runner') };
    db.close();
    return slots;
  });
}
