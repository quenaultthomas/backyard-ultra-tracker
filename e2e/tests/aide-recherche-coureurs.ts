import { expect, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs, type Request, type Route } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi } from './aide-connexion';
import { creerAdminParApi, creerBenevoleParApi, connecterParApi } from './aide-admin';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
export const CHEMIN_RECHERCHE = '/api/administration/coureurs';

/** Préfixe de pseudos unique par test (8 caractères, alphabet des pseudos), pour la base partagée en parallèle. */
export function prefixeUnique(): string {
  return `r${Date.now().toString(36).slice(-4)}${Math.random().toString(36).slice(2, 5)}`.replace(/[^a-z0-9]/g, 'k');
}

export async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

export const deuxChiffres = (n: number): string => String(n).padStart(2, '0');

/** Crée les coureurs `<prefixe>01` à `<prefixe>NN` par l'API. */
export async function creerCoureursNumerotes(playwright: PlaywrightLib, prefixe: string, nombre: number): Promise<string[]> {
  const pseudos = Array.from({ length: nombre }, (_, i) => `${prefixe}${deuxChiffres(i + 1)}`);
  for (const pseudo of pseudos) {
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  }
  return pseudos;
}

/** Crée un coureur puis le supprime lui-même (3.6) : le Compte est anonymisé. */
export async function creerCoureurAnonymise(playwright: PlaywrightLib, pseudo: string): Promise<void> {
  await avecApi(playwright, async (ctx) => {
    await creerCompteParApi(ctx, pseudo);
    await connecterParApi(ctx, pseudo, MOT_DE_PASSE);
    await ctx.get('/api/csrf');
    const jeton = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
    expect(jeton).toBeTruthy();
    const reponse = await ctx.post('/api/comptes/moi/suppression', { headers: { 'X-XSRF-TOKEN': jeton! }, data: { motDePasseActuel: MOT_DE_PASSE } });
    expect(reponse.status()).toBeLessThan(300);
  });
}

/** Jeu de la spec CA12 : 12 coureurs, plus un admin, un bénévole et un Compte anonymisé de même préfixe. */
export async function preparerJeuRecherche(playwright: PlaywrightLib, prefixe: string): Promise<{ coureurs: string[]; admin: string; benevole: string; anonymise: string }> {
  const coureurs = await creerCoureursNumerotes(playwright, prefixe, 12);
  const admin = `${prefixe}ad`;
  const benevole = `${prefixe}be`;
  const anonymise = `${prefixe}an`;
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
  await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
  await creerCoureurAnonymise(playwright, anonymise);
  return { coureurs, admin, benevole, anonymise };
}

export const estRecherche = (r: Request): boolean => new URL(r.url()).pathname === CHEMIN_RECHERCHE;
export const fragmentDe = (r: Request): string | null => new URL(r.url()).searchParams.get('q');

/** Enregistre les requêtes de recherche émises par la page (méthode et fragment). */
export function espionnerRecherches(page: Page): Array<{ methode: string; q: string | null }> {
  const vues: Array<{ methode: string; q: string | null }> = [];
  page.on('request', (r) => {
    if (estRecherche(r)) vues.push({ methode: r.method(), q: fragmentDe(r) });
  });
  return vues;
}

/** Aucune requête de recherche n'est émise pendant `ms` millisecondes (attente observée, pas un sommeil). */
export async function aucuneRecherche(page: Page, ms: number): Promise<void> {
  await expect(page.waitForRequest(estRecherche, { timeout: ms })).rejects.toThrow();
}

/** Le localisateur ne devient pas visible pendant `ms` millisecondes. */
export async function resteAbsent(localisateur: Locator, ms = 800): Promise<void> {
  await expect(localisateur.waitFor({ state: 'visible', timeout: ms })).rejects.toThrow();
}

/** Réponse JSON de la recherche au format du contrat. */
export const corpsRecherche = (pseudos: string[], plusDeResultats = false): string =>
  JSON.stringify({ coureurs: pseudos.map((pseudo, i) => ({ id: `00000000-0000-0000-0000-${String(i).padStart(12, '0')}`, pseudo })), plusDeResultats });

/**
 * Retarde la réponse de la recherche dont le fragment vaut exactement `q` : la page reçoit, à `liberer()`,
 * un résultat « sentinelle » (`tardif`). Les autres recherches passent normalement.
 */
export async function retarderRecherche(page: Page, q: string): Promise<{ vue: Promise<void>; liberer: () => void }> {
  let liberer!: () => void;
  const feu = new Promise<void>((resolve) => (liberer = resolve));
  let signaler!: () => void;
  const vue = new Promise<void>((resolve) => (signaler = resolve));
  await page.route((url) => url.pathname === CHEMIN_RECHERCHE, async (route: Route) => {
    if (fragmentDe(route.request()) !== q) {
      await route.continue();
      return;
    }
    signaler();
    await feu;
    await route.fulfill({ status: 200, contentType: 'application/json', body: corpsRecherche(['tardif']) }).catch(() => undefined);
  });
  return { vue, liberer };
}

export const PROBLEME = (status: number, code: string): { status: number; contentType: string; body: string } => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ type: 'about:blank', title: 'Erreur', status, detail: 'Erreur simulée.', instance: CHEMIN_RECHERCHE, code }),
});
