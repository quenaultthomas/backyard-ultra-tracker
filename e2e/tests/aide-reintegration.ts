import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { connecterParApi } from './aide-admin';
import { lire } from './aide-abandon';

/** Réponse brute d'une réintégration par l'API (setup ou vérification). */
export interface ReponseReintegration {
  statut: number;
  corps: Record<string, unknown>;
}

/** Réintègre un coureur par l'API avec la session de l'admin donné (jeton CSRF relu). */
export async function reintegrerParApi(
  request: APIRequestContext,
  courseId: string,
  inscriptionId: string,
  admin: { pseudo: string; motDePasse: string },
): Promise<ReponseReintegration> {
  await connecterParApi(request, admin.pseudo, admin.motDePasse);
  await request.get('/api/csrf');
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  const reponse = await request.post(`/api/administration/courses/${courseId}/inscriptions/${inscriptionId}/reintegration`, {
    headers: { 'X-XSRF-TOKEN': jeton! },
  });
  return { statut: reponse.status(), corps: (await reponse.json().catch(() => ({}))) as Record<string, unknown> };
}

export const boutonReintegrer = (page: Page, dossard: number, pseudo: string) =>
  page.getByRole('button', { name: `Réintégrer le coureur n° ${dossard} ${pseudo}` });

/** Pluriel de « boucle corrigée » comme l'écran. */
export const libelleCorrigees = (n: number) => (n > 1 ? `${n} boucles corrigées` : `${n} boucle corrigée`);

/** Parcours complet de l'écran : « Réintégrer », panneau, « Confirmer la réintégration ». Attend le 200. */
export async function reintegrerParEcran(page: Page, dossard: number, pseudo: string, bouclesCorrigees: number): Promise<void> {
  await boutonReintegrer(page, dossard, pseudo).click();
  await expect(lire(page, 'pilotage-reintegrer-titre')).toHaveText(`Réintégrer le coureur n° ${dossard} ${pseudo} ?`);
  const reponse = page.waitForResponse((r) => r.request().method() === 'POST' && /\/reintegration$/.test(new URL(r.url()).pathname));
  await lire(page, 'pilotage-reintegrer-confirmer').click();
  expect((await reponse).status()).toBe(200);
  await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
  await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveText(
    `Coureur n° ${dossard} ${pseudo} réintégré : ${libelleCorrigees(bouclesCorrigees)}.`,
  );
}

/** Compte les POST de réintégration émis par la page (corps et en-têtes conservés). */
export function compterPostsReintegration(page: Page): { lire: () => number; corps: () => Array<string | null>; entetes: () => Array<Record<string, string>> } {
  const requetes: Array<{ corps: string | null; entetes: Record<string, string> }> = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/inscriptions\/[^/]+\/reintegration$/.test(new URL(r.url()).pathname)) {
      requetes.push({ corps: r.postData(), entetes: r.headers() });
    }
  });
  return { lire: () => requetes.length, corps: () => requetes.map((r) => r.corps), entetes: () => requetes.map((r) => r.entetes) };
}
