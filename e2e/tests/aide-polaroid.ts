import { expect, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { pseudoUnique } from './aide-connexion';
import { avecApi } from './aide-horloge';
import { poserPhoto, sessionCoureur } from './aide-photo';
import { octetsFixture, pngDeTaille } from './aide-photo-ecran';
import { preparerCourseDeScan, type CourseDeScan } from './aide-scan';
import { lire, reponseSuivi } from './aide-suivi';

/*
 * Aides des E2E du tableau de polaroïds (10.2) : course de 4 coureurs (Alice, Karim, Sophie, Tom), mesures de
 * l'inclinaison / des paliers / des nœuds DOM, et réponses de suivi simulées (page.route).
 */

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export const TABLEAU = 'suivi-tableau';
export const ESTIME_PHOTO = /^\/api\/photos\/[0-9a-f]{32}$/;

export interface CourseDeQuatre {
  course: CourseDeScan;
  alice: CourseDeScan['coureurs'][number];
  karim: CourseDeScan['coureurs'][number];
  sophie: CourseDeScan['coureurs'][number];
  tom: CourseDeScan['coureurs'][number];
  /** Jetons de photo de Karim (JPEG) et Sophie (PNG), lus dans `photoUrl` de leur compte. */
  jetonKarim: string;
  jetonSophie: string;
}

/** Pose (ou remplace) la photo d'un coureur par l'API ; renvoie le jeton de la photo. */
export async function poserPhotoCoureur(playwright: PlaywrightLib, pseudo: string, fichier: string, mimeType: string): Promise<string> {
  return avecApi(playwright, async (ctx) => poserPhoto(ctx, await sessionCoureur(ctx, pseudo), octetsFixture(fichier), mimeType, fichier));
}

/** Retire la photo d'un coureur par l'API. */
export async function retirerPhotoCoureur(playwright: PlaywrightLib, pseudo: string): Promise<void> {
  await avecApi(playwright, async (ctx) => {
    const csrf = await sessionCoureur(ctx, pseudo);
    const r = await ctx.delete('/api/comptes/moi/photo', { headers: { 'X-XSRF-TOKEN': csrf } });
    expect(r.status()).toBe(204);
  });
}

/** Course de 60 min avec Alice (1), Karim (2, JPEG), Sophie (3, PNG) et Tom (4), sans photo pour Alice et Tom. */
export async function courseDeQuatre(playwright: PlaywrightLib, options: { demarree?: boolean } = {}): Promise<CourseDeQuatre> {
  const pseudos = ['alice', 'karim', 'sophie', 'tom'].map((p) => pseudoUnique(p));
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 4, pseudos, demarree: options.demarree ?? true });
  const [alice, karim, sophie, tom] = course.coureurs;
  const jetonKarim = await poserPhotoCoureur(playwright, karim.pseudo, 'photo.jpg', 'image/jpeg');
  const jetonSophie = await poserPhotoCoureur(playwright, sophie.pseudo, 'logo.png', 'image/png');
  return { course, alice, karim, sophie, tom, jetonKarim, jetonSophie };
}

/** Inclinaison (degrés, entier) lue sur la matrice calculée du `figure` du polaroïd. */
export async function angle(polaroid: Locator): Promise<number> {
  return polaroid.evaluate((el) => {
    const t = getComputedStyle(el).transform;
    if (t === 'none') return 0;
    const m = /matrix\(([^)]+)\)/.exec(t);
    if (!m) throw new Error(`transformation inattendue : ${t}`);
    const [a, b] = m[1].split(',').map(Number);
    return Math.round((Math.atan2(b, a) * 180) / Math.PI * 1000) / 1000;
  });
}

export const polaroidDe = (page: Page, dossard: number): Locator => lire(page, `suivi-coureur-${dossard}`).getByTestId('suivi-polaroid');

/** Dossards des `li` du tableau, dans l'ordre du DOM. */
export const dossardsDuTableau = (page: Page): Promise<number[]> =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-testid="suivi-tableau"] > li')].map((li) => Number((li.getAttribute('data-testid') ?? '').replace('suivi-coureur-', ''))),
  );

