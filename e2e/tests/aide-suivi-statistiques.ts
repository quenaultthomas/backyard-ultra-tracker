import { expect, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { avecApi } from './aide-horloge';
import { preparerCourseDeScan, type CourseDeScan } from './aide-scan';
import { lire, reponseSuivi } from './aide-suivi';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

/** Course « Y » de la spec 6.3 : 60 min, d = 6700 m, h = 35 m, M = 5 par défaut, Alice et Bruno inscrits. */
export function courseY(
  playwright: PlaywrightLib,
  options: { nombreCoureurs?: number; nombreMaxBoucles?: number; demarree?: boolean } = {},
): Promise<CourseDeScan> {
  return preparerCourseDeScan(playwright, {
    nombreCoureurs: options.nombreCoureurs ?? 2,
    nombreMaxBoucles: options.nombreMaxBoucles ?? 5,
    dureeBoucleMinutes: 60,
    distanceBoucleMetres: 6700,
    denivelePositifBoucleMetres: 35,
    demarree: options.demarree ?? true,
  });
}

export const CINQ_SECONDES = { timeout: 5000 };

/** Allure de l'API (secondes par kilomètre) pour une Course, ou null. */
export async function allureDeLApi(playwright: PlaywrightLib, courseId: string): Promise<number | null> {
  return avecApi(playwright, async (ctx) => {
    const reponse = await ctx.get(`/api/courses/${courseId}/suivi`);
    expect(reponse.status()).toBe(200);
    return ((await reponse.json()) as { allureSecondesParKilometre: number | null }).allureSecondesParKilometre;
  });
}

/** Format `m:ss /km`, minutes sans plafond. */
export function formaterAllureAttendue(secondesParKilometre: number): string {
  return `${Math.floor(secondesParKilometre / 60)}:${String(secondesParKilometre % 60).padStart(2, '0')} /km`;
}

export const FORMAT_ALLURE = /^\d+:\d{2} \/km$/;

/** Clic sur « Plein écran » et attente de la confirmation du navigateur (élément plein écran = racine). */
export async function entrerEnProjection(page: Page): Promise<void> {
  const bouton = lire(page, 'suivi-bouton-projection');
  await bouton.click();
  await expect.poll(() => elementPleinEcran(page)).toBe('suivi-racine');
  await expect(bouton).toHaveText('Quitter le plein écran');
}

export const elementPleinEcran = (page: Page) =>
  page.evaluate(() => document.fullscreenElement?.getAttribute('data-testid') ?? null);

/** Taille de police calculée, en px, d'un élément. */
export const taillePolice = (cible: Locator): Promise<number> => cible.evaluate((el) => parseFloat(getComputedStyle(el).fontSize));

/** Valeur en px d'un jeton de taille lu sur `:root`. */
export function tailleJeton(page: Page, jeton: string): Promise<number> {
  return page.evaluate((nom) => {
    const sonde = document.createElement('span');
    sonde.style.fontSize = `var(${nom})`;
    document.documentElement.appendChild(sonde);
    const taille = parseFloat(getComputedStyle(sonde).fontSize);
    sonde.remove();
    return taille;
  }, jeton);
}

export const defilementHorizontal = (page: Page): Promise<number> =>
  page.evaluate(() => {
    const racine = document.querySelector('[data-testid="suivi-racine"]') as HTMLElement;
    return Math.max(
      document.documentElement.scrollWidth - document.documentElement.clientWidth,
      racine.scrollWidth - racine.clientWidth,
    );
  });

/** Aucun élément du header de session n'a le focus après `n` appuis sur Tab (et le focus ne sort pas de la page). */
export async function tabulerSansAtteindreEntete(page: Page, n: number): Promise<void> {
  for (let i = 0; i < n; i++) {
    await page.keyboard.press('Tab');
    const dansEntete = await page.evaluate(() => !!document.activeElement?.closest('app-entete, app-mise-a-jour'));
    expect(dansEntete, `Tab ${i + 1} a atteint l'en-tête`).toBe(false);
  }
}

/** Réponse simulée du suivi avec statistiques. */
export function reponseAvecStatistiques(surcharge: Record<string, unknown> = {}): string {
  return reponseSuivi({
    coureursEnCourse: [],
    abandons: [],
    vainqueurs: [],
    ...surcharge,
  });
}

export const PSEUDO_30 = 'pseudo-de-trente-caracteres-xx';
export const PSEUDO_HTML = '<b>x</b>';
export const NOM_60 = 'Course au nom très long de soixante caractères pour projeter';

/** Réponse simulée : 40 coureurs en course (dont pseudo long et HTML) et 6 abandons, ou course terminée. */
export function reponseChargee(statut: 'EN_COURS' | 'TERMINEE'): string {
  const abandons = Array.from({ length: 6 }, (_, i) => ({
    dossard: 41 + i,
    pseudo: i === 0 ? PSEUDO_30 : `abandon-${41 + i}`,
    boucleAbandon: 3 - Math.floor(i / 2),
    motifAbandon: ['HORS_DELAI', 'MANUEL', 'VOLONTAIRE', 'AUTRE', 'HORS_DELAI', 'HORS_DELAI'][i],
  }));
  const stats = { bouclesTerminees: 3, distanceCumuleeMetres: 20100, denivelePositifCumuleMetres: 105, allureSecondesParKilometre: 433 };
  if (statut === 'TERMINEE') {
    return reponseSuivi({
      nom: NOM_60,
      statut,
      boucleCourante: null,
      derniereBoucle: null,
      boucleMaxTerminee: null,
      tempsAvantFinBoucleMillisecondes: null,
      dureeEcouleeMillisecondes: null,
      coureursEnCourse: [],
      vainqueurs: [{ dossard: 1, pseudo: PSEUDO_30 }],
      abandons,
      ...stats,
      dureeFinaleMillisecondes: 10_800_000,
    });
  }
  const coureurs = Array.from({ length: 40 }, (_, i) => ({ dossard: i + 1, pseudo: `coureur-${i + 1}` }));
  coureurs[0] = { dossard: 1, pseudo: PSEUDO_30 };
  coureurs[1] = { dossard: 2, pseudo: PSEUDO_HTML };
  return reponseSuivi({ nom: NOM_60, coureursEnCourse: coureurs, abandons, vainqueurs: [], ...stats });
}

/** Contrastes (WCAG) de tous les textes visibles de `main` : renvoie les plus faibles. */
export function mesurerContrastes(page: Page): Promise<{ texte: string; couleur: string; fond: string; ratio: number; taille: number }[]> {
  return page.evaluate(() => {
    const analyser = (css: string): number[] => (css.match(/[\d.]+/g) ?? []).slice(0, 4).map(Number);
    const luminance = ([r, g, b]: number[]) => {
      const c = [r, g, b].map((v) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    };
    const fond = (el: Element): string => {
      let e: Element | null = el;
      while (e) {
        const c = getComputedStyle(e).backgroundColor;
        const a = analyser(c);
        if (a.length >= 3 && (a.length === 3 || a[3] > 0)) return c;
        e = e.parentElement;
      }
      return 'rgb(255, 255, 255)';
    };
    const resultats: { texte: string; couleur: string; fond: string; ratio: number; taille: number }[] = [];
    for (const el of document.querySelectorAll('[data-testid="suivi-racine"] *')) {
      const propre = [...el.childNodes].filter((n) => n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim() !== '');
      if (propre.length === 0) continue;
      const rect = el.getBoundingClientRect();
      const style = getComputedStyle(el);
      if (rect.width <= 1 || rect.height <= 1 || style.visibility === 'hidden' || style.display === 'none') continue;
      const c = getComputedStyle(el).color;
      const f = fond(el);
      const l1 = luminance(analyser(c));
      const l2 = luminance(analyser(f));
      const ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
      resultats.push({ texte: (el.textContent ?? '').trim().slice(0, 40), couleur: c, fond: f, ratio, taille: parseFloat(style.fontSize) });
    }
    return resultats;
  });
}
