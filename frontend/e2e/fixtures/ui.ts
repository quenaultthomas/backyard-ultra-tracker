import { expect, type Page, type Request } from '@playwright/test';

/** Connexion ADMIN par l'écran de connexion, avec retour vers `retour` (l'admin n'est gardé qu'en mémoire). */
export async function loginAdmin(page: Page, retour = '/admin'): Promise<void> {
  await page.goto(`/connexion?retour=${encodeURIComponent(retour)}`);
  await page.getByLabel("Nom d'utilisateur").fill('admin-test');
  await page.getByLabel('Mot de passe').fill('admin-secret');
  await page.getByRole('button', { name: 'Se connecter' }).click();
}

/** Remplit et valide l'écran `/compte/connexion` (sans navigation préalable si `open` est faux). */
export async function loginRunner(page: Page, pseudo: string, password: string,
                                  options: { readonly remember?: boolean; readonly open?: boolean } = {}):
  Promise<void> {
  if (options.open !== false) {
    await page.goto('/compte/connexion');
  }
  await page.getByLabel('Pseudo').fill(pseudo);
  await page.getByLabel('Mot de passe').fill(password);
  if (options.remember === true) {
    await page.getByLabel('Rester connecté 24 h', { exact: false }).check();
  }
  await page.getByRole('button', { name: 'Se connecter' }).click();
}

/** Compteur de requêtes sortantes de la page (journal réseau) dont la méthode et l'URL correspondent. */
export function countRequests(page: Page, method: string, urlPattern: RegExp): {
  readonly count: () => number;
  readonly urls: () => string[];
} {
  const seen: string[] = [];
  page.on('request', (request: Request) => {
    if (request.method() === method && urlPattern.test(request.url())) {
      seen.push(request.url());
    }
  });
  return { count: () => seen.length, urls: () => [...seen] };
}

/** Attend l'affichage de « Mes inscriptions » avec le pseudo attendu. */
export async function expectAccountPage(page: Page, pseudo: string): Promise<void> {
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mes inscriptions');
  await expect(page.getByText(`Vous êtes connecté en tant que ${pseudo}`)).toBeVisible();
}
