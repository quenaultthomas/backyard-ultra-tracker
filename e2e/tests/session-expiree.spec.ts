import { expect, test, type Page } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir, seConnecter } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  creerAdminParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
} from './aide-admin';
import { seDeconnecterParLeMenu, ouvrirMenuCompte } from './aide-entete';

const TEXTE_SESSION_EXPIREE = 'Votre session a expiré ou a été fermée. Veuillez vous reconnecter.';

async function connecterAdmin(page: Page, pseudo: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, MOT_DE_PASSE_ADMIN_CREE);
  await page.getByTestId('bouton-connexion').click();
  await expect(page).toHaveURL(/\/administration$/);
}

/** Simule l'expiration côté serveur : le cookie de session (HttpOnly) disparaît du navigateur. */
async function perdreLaSession(page: Page): Promise<void> {
  await page.context().clearCookies({ name: 'JSESSIONID' });
  expect((await page.context().cookies()).some((c) => c.name === 'JSESSIONID')).toBe(false);
}

test.describe('Session expirée (8.2)', () => {
  test.beforeAll(() => exigerIdentifiantsAdminMaster());

  test('CA10 - un admin connecté dont la session a disparu voit le message après un clic dans l\'application, une seule fois, puis retrouve son écran', async ({ page, request }) => {
    const nadia = pseudoAdminUnique();
    await creerAdminParApi(request, nadia);
    await connecterAdmin(page, nadia);
    await page.getByTestId('lien-gestion-courses').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await expect(page.getByTestId('titre-courses')).toBeVisible();
    const verificationAcces = page.waitForResponse((r) => r.url().endsWith('/api/administration/acces'));
    await page.getByTestId('lien-retour-administration').click();
    await expect(page).toHaveURL(/\/administration$/);
    expect((await verificationAcces).status()).toBe(204);

    await perdreLaSession(page);
    await page.getByTestId('lien-gestion-benevoles').click();

    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fbenevoles$/);
    await expect(page.getByTestId('message-session-expiree')).toHaveText(TEXTE_SESSION_EXPIREE);
    await expect(page.getByTestId('message-session-expiree')).toHaveAttribute('role', 'status');

    await page.reload();
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await expect(page.getByTestId('message-session-expiree')).toHaveCount(0);

    await saisir(page, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/administration\/benevoles$/);
    await expect(page.getByTestId('titre-benevoles')).toBeVisible();
  });

  test('CA10 - une coureuse connectée dont la session a disparu voit le message après un clic dans l\'application', async ({ page, request }) => {
    const alice = pseudoUnique('alice');
    await creerCompteParApi(request, alice);
    await seConnecter(page, alice);
    await ouvrirMenuCompte(page);
    await page.getByTestId('menu-lien-mes-inscriptions').click();
    await expect(page).toHaveURL(/\/coureur\/inscriptions$/);
    await expect(page.getByTestId('inscriptions-titre')).toBeVisible();
    await ouvrirMenuCompte(page);
    await page.getByTestId('menu-lien-mon-compte').click();
    await expect(page).toHaveURL(/\/mon-compte$/);
    await expect(page.getByTestId('titre-mon-compte')).toBeVisible();

    // Retour vers « Mes inscriptions » (écran qui interroge l'API) avec la session perdue.
    await perdreLaSession(page);
    await ouvrirMenuCompte(page);
    await page.getByTestId('menu-lien-mes-inscriptions').click();

    await expect(page).toHaveURL(/\/connexion\?retour=%2Fcoureur%2Finscriptions$/);
    await expect(page.getByTestId('message-session-expiree')).toHaveText(TEXTE_SESSION_EXPIREE);
  });

  test('CA10 (a) - sans message après suppression du cookie puis rechargement d\'un écran protégé', async ({ page, request }) => {
    const nadia = pseudoAdminUnique();
    await creerAdminParApi(request, nadia);
    await connecterAdmin(page, nadia);
    await page.getByTestId('lien-gestion-courses').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);

    await perdreLaSession(page);
    await page.reload();

    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fcourses$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await expect(page.getByTestId('message-session-expiree')).toHaveCount(0);
  });

  test('CA10 (b) - un visiteur anonyme qui ouvre /administration/courses arrive sur la connexion sans message', async ({ page }) => {
    await page.goto('/administration/courses');
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fcourses$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await expect(page.getByTestId('message-session-expiree')).toHaveCount(0);
  });

  test('CA10 (c) - après « Se déconnecter », seul « Vous êtes déconnecté. » s\'affiche', async ({ page, request }) => {
    const alice = pseudoUnique('alice');
    await creerCompteParApi(request, alice);
    await seConnecter(page, alice);
    await seDeconnecterParLeMenu(page);

    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('message-deconnexion')).toHaveText('Vous êtes déconnecté.');
    await expect(page.getByTestId('message-session-expiree')).toHaveCount(0);
  });
});