/** Pose un identifiant caché sur chaque `li`, `figure` et `img` du tableau. */
export async function marquerNoeuds(page: Page): Promise<void> {
  await page.evaluate(() => {
    let i = 0;
    for (const li of document.querySelectorAll('[data-testid="suivi-tableau"] > li')) {
      for (const el of [li, ...li.querySelectorAll('figure, img')]) (el as unknown as Record<string, unknown>)['__marque'] = `m${i++}`;
    }
  });
}

/** Marque portée par le nœud du polaroïd (li, figure) ou de son image, ou null si le nœud est nouveau. */
export function marquesDe(page: Page, dossard: number): Promise<{ li: unknown; figure: unknown; img: unknown }> {
  return page.evaluate((d) => {
    const li = document.querySelector(`[data-testid="suivi-coureur-${d}"]`) as unknown as Record<string, unknown> | null;
    const lire = (e: Element | null | undefined) => (e ? ((e as unknown as Record<string, unknown>)['__marque'] ?? null) : null);
    return { li: lire(li as unknown as Element), figure: lire((li as unknown as Element | null)?.querySelector('figure')), img: lire((li as unknown as Element | null)?.querySelector('img')) };
  }, dossard);
}

/** Enregistre les chemins des requêtes `/api/photos/*` émises par la page. */
export function espionnerImages(page: Page): { urls: () => string[]; vers: (chemin: string) => number; vider: () => void } {
  let urls: string[] = [];
  page.on('request', (r: Request) => {
    const chemin = new URL(r.url()).pathname;
    if (chemin.startsWith('/api/photos/')) urls.push(chemin);
  });
  return { urls: () => urls, vers: (chemin) => urls.filter((u) => u === chemin).length, vider: () => (urls = []) };
}

/* ---------- réponses simulées ---------- */

export interface CoureurSimule {
  dossard: number;
  pseudo: string;
  photoUrl: string | null;
}

export const jetonSimule = (dossard: number): string => dossard.toString(16).padStart(32, '0');
export const urlSimulee = (dossard: number): string => `/api/photos/${jetonSimule(dossard)}`;

/** `n` coureurs en course (dossards 1 à n), dont ceux de `avecPhoto` ont une photo. */
export function coureursSimules(n: number, avecPhoto: (dossard: number) => boolean = () => false): CoureurSimule[] {
  return Array.from({ length: n }, (_, i) => ({ dossard: i + 1, pseudo: `coureur-${i + 1}`, photoUrl: avecPhoto(i + 1) ? urlSimulee(i + 1) : null }));
}

export const suiviSimule = (coureurs: CoureurSimule[], surcharge: Record<string, unknown> = {}): string =>
  reponseSuivi({ coureursEnCourse: coureurs, abandons: [], vainqueurs: [], ...surcharge });

/** Sert le suivi avec le corps renvoyé par `corps()` (relu à chaque appel, donc modifiable en cours de test). */
export async function servirSuivi(page: Page, corps: () => string): Promise<void> {
  await page.route('**/api/courses/*/suivi', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: corps() }));
}

/** Sert toute image `/api/photos/*` avec un PNG de `taille` octets. */
export async function servirImages(page: Page, taille = 40 * 1024): Promise<void> {
  const png = taille > 0 ? pngDeTaille(taille) : octetsFixture('logo.png');
  await page.route('**/api/photos/**', (route) => route.fulfill({ status: 200, contentType: 'image/png', body: png }));
}

export const ID_SIMULE = '00000000-0000-0000-0000-000000000001';

/** Contraste WCAG du texte `testid` de chaque polaroïd sur le fond de son `figure`. */
export function contrastesLegendes(page: Page): Promise<number[]> {
  return page.evaluate(() => {
    const analyser = (css: string): number[] => (css.match(/[\d.]+/g) ?? []).slice(0, 4).map(Number);
    const lum = ([r, g, b]: number[]) => {
      const c = [r, g, b].map((v) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    };
    const ratios: number[] = [];
    for (const fig of document.querySelectorAll('[data-testid="suivi-tableau"] figure')) {
      const fond = analyser(getComputedStyle(fig).backgroundColor);
      for (const id of ['suivi-polaroid-pseudo', 'suivi-polaroid-dossard']) {
        const texte = analyser(getComputedStyle(fig.querySelector(`[data-testid="${id}"]`)!).color);
        const [a, b] = [lum(texte), lum(fond)];
        ratios.push((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05));
      }
    }
    return ratios;
  });
}

export { lire };
