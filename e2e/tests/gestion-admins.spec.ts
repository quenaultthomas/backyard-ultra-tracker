import { expect, test, type Page } from '@playwright/test';
import { creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  PSEUDO_ADMIN_MASTER,
  connecterAdminMaster,
  creerAdminParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
} from './aide-admin';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const URL_ADMINS = /\/administration\/admins$/;
const RETOUR_ADMINS = /\/connexion\?retour=%2Fadministration%2Fadmins$/;

async function ouvrirGestionAdminsEnMaster(page: Page): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
  await page.goto('/administration/admins');
  await expect(page.getByTestId('titre-admins')).toBeVisible();
  // la liste est chargée (vide ou non) avant toute interaction
  await expect(page.getByTestId('liste-admins').or(page.getByTestId('admins-vide'))).toBeVisible();
}

async function remplirFormulaire(page: Page, pseudo: string, motDePasse: string, confirmation: string): Promise<void> {
  await page.getByTestId('admin-champ-pseudo').fill(pseudo);
  await page.getByTestId('admin-champ-mot-de-passe').fill(motDePasse);
  await page.getByTestId('admin-champ-confirmation').fill(confirmation);
}

test.describe('Gestion des administrateurs', () => {
  test('CA21 - l\'admin master crée un administrateur depuis l\'espace d\'administration', async ({ page }) => {
    const pseudo = pseudoAdminUnique();
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('administration-vide')).toBeVisible();

    await page.getByTestId('lien-gestion-admins').click();
    await expect(page).toHaveURL(URL_ADMINS);
    await expect(page.getByTestId('titre-admins')).toHaveText('Gestion des administrateurs');
    await expect(page.getByTestId('liste-admins').or(page.getByTestId('admins-vide'))).toBeVisible();
    await expect(page.getByTestId('ligne-admin').filter({ hasText: pseudo })).toHaveCount(0);

    await remplirFormulaire(page, pseudo, MOT_DE_PASSE_ADMIN_CREE, MOT_DE_PASSE_ADMIN_CREE);
    await page.getByTestId('admin-bouton-creer').click();

    await expect(page.getByTestId('admin-message-succes')).toHaveText(`L'administrateur ${pseudo} a été créé.`);
    await expect(page.getByTestId('ligne-admin').filter({ hasText: pseudo })).toHaveCount(1);
    await expect(page.getByTestId('admin-champ-pseudo')).toHaveValue('');
    await expect(page.getByTestId('admin-champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('admin-champ-confirmation')).toHaveValue('');

    const liste = await page.request.get('/api/administration/admins');
    expect(liste.status()).toBe(200);
    const admins = (await liste.json()) as Array<Record<string, unknown>>;
    const cree = admins.find((a) => a['pseudo'] === pseudo);
    expect(cree).toBeDefined();
    expect(cree!['role']).toBe('ADMIN');
    expect(Object.keys(cree!).some((cle) => /mot.?de.?passe|empreinte|hash/i.test(cle))).toBe(false);

    await page.getByTestId('lien-retour-administration').click();
    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('titre-administration')).toBeVisible();
  });

  test('CA22 - l\'admin créé se connecte, n\'a pas accès à la gestion des administrateurs', async ({ page, request }) => {
    const pseudo = pseudoAdminUnique();
    await creerAdminParApi(request, pseudo);

    await ouvrirConnexion(page);
    await saisir(page, pseudo.toUpperCase(), MOT_DE_PASSE_ADMIN_CREE);
    await page.getByTestId('bouton-connexion').click();

    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('administration-role')).toHaveText('Administrateur');
    await expect(page.getByTestId('lien-gestion-admins')).toHaveCount(0);

    await page.goto('/administration/admins');
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(page.getByTestId('titre-acces-refuse')).toHaveText('Accès refusé');

    const liste = await page.request.get('/api/administration/admins');
    expect(liste.status()).toBe(403);
    await page.request.get('/api/csrf');
    const jeton = (await page.context().storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
    expect(jeton).toBeTruthy();
    const creation = await page.request.post('/api/administration/admins', {
      headers: { 'X-XSRF-TOKEN': jeton! },
      data: { pseudo: pseudoAdminUnique(), motDePasse: MOT_DE_PASSE_ADMIN_CREE },
    });
    expect(creation.status()).toBe(403);
  });

  test('CA23 - les erreurs de saisie sont affichées côté client puis côté serveur', async ({ page, request }) => {
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);
    await ouvrirGestionAdminsEnMaster(page);

    // formulaire vide
    await page.getByTestId('admin-bouton-creer').click();
    await expect(page.getByTestId('admin-erreur-pseudo')).toHaveText('Le pseudo est obligatoire.');
    await expect(page.getByTestId('admin-erreur-mot-de-passe')).toHaveText('Le mot de passe est obligatoire.');

    // mot de passe trop court et confirmations différentes
    await remplirFormulaire(page, pseudoAdminUnique(), 'court-secre', 'autre-chose-12');
    await page.getByTestId('admin-bouton-creer').click();
    await expect(page.getByTestId('admin-erreur-mot-de-passe')).toHaveText('Le mot de passe doit faire au moins 12 caractères.');
    await expect(page.getByTestId('admin-erreur-confirmation')).toHaveText('Les mots de passe ne correspondent pas.');
    await expect(page.getByTestId('admin-erreur-pseudo')).toHaveCount(0);

    // erreur de longueur du pseudo renvoyée par le serveur
    await remplirFormulaire(page, 'ab', MOT_DE_PASSE_ADMIN_CREE, MOT_DE_PASSE_ADMIN_CREE);
    await page.getByTestId('admin-bouton-creer').click();
    await expect(page.getByTestId('admin-erreur-pseudo')).toBeVisible();
    await expect(page.getByTestId('admin-erreur-pseudo')).not.toHaveText('Le pseudo est obligatoire.');
    await expect(page.getByTestId('admin-erreur-pseudo')).toContainText('3');
    await expect(page.getByTestId('admin-champ-pseudo')).toHaveValue('ab');
    await expect(page.getByTestId('admin-champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('admin-champ-confirmation')).toHaveValue('');
    await expect(page.getByTestId('admin-message-succes')).toHaveCount(0);

    // pseudo d'un coureur existant, casse différente
    await remplirFormulaire(page, coureur.toUpperCase(), MOT_DE_PASSE_ADMIN_CREE, MOT_DE_PASSE_ADMIN_CREE);
    await page.getByTestId('admin-bouton-creer').click();
    await expect(page.getByTestId('admin-erreur-pseudo')).toHaveText('Ce pseudo est déjà utilisé.');
    await expect(page.getByTestId('admin-message-succes')).toHaveCount(0);
    await expect(page.getByTestId('ligne-admin').filter({ hasText: coureur })).toHaveCount(0);
  });

  test('CA24 - les accès à /administration/admins dépendent du rôle', async ({ page, browser, request }) => {
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);

    // anonyme : redirigé vers la connexion avec retour, puis arrive sur l'écran après connexion de l'admin master
    await page.goto('/administration/admins');
    await expect(page).toHaveURL(RETOUR_ADMINS);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await saisir(page, PSEUDO_ADMIN_MASTER, process.env.E2E_ADMIN_MASTER_MOT_DE_PASSE ?? '');
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(URL_ADMINS);
    await expect(page.getByTestId('titre-admins')).toBeVisible();
    await page.reload();
    await expect(page).toHaveURL(URL_ADMINS);
    await expect(page.getByTestId('titre-admins')).toBeVisible();

    // le lien est présent pour l'admin master
    await page.goto('/administration');
    await expect(page.getByTestId('lien-gestion-admins')).toBeVisible();

    // coureur : accès refusé
    const contexteCoureur = await browser.newContext();
    const pageCoureur = await contexteCoureur.newPage();
    await pageCoureur.goto('/administration/admins');
    await expect(pageCoureur).toHaveURL(RETOUR_ADMINS);
    await saisir(pageCoureur, coureur, 'un-mot-de-passe-12');
    await pageCoureur.getByTestId('bouton-connexion').click();
    await expect(pageCoureur).toHaveURL(/\/acces-refuse$/);
    await contexteCoureur.close();

    // admin non master : pas de lien sur /administration
    const admin = pseudoAdminUnique();
    await creerAdminParApi(request, admin);
    const contexteAdmin = await browser.newContext();
    const pageAdmin = await contexteAdmin.newPage();
    await ouvrirConnexion(pageAdmin);
    await saisir(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
    await pageAdmin.getByTestId('bouton-connexion').click();
    await expect(pageAdmin).toHaveURL(/\/administration$/);
    await expect(pageAdmin.getByTestId('titre-administration')).toBeVisible();
    await expect(pageAdmin.getByTestId('administration-role')).toBeVisible();
    await expect(pageAdmin.getByTestId('lien-gestion-admins')).toHaveCount(0);
    await contexteAdmin.close();
  });

  test('CA25 - les erreurs 500, 401 et 403 des appels de gestion des administrateurs sont gérées', async ({ page }) => {
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    const motifListe = '**/api/administration/admins';
    const problemes = (status: number, code: string) => ({
      status,
      contentType: 'application/problem+json',
      body: JSON.stringify({ status, code }),
    });

    // liste en 500 : message sans code HTTP, formulaire utilisable
    await page.route(motifListe, (route) =>
      route.request().method() === 'GET' ? route.fulfill(problemes(500, 'ERREUR_INTERNE')) : route.continue(),
    );
    await page.goto('/administration/admins');
    await expect(page.getByTestId('admins-erreur')).toHaveText('Impossible de charger la liste des administrateurs. Réessayez plus tard.');
    await expect(page.getByTestId('admins-erreur')).not.toContainText('500');
    await expect(page.getByTestId('admin-champ-pseudo')).toBeEnabled();
    await expect(page.getByTestId('admin-bouton-creer')).toBeEnabled();

    // POST en 500 : message général, pseudo conservé, mots de passe vidés
    await page.unroute(motifListe);
    await page.route(motifListe, (route) =>
      route.request().method() === 'POST' ? route.fulfill(problemes(500, 'ERREUR_INTERNE')) : route.continue(),
    );
    await page.goto('/administration/admins');
    await expect(page.getByTestId('liste-admins').or(page.getByTestId('admins-vide'))).toBeVisible();
    const pseudo = pseudoAdminUnique();
    await remplirFormulaire(page, pseudo, MOT_DE_PASSE_ADMIN_CREE, MOT_DE_PASSE_ADMIN_CREE);
    await page.getByTestId('admin-bouton-creer').click();
    await expect(page.getByTestId('admin-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('admin-champ-pseudo')).toHaveValue(pseudo);
    await expect(page.getByTestId('admin-champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('admin-champ-confirmation')).toHaveValue('');
    await page.unroute(motifListe);

    // liste en 401 : redirection vers la connexion avec retour
    await page.route(motifListe, (route) =>
      route.request().method() === 'GET' ? route.fulfill(problemes(401, 'NON_AUTHENTIFIE')) : route.continue(),
    );
    await page.goto('/administration/admins');
    await expect(page).toHaveURL(RETOUR_ADMINS);
    await page.unroute(motifListe);

    // liste en 403 ACCES_REFUSE : écran Accès refusé (la session serveur reste valide)
    await page.goto('/');
    await page.route(motifListe, (route) =>
      route.request().method() === 'GET' ? route.fulfill(problemes(403, 'ACCES_REFUSE')) : route.continue(),
    );
    await page.goto('/administration/admins');
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });
});
