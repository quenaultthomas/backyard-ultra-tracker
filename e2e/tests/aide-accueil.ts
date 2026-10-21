import { expect, type Browser, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { MOT_DE_PASSE_ADMIN_CREE, creerCourseParApi, courseDeReference, nomCourseUnique } from './aide-admin';
import { avecApi } from './aide-horloge';
import { placerStatutCourseEnBase } from './aide-coureur';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export type Rubrique = 'en-cours' | 'a-venir' | 'terminees';
export const RUBRIQUES: Rubrique[] = ['en-cours', 'a-venir', 'terminees'];
export const TITRES: Record<Rubrique, string> = { 'en-cours': 'En cours', 'a-venir': 'À venir', terminees: 'Terminées' };
export const MESSAGES_VIDES: Record<Rubrique, string> = {
  'en-cours': 'Aucune course en cours.',
  'a-venir': 'Aucune course à venir.',
  terminees: 'Aucune course terminée.',
};
export const MOTIF_API_COURSES = '**/api/courses';
export const VIDE = { enCours: [], aVenir: [], terminees: [] };

/** Course sans inscrit, non démarrée, de nom et de date donnés. Renvoie l'identifiant. */
export async function creerCourseDatee(playwright: PlaywrightLib, nom: string, date: string): Promise<string> {
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date })));
  return c['id'] as string;
}

/** Course dont le statut est posé en base (la liste de l'accueil lit le statut enregistré, 6.4 RG3). */
export async function creerCourseTerminee(playwright: PlaywrightLib, nom: string, date: string): Promise<string> {
  const id = await creerCourseDatee(playwright, nom, date);
  placerStatutCourseEnBase(id, 'TERMINEE');
  return id;
}

export function nomUnique(libelle: string): string {
  return nomCourseUnique(`Course E2E ${libelle}`);
}

export const rubrique = (page: Page, cle: Rubrique) => page.getByTestId(`accueil-rubrique-${cle}`);

/** Carte de la Course de nom donné, dans toute la page ou dans une rubrique. */
export function carte(page: Page, nom: string, cle?: Rubrique): Locator {
  const portee = cle ? page.getByTestId(`accueil-rubrique-${cle}`) : page;
  return portee.getByTestId('accueil-course').filter({ has: page.getByTestId('accueil-course-nom').getByText(nom, { exact: true }) });
}

export async function ouvrirAccueil(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.getByTestId('titre')).toBeVisible();
  await expect(page.getByTestId('accueil-chargement')).toHaveCount(0);
  await expect(page.getByTestId('accueil-rubrique-en-cours')).toBeVisible();
}

/** Rubrique (ou null) dans laquelle figure chaque Course de nom donné ; une Course doit être dans exactement une rubrique. */
export async function repartition(page: Page, noms: string[]): Promise<Record<string, Rubrique | null>> {
  const resultat: Record<string, Rubrique | null> = {};
  for (const nom of noms) {
    const trouvees: Rubrique[] = [];
    for (const cle of RUBRIQUES) {
      if ((await carte(page, nom, cle).count()) > 0) trouvees.push(cle);
    }
    expect(trouvees.length, `« ${nom} » dans au plus une rubrique`).toBeLessThanOrEqual(1);
    resultat[nom] = trouvees[0] ?? null;
  }
  return resultat;
}

/** Noms des cartes d'une rubrique, dans l'ordre d'affichage, restreints à ceux demandés. */
export async function ordreDansRubrique(page: Page, cle: Rubrique, noms: string[]): Promise<string[]> {
  const tous = (await rubrique(page, cle).getByTestId('accueil-course-nom').allTextContents()).map((n) => n.trim());
  return tous.filter((n) => noms.includes(n));
}

/** Contexte de navigateur neuf, sans session ni stockage. */
export async function pageAnonyme(browser: Browser): Promise<Page> {
  const contexte = await browser.newContext({ serviceWorkers: 'block' });
  return contexte.newPage();
}

export { MOT_DE_PASSE_ADMIN_CREE };
