import { expect, test, type Page } from '@playwright/test';
import { creerCompteParApi, MOT_DE_PASSE, ouvrirConnexion, pseudoUnique, saisir, seConnecter } from './aide-connexion';

const PSEUDO_ADMIN = process.env.E2E_ADMIN_MASTER_PSEUDO ?? '';
const MOT_DE_PASSE_ADMIN = process.env.E2E_ADMIN_MASTER_MOT_DE_PASSE ?? '';

test.beforeAll(() => {
  if (!PSEUDO_ADMIN || !MOT_DE_PASSE_ADMIN) {
    throw new Error('E2E_ADMIN_MASTER_PSEUDO et E2E_ADMIN_MASTER_MOT_DE_PASSE doivent être renseignées (mêmes valeurs que la stack).');
  }
});

async function connecterAdmin(page: Page, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, PSEUDO_ADMIN, MOT_DE_PASSE_ADMIN);
  await page.getByTestId('bouton-connexion').click();
  await expect(page).toHaveURL(/\/administration$/);
  await expect(page.getByTestId('titre-administration')).toBeVisible();
}

test.describe('Espace d\'administration', () => {
  test('1.4 CA24 - l\'admin master se connecte et arrive dans l\'espace d\'administration', async ({ page }) => {
    await ouvrirConnexion(page);
    await saisir(page, PSEUDO_ADMIN, MOT_DE_PASSE_ADMIN);
    await page.getByTestId('bouton-connexion').click();

    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('titre-administration')).toHaveText('Administration');
    await expect(page.getByTestId('administration-role')).toHaveText('Administrateur master');
    await expect(page.getByTestId('administration-vide')).toBeVisible();
    await expect(page.getByTestId('entete-pseudo')).toHaveText(PSEUDO_ADMIN);
    await expect(page.getByTestId('lien-administration')).toBeVisible();

    const acces = await page.request.get('/api/administration/acces');
    expect(acces.status()).toBe(204);
  });

  test('1.4 CA25 - le coureur arrive à l\'accueil, sans lien Administration, et est refusé sur /administration', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);
    await expect(page.getByTestId('lien-administration')).toHaveCount(0);

    await page.goto('/administration');
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(page.getByTestId('titre-acces-refuse')).toHaveText('Accès refusé');
    await expect(page.getByTestId('message-acces-refuse')).toHaveText('Vous n\'avez pas les droits nécessaires pour accéder à cette page.');

    const acces = await page.request.get('/api/administration/acces');
    expect(acces.status()).toBe(403);

    await page.getByTestId('lien-accueil-acces-refuse').click();
    await expect(page).toHaveURL(/\/$/);
  });

  test('1.4 CA25 - l\'écran Accès refusé est public', async ({ page }) => {
    await page.goto('/acces-refuse');
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(page.getByTestId('titre-acces-refuse')).toHaveText('Accès refusé');
    await expect(page.getByTestId('message-acces-refuse')).toBeVisible();
  });

  test('1.4 CA26 - l\'anonyme est renvoyé vers la connexion puis vers la destination selon son rôle', async ({ page, request }) => {
    const csrf = page.waitForResponse((r) => r.url().includes('/api/csrf'));
    await page.goto('/administration');
    await csrf;
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await saisir(page, PSEUDO_ADMIN, MOT_DE_PASSE_ADMIN);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('titre-administration')).toBeVisible();
    await page.getByTestId('bouton-deconnexion').click();
    await expect(page.getByTestId('message-deconnexion')).toBeVisible();

    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await ouvrirConnexion(page, '/connexion?retour=%2Fadministration');
    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await page.goto('/');
    await page.getByTestId('bouton-deconnexion').click();
    await expect(page.getByTestId('message-deconnexion')).toBeVisible();

    await ouvrirConnexion(page, '/connexion?retour=//exemple.org');
    await saisir(page, PSEUDO_ADMIN, MOT_DE_PASSE_ADMIN);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/administration$/);
    expect(new URL(page.url()).origin).toBe(new URL(process.env.BASE_URL ?? 'http://localhost').origin);
  });

  test('1.4 CA27 - le rechargement garde l\'admin sur /administration et le lien de l\'en-tête y ramène', async ({ page }) => {
    await connecterAdmin(page);

    await page.reload();
    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('titre-administration')).toBeVisible();
    await expect(page.getByTestId('administration-role')).toHaveText('Administrateur master');

    await page.goto('/');
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('lien-administration')).toBeVisible();
    await page.getByTestId('lien-administration').click();
    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('titre-administration')).toBeVisible();
  });

  test('1.4 CA28 - les réponses 401, 403 et 500 de l\'appel de contrôle sont gérées', async ({ page }) => {
    await connecterAdmin(page);
    const motif = '**/api/administration/acces';

    await page.route(motif, (route) => route.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ status: 401, code: 'NON_AUTHENTIFIE' }) }));
    await page.goto('/administration');
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration$/);
    await page.unroute(motif);

    // la session serveur reste valide (seule la réponse était simulée) : le rechargement relit le Compte
    await page.goto('/');
    await expect(page.getByTestId('entete-pseudo')).toHaveText(PSEUDO_ADMIN);
    await page.route(motif, (route) => route.fulfill({ status: 403, contentType: 'application/problem+json', body: JSON.stringify({ status: 403, code: 'ACCES_REFUSE' }) }));
    await page.goto('/administration');
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await page.unroute(motif);

    await page.goto('/');
    await page.route(motif, (route) => route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'ERREUR_INTERNE' }) }));
    await page.goto('/administration');
    await expect(page.getByTestId('titre-administration')).toBeVisible();
    await expect(page.getByTestId('administration-erreur')).toHaveText('Impossible de vérifier vos droits d\'accès. Réessayez plus tard.');
    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('administration-erreur')).not.toContainText('500');
  });

  test('1.4 CA29 - la déconnexion de l\'admin ferme l\'espace d\'administration', async ({ page }) => {
    await connecterAdmin(page);

    await page.getByTestId('bouton-deconnexion').click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('message-deconnexion')).toHaveText('Vous êtes déconnecté.');

    await page.goto('/administration');
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
  });
});
