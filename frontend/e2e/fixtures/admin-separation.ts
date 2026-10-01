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
  /**
   * Textes exacts des liens vers `/connexion` attendus sur la page, en-tête compris (inc. 7, RG4 : « Se connecter » de
   * la zone « compte » et/ou celui du bandeau de `/scan`). Égalité exacte : aucun lien en trop, aucun doublon.
   */
  readonly loginLinks: readonly string[];
  /** Textes exacts des liens vers `/inscription` (écran d'inscription autonome) attendus sur la page (RG4). */
  readonly createAccountLinks: readonly string[];
}

/** Zone « compte » de l'en-tête d'un visiteur sans connexion coureur, selon l'écran (RG4, CA14 inc. 7). */
export const ANONYMOUS_BOTH_LINKS: PublicPageAuditOptions = {
  loginLinks: ['Se connecter'], createAccountLinks: ['Créer un compte'],
};
/** `/scan` : un seul « Se connecter » (celui du bandeau) et « Créer un compte ». */
export const ANONYMOUS_SCAN_LINKS: PublicPageAuditOptions = ANONYMOUS_BOTH_LINKS;
/** `/connexion` et `/compte/connexion` : « Créer un compte » seulement. */
export const ANONYMOUS_LOGIN_SCREEN_LINKS: PublicPageAuditOptions = {
  loginLinks: [], createAccountLinks: ['Créer un compte'],
};
/** `/inscription` : « Se connecter » seulement. */
export const ANONYMOUS_CREATE_SCREEN_LINKS: PublicPageAuditOptions = {
  loginLinks: ['Se connecter'], createAccountLinks: [],
};
/** Connecté en coureur : ni « Se connecter » ni « Créer un compte » nulle part (le bandeau de `/scan` aussi). */
export const RUNNER_CONNECTED_LINKS: PublicPageAuditOptions = { loginLinks: [], createAccountLinks: [] };

/**
 * Audit RG2 inc. 6 / CA2 inc. 6 amendé par RG4 inc. 7 (CA14) de la page affichée, en-tête de navigation compris :
 * aucun lien (`a[href]`, `area[href]`) vers `/admin` ou `/admin/...` ; les liens vers `/connexion` et `/inscription`
 * sont exactement ceux attendus (`options`) ; aucun texte visible ni nom accessible de lien ou de bouton contenant
 * « Administration ». Les contrôles « aucun lien /admin » et « aucun texte Administration » sont inchangés.
 */
export async function auditNoAdminLink(page: Page, label: string, options: PublicPageAuditOptions): Promise<void> {
  const readLinks = () => page.evaluate(() => Array.from(document.querySelectorAll('a[href], area[href]')).map((el) => ({
    path: new URL((el as HTMLAnchorElement).href).pathname,
    text: (el.textContent ?? '').trim(),
    label: el.getAttribute('aria-label') ?? '',
  })));
  // L'en-tête calcule sa zone « compte » à la fin de la navigation : attente explicite de l'état stable attendu
  // (le titre de la page peut s'afficher quelques instants avant), puis contrôle exact de cet état stable.
  await expect.poll(async () => {
    const current = await readLinks();
    return {
      login: current.filter((link) => link.path === '/connexion').map((link) => link.text),
      create: current.filter((link) => link.path === '/inscription').map((link) => link.text),
    };
  }, { message: `${label} : zone « compte » de l'en-tête` })
    .toEqual({ login: [...options.loginLinks], create: [...options.createAccountLinks] });
  const links = await readLinks();
  const adminLinks = links.filter((link) => link.path === '/admin' || link.path.startsWith('/admin/'));
  expect(adminLinks, `${label} : lien vers /admin`).toEqual([]);

  expect(links.filter((link) => link.path === '/connexion').map((link) => link.text),
    `${label} : liens vers /connexion`).toEqual([...options.loginLinks]);
  expect(links.filter((link) => link.path === '/inscription').map((link) => link.text),
    `${label} : liens vers /inscription`).toEqual([...options.createAccountLinks]);

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

/** Texte de la zone « compte » de l'en-tête (rôle `banner`) hors titre et navigation : « Connecté : {pseudo} » ou liens. */
export async function headerAccountText(page: Page): Promise<string> {
  const banner = page.getByRole('banner');
  const whole = (await banner.innerText()).replace(/\s+/g, ' ').trim();
  const known = ['Backyard Ultra Tracker', 'Courses', 'Mes inscriptions', 'Scan'];
  return known.reduce((rest, word) => rest.replace(word, ''), whole).replace(/\s+/g, ' ').trim();
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
