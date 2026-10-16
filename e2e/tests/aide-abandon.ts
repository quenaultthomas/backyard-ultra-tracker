import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { connecterAdminMaster, connecterParApi, MOT_DE_PASSE_ADMIN_CREE } from './aide-admin';
import { ouvrirConnexion, saisir } from './aide-connexion';

export type MotifAbandon = 'VOLONTAIRE' | 'MANUEL' | 'AUTRE';

export const LIBELLE_MOTIF: Record<MotifAbandon, string> = { VOLONTAIRE: 'Volontaire', MANUEL: 'Manuel', AUTRE: 'Autre' };

export const urlPilotage = (courseId: string) => `/administration/courses/${courseId}/pilotage`;

export const lire = (page: Page, testid: string) => page.getByTestId(testid);

/** Connexion d'un admin (compte créé par l'API) par l'écran. */
export async function connecterAdmin(page: Page, pseudo: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, MOT_DE_PASSE_ADMIN_CREE);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}

export async function connecterPatron(page: Page): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
}

/** Ouvre le pilotage et attend le tableau des coureurs en course. */
export async function ouvrirPilotage(page: Page, courseId: string, nombreEnCourse: number): Promise<void> {
  await page.goto(urlPilotage(courseId));
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(nombreEnCourse);
}

/** Abandon manuel par l'API (setup) avec la session de l'admin donné (défaut : admin master via l'appelant). Renvoie le statut HTTP. */
export async function abandonnerParApi(
  request: APIRequestContext,
  courseId: string,
  inscriptionId: string,
  motif: MotifAbandon,
  admin: { pseudo: string; motDePasse: string },
): Promise<number> {
  await connecterParApi(request, admin.pseudo, admin.motDePasse);
  await request.get('/api/csrf');
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  const reponse = await request.post(`/api/administration/courses/${courseId}/inscriptions/${inscriptionId}/abandon`, {
    headers: { 'X-XSRF-TOKEN': jeton! },
    data: { motif },
  });
  return reponse.status();
}

/** Parcours complet de l'écran : « Abandonner », choix du motif, « Confirmer l'abandon ». */
export async function abandonnerParEcran(page: Page, dossard: number, pseudo: string, motif: MotifAbandon): Promise<void> {
  await page.getByRole('button', { name: `Abandonner le coureur n° ${dossard} ${pseudo}` }).click();
  await expect(lire(page, 'pilotage-abandonner-titre')).toHaveText(`Abandonner le coureur n° ${dossard} ${pseudo} ?`);
  await lire(page, `pilotage-abandonner-motif-${motif}`).check();
  const reponse = page.waitForResponse((r) => r.request().method() === 'POST' && /\/abandon$/.test(new URL(r.url()).pathname));
  await lire(page, 'pilotage-abandonner-confirmer').click();
  expect((await reponse).status()).toBe(200);
  await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
}

/** Compte les POST d'abandon émis par la page. */
export function compterPostsAbandon(page: Page): { lire: () => number; corps: () => unknown[]; entetes: () => Array<Record<string, string>> } {
  const requetes: Array<{ corps: unknown; entetes: Record<string, string> }> = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/inscriptions\/[^/]+\/abandon$/.test(new URL(r.url()).pathname)) {
      requetes.push({ corps: r.postDataJSON(), entetes: r.headers() });
    }
  });
  return { lire: () => requetes.length, corps: () => requetes.map((r) => r.corps), entetes: () => requetes.map((r) => r.entetes) };
}
