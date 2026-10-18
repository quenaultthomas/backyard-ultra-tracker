import { expect, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { courseDeReference, dateDansJours, demarrerCourseParApi, creerCourseParApi, envoyerLogoParApi } from './aide-admin';
import { avecApi } from './aide-horloge';
import { preparerCourseDeScan, type CourseDeScan } from './aide-scan';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export const urlSuivi = (id: string) => `/courses/${id}/suivi`;
export const lire = (page: Page, testid: string) => page.getByTestId(testid);
export const estSuivi = (r: Request) => r.method() === 'GET' && /^\/api\/courses\/[^/]+\/suivi$/.test(new URL(r.url()).pathname);
export const MOTIF_API_SUIVI = '**/api/courses/*/suivi';

export function secondes(texte: string): number {
  const m = /^(\d{2,}):(\d{2}):(\d{2})$/.exec(texte.trim());
  expect(m, `format HH:MM:SS attendu, obtenu « ${texte} »`).not.toBeNull();
  return Number(m![1]) * 3600 + Number(m![2]) * 60 + Number(m![3]);
}

export const lireSecondes = async (page: Page, testid: string) => secondes(await lire(page, testid).innerText());

/** Course datée du jour, démarrée, 2 coureurs inscrits, avec logo (suivi public : aucune de ces données n'est lue, 6.1 RG6). */
export async function courseDemarreeAvecLogo(
  playwright: PlaywrightLib,
  options: { dureeBoucleMinutes?: number; nombreMaxBoucles?: number; avecLogo?: boolean } = {},
): Promise<CourseDeScan> {
  const course = await preparerCourseDeScan(playwright, {
    nombreCoureurs: 2,
    nombreMaxBoucles: options.nombreMaxBoucles ?? 5,
    dureeBoucleMinutes: options.dureeBoucleMinutes ?? 60,
    demarree: false,
  });
  // Le logo n'est plus modifiable une fois la Course démarrée (409) : envoi avant le démarrage.
  if (options.avecLogo !== false) {
    const contenu = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));
    await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, course.id, { nom: 'logo.png', type: 'image/png', contenu }));
  }
  await demarrer(playwright, course.id);
  return course;
}

/** Course en préparation (aucun démarrage), avec un coureur inscrit pour pouvoir la démarrer ensuite. */
export async function courseEnPreparation(playwright: PlaywrightLib): Promise<CourseDeScan> {
  return preparerCourseDeScan(playwright, { nombreCoureurs: 1, demarree: false });
}

export async function demarrer(playwright: PlaywrightLib, id: string): Promise<void> {
  await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, id));
}

/** Course sans inscrit, non démarrée, de nom donné. */
export async function courseNommee(playwright: PlaywrightLib, nom: string): Promise<string> {
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date: dateDansJours(0) })));
  return c['id'] as string;
}

export async function poserMarqueur(page: Page): Promise<void> {
  await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));
}

export async function verifierSansRechargement(page: Page): Promise<void> {
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);
}

/** Compte les requêtes GET .../suivi émises par la page. */
export function compterRequetesSuivi(page: Page): { nombre: () => number; instants: number[] } {
  const instants: number[] = [];
  page.on('request', (r) => {
    if (estSuivi(r)) instants.push(Date.now());
  });
  return { nombre: () => instants.length, instants };
}

export function reponseSuivi(surcharge: Record<string, unknown> = {}): string {
  return JSON.stringify({
    courseId: '00000000-0000-0000-0000-000000000001',
    nom: 'Course simulée',
    logoUrl: null,
    statut: 'EN_COURS',
    heureServeur: new Date().toISOString(),
    nombreMaxBoucles: 5,
    boucleCourante: 1,
    derniereBoucle: false,
    boucleMaxTerminee: false,
    tempsAvantFinBoucleMillisecondes: 3_600_000,
    dureeEcouleeMillisecondes: 0,
    ...surcharge,
  });
}
