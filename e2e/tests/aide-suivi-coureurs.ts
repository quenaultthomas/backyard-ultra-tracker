import { expect, type APIRequestContext, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { MOT_DE_PASSE } from './aide-connexion';
import { MOT_DE_PASSE_ADMIN_MASTER, MOT_DE_PASSE_BENEVOLE_CREE, PSEUDO_ADMIN_MASTER, connecterParApi } from './aide-admin';
import { abandonnerParApi, type MotifAbandon } from './aide-abandon';
import { reintegrerParApi } from './aide-reintegration';
import { avecApi } from './aide-horloge';
import { preparerCourseDeScan, type Coureur, type CourseDeScan } from './aide-scan';
import { lire } from './aide-suivi';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

const MASTER = { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER };

/** Course de 60 min démarrée par l'API, avec `nombreCoureurs` inscrits (dossards 1, 2…) et un bénévole affecté. */
export function courseDemarree(playwright: PlaywrightLib, nombreCoureurs = 2, dureeBoucleMinutes = 60): Promise<CourseDeScan> {
  return preparerCourseDeScan(playwright, { nombreCoureurs, nombreMaxBoucles: 5, dureeBoucleMinutes, demarree: true });
}

async function csrf(ctx: APIRequestContext): Promise<string> {
  await ctx.get('/api/csrf');
  const jeton = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  return jeton!;
}

/** Scan d'un coureur par l'API avec la session du bénévole affecté (201 attendu). */
export async function scannerParApi(playwright: PlaywrightLib, course: CourseDeScan, coureur: Coureur): Promise<void> {
  await avecApi(playwright, async (ctx) => {
    await connecterParApi(ctx, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    const reponse = await ctx.post(`/api/benevole/courses/${course.id}/passages`, {
      headers: { 'X-XSRF-TOKEN': await csrf(ctx) },
      data: { jetonQr: coureur.jeton, identifiantScan: randomUUID(), ageDuScanMillisecondes: 0 },
    });
    expect(reponse.status(), await reponse.text()).toBe(201);
  });
}

/** Abandon manuel par l'API (session admin master). */
export async function abandonManuelParApi(playwright: PlaywrightLib, course: CourseDeScan, coureur: Coureur, motif: MotifAbandon): Promise<void> {
  const statut = await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, coureur.inscriptionId, motif, MASTER));
  expect(statut).toBe(200);
}

/** Réintégration par l'API (session admin master). */
export async function reintegrerCoureurParApi(playwright: PlaywrightLib, course: CourseDeScan, coureur: Coureur): Promise<void> {
  const reponse = await avecApi(playwright, (ctx) => reintegrerParApi(ctx, course.id, coureur.inscriptionId, MASTER));
  expect(reponse.statut).toBe(200);
}

/** Suppression (anonymisation) du compte d'un coureur par l'API, session du coureur. */
export async function supprimerCompteCoureurParApi(playwright: PlaywrightLib, coureur: Coureur): Promise<void> {
  await avecApi(playwright, async (ctx) => {
    await connecterParApi(ctx, coureur.pseudo, MOT_DE_PASSE);
    const reponse = await ctx.post('/api/comptes/moi/suppression', {
      headers: { 'X-XSRF-TOKEN': await csrf(ctx) },
      data: { motDePasseActuel: MOT_DE_PASSE },
    });
    expect(reponse.status()).toBe(204);
  });
}

/** Attend `millisecondes` dans la page sans `waitForTimeout` (minuterie de la page, interrogée par `expect.poll`). */
export async function laisserPasser(page: Page, millisecondes: number): Promise<void> {
  await expect
    .poll(() => page.evaluate((ms) => new Promise<boolean>((resolve) => setTimeout(() => resolve(true), ms)), millisecondes), { timeout: millisecondes + 3000 })
    .toBe(true);
}

export const section = (page: Page, nom: RegExp) => page.getByRole('region', { name: nom });
export const SECTION_EN_COURSE = /Coureurs en course/;
export const SECTION_ABANDONS = /Abandons/;

export const CINQ_SECONDES = { timeout: 5000 };

export { lire };
