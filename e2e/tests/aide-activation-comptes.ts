import { expect, type APIRequestContext } from '@playwright/test';
import { MOT_DE_PASSE_ADMIN_MASTER, PSEUDO_ADMIN_MASTER, connecterParApi } from './aide-admin';

export type RouteComptes = 'admins' | 'benevoles';

async function jeton(request: APIRequestContext): Promise<string> {
  expect((await request.get('/api/csrf')).status()).toBe(204);
  const valeur = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(valeur).toBeTruthy();
  return valeur!;
}

/** Désactive (ou réactive) un compte par l'API avec la session de l'admin master (contexte isolé). */
export async function changerActivationParApi(
  request: APIRequestContext,
  route: RouteComptes,
  pseudo: string,
  action: 'desactivation' | 'reactivation',
): Promise<void> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const liste = await request.get(`/api/administration/${route}`);
  expect(liste.status()).toBe(200);
  const compte = ((await liste.json()) as Array<{ id: string; pseudo: string }>).find((c) => c.pseudo === pseudo);
  expect(compte, `compte ${pseudo} introuvable`).toBeTruthy();
  const reponse = await request.post(`/api/administration/${route}/${compte!.id}/${action}`, {
    headers: { 'X-XSRF-TOKEN': await jeton(request) },
  });
  expect(reponse.status()).toBe(204);
}
