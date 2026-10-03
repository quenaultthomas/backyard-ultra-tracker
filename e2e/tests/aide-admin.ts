import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';

export const PSEUDO_ADMIN_MASTER = process.env.E2E_ADMIN_MASTER_PSEUDO ?? '';
export const MOT_DE_PASSE_ADMIN_MASTER = process.env.E2E_ADMIN_MASTER_MOT_DE_PASSE ?? '';
export const MOT_DE_PASSE_ADMIN_CREE = 'mot-de-passe-admin-1';

export function exigerIdentifiantsAdminMaster(): void {
  if (!PSEUDO_ADMIN_MASTER || !MOT_DE_PASSE_ADMIN_MASTER) {
    throw new Error('E2E_ADMIN_MASTER_PSEUDO et E2E_ADMIN_MASTER_MOT_DE_PASSE doivent être renseignées (mêmes valeurs que la stack).');
  }
}

async function jetonCsrf(request: APIRequestContext): Promise<string> {
  const csrf = await request.get('/api/csrf');
  expect(csrf.status()).toBe(204);
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  return jeton!;
}

/** Ouvre une session API (contexte isolé) pour le pseudo donné. */
export async function connecterParApi(request: APIRequestContext, pseudo: string, motDePasse: string): Promise<void> {
  const reponse = await request.post('/api/connexion', {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    data: { pseudo, motDePasse },
  });
  expect(reponse.status()).toBe(200);
}

/** Crée un admin par l'API avec la session de l'admin master (contexte isolé). */
export async function creerAdminParApi(request: APIRequestContext, pseudo: string, motDePasse = MOT_DE_PASSE_ADMIN_CREE): Promise<void> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const creation = await request.post('/api/administration/admins', {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    data: { pseudo, motDePasse },
  });
  expect(creation.status()).toBe(201);
}

export function pseudoAdminUnique(): string {
  return pseudoUnique('admin');
}

export async function connecterAdminMaster(page: Page, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  await page.getByTestId('bouton-connexion').click();
}

export const MOT_DE_PASSE_BENEVOLE_CREE = 'mot-de-passe-benevole-1';

export function pseudoBenevoleUnique(): string {
  return pseudoUnique('benevole');
}

/** Crée un bénévole par l'API avec la session de l'admin master (contexte isolé). */
export async function creerBenevoleParApi(request: APIRequestContext, pseudo: string, motDePasse = MOT_DE_PASSE_BENEVOLE_CREE): Promise<void> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const creation = await request.post('/api/administration/benevoles', {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    data: { pseudo, motDePasse },
  });
  expect(creation.status()).toBe(201);
}
