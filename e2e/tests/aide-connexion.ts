import { expect, type APIRequestContext, type Page } from '@playwright/test';

export const MOT_DE_PASSE = 'un-mot-de-passe-12';

export function pseudoUnique(prefixe = 'coureur'): string {
  const suffixe = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
  return `${prefixe}-${suffixe}`;
}

/** Crée un compte coureur via l'API, dans un contexte de requêtes isolé (sans session partagée avec le navigateur). */
export async function creerCompteParApi(request: APIRequestContext, pseudo: string): Promise<void> {
  const csrf = await request.get('/api/csrf');
  expect(csrf.status()).toBe(204);
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  const creation = await request.post('/api/comptes', {
    headers: { 'X-XSRF-TOKEN': jeton! },
    data: { pseudo, motDePasse: MOT_DE_PASSE },
  });
  expect(creation.status()).toBe(201);
}

export async function ouvrirConnexion(page: Page, chemin = '/connexion'): Promise<void> {
  const csrf = page.waitForResponse((r) => r.url().includes('/api/csrf'));
  await page.goto(chemin);
  await csrf;
  await expect(page.getByTestId('titre-connexion')).toBeVisible();
}

export async function saisir(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await page.getByTestId('champ-pseudo').fill(pseudo);
  await page.getByTestId('champ-mot-de-passe').fill(motDePasse);
}

export async function seConnecter(page: Page, pseudo: string, motDePasse = MOT_DE_PASSE): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}
