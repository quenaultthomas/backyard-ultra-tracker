import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
type PlaywrightLib = PlaywrightWorkerArgs['playwright'];
import { creerCompteParApi, MOT_DE_PASSE, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_ADMIN_MASTER,
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
  connecterAdminMaster,
  creerAdminParApi,
  creerBenevoleParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const URL_BENEVOLES = /\/administration\/benevoles$/;
const RETOUR_BENEVOLES = /\/connexion\?retour=%2Fadministration%2Fbenevoles$/;
const RETOUR_BENEVOLE = /\/connexion\?retour=%2Fbenevole$/;

async function remplirFormulaire(page: Page, pseudo: string, motDePasse: string, confirmation: string): Promise<void> {
  await page.getByTestId('benevole-champ-pseudo').fill(pseudo);
  await page.getByTestId('benevole-champ-mot-de-passe').fill(motDePasse);
  await page.getByTestId('benevole-champ-confirmation').fill(confirmation);
}

async function connecter(page: Page, pseudo: string, motDePasse: string, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

async function ouvrirGestionBenevoles(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await connecter(page, pseudo, motDePasse);
  await expect(page).toHaveURL(/\/administration$/);
  await page.goto('/administration/benevoles');
  await expect(page.getByTestId('titre-benevoles')).toBeVisible();
  await expect(page.getByTestId('liste-benevoles').or(page.getByTestId('benevoles-vide'))).toBeVisible();
}

// une session API par appel : un contexte déjà connecté recevrait 409 DEJA_CONNECTE
async function avecContexteApi(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<void>): Promise<void> {
  const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
  try {
    await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

const probleme = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code }),
});

test.describe('Gestion des bénévoles', () => {
  test('CA17 - l\'admin master puis un admin créent un bénévole depuis l\'espace d\'administration', async ({ page, browser, request }) => {
    const pseudo = pseudoBenevoleUnique();
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);

    await page.getByTestId('lien-gestion-benevoles').click();
    await expect(page).toHaveURL(URL_BENEVOLES);
    await expect(page.getByTestId('titre-benevoles')).toHaveText('Gestion des bénévoles');
    await expect(page.getByTestId('liste-benevoles').or(page.getByTestId('benevoles-vide'))).toBeVisible();

    await remplirFormulaire(page, pseudo, MOT_DE_PASSE_BENEVOLE_CREE, MOT_DE_PASSE_BENEVOLE_CREE);
    await page.getByTestId('benevole-bouton-creer').click();

    await expect(page.getByTestId('benevole-message-succes')).toHaveText(`Le bénévole ${pseudo} a été créé.`);
    await expect(page.getByTestId('ligne-benevole').filter({ hasText: pseudo })).toHaveCount(1);
    await expect(page.getByTestId('benevole-champ-pseudo')).toHaveValue('');
    await expect(page.getByTestId('benevole-champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('benevole-champ-confirmation')).toHaveValue('');

    const liste = await page.request.get('/api/administration/benevoles');
    expect(liste.status()).toBe(200);
    const benevoles = (await liste.json()) as Array<Record<string, unknown>>;
    const cree = benevoles.find((b) => b['pseudo'] === pseudo);
    expect(cree).toBeDefined();
    expect(cree!['role']).toBe('BENEVOLE');
    expect(Object.keys(cree!).some((cle) => /mot.?de.?passe|empreinte|hash/i.test(cle))).toBe(false);

    await page.getByTestId('lien-retour-administration').click();
    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('titre-administration')).toBeVisible();

    // même création par un admin non master
    const admin = pseudoAdminUnique();
    await creerAdminParApi(request, admin);
    const contexte = await browser.newContext();
    const pageAdmin = await contexte.newPage();
    await ouvrirGestionBenevoles(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
    const pseudo2 = pseudoBenevoleUnique();
    await remplirFormulaire(pageAdmin, pseudo2, MOT_DE_PASSE_BENEVOLE_CREE, MOT_DE_PASSE_BENEVOLE_CREE);
    await pageAdmin.getByTestId('benevole-bouton-creer').click();
    await expect(pageAdmin.getByTestId('benevole-message-succes')).toHaveText(`Le bénévole ${pseudo2} a été créé.`);
    await expect(pageAdmin.getByTestId('ligne-benevole').filter({ hasText: pseudo2 })).toHaveCount(1);
    await expect(pageAdmin.getByTestId('ligne-benevole').filter({ hasText: pseudo })).toHaveCount(1);
    await contexte.close();
  });

  test('CA18 - les erreurs de saisie sont affichées côté client puis côté serveur', async ({ page, request }) => {
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);
    await ouvrirGestionBenevoles(page, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
    const lignesAvant = await page.getByTestId('ligne-benevole').count();

    await page.getByTestId('benevole-bouton-creer').click();
    await expect(page.getByTestId('benevole-erreur-pseudo')).toHaveText('Le pseudo est obligatoire.');
    await expect(page.getByTestId('benevole-erreur-mot-de-passe')).toHaveText('Le mot de passe est obligatoire.');

    await remplirFormulaire(page, pseudoBenevoleUnique(), 'court-secre', 'autre-chose-12');
    await page.getByTestId('benevole-bouton-creer').click();
    await expect(page.getByTestId('benevole-erreur-mot-de-passe')).toHaveText('Le mot de passe doit faire au moins 12 caractères.');
    await expect(page.getByTestId('benevole-erreur-confirmation')).toHaveText('Les mots de passe ne correspondent pas.');
    await expect(page.getByTestId('benevole-erreur-pseudo')).toHaveCount(0);

    await remplirFormulaire(page, 'ab', MOT_DE_PASSE_BENEVOLE_CREE, MOT_DE_PASSE_BENEVOLE_CREE);
    await page.getByTestId('benevole-bouton-creer').click();
    await expect(page.getByTestId('benevole-erreur-pseudo')).toBeVisible();
    await expect(page.getByTestId('benevole-erreur-pseudo')).not.toHaveText('Le pseudo est obligatoire.');
    await expect(page.getByTestId('benevole-erreur-pseudo')).toContainText('3');
    await expect(page.getByTestId('benevole-champ-pseudo')).toHaveValue('ab');
    await expect(page.getByTestId('benevole-champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('benevole-champ-confirmation')).toHaveValue('');
    await expect(page.getByTestId('benevole-message-succes')).toHaveCount(0);

    await remplirFormulaire(page, coureur.toUpperCase(), MOT_DE_PASSE_BENEVOLE_CREE, MOT_DE_PASSE_BENEVOLE_CREE);
    await page.getByTestId('benevole-bouton-creer').click();
    await expect(page.getByTestId('benevole-erreur-pseudo')).toHaveText('Ce pseudo est déjà utilisé.');
    await expect(page.getByTestId('benevole-message-succes')).toHaveCount(0);
    await expect(page.getByTestId('ligne-benevole')).toHaveCount(lignesAvant);
  });

  test('CA19 - les accès à /administration/benevoles et les liens dépendent du rôle', async ({ page, browser, request, playwright }) => {
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);
    const benevole = pseudoBenevoleUnique();
    await avecContexteApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    const admin = pseudoAdminUnique();
    await avecContexteApi(playwright, (ctx) => creerAdminParApi(ctx, admin));

    // anonyme puis admin master avec retour, F5 conservé
    await page.goto('/administration/benevoles');
    await expect(page).toHaveURL(RETOUR_BENEVOLES);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await saisir(page, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(URL_BENEVOLES);
    await expect(page.getByTestId('titre-benevoles')).toBeVisible();
    await page.reload();
    await expect(page).toHaveURL(URL_BENEVOLES);
    await expect(page.getByTestId('titre-benevoles')).toBeVisible();
    await page.goto('/administration');
    await expect(page.getByTestId('lien-gestion-benevoles')).toBeVisible();
    await expect(page.getByTestId('lien-gestion-admins')).toBeVisible();

    // coureur et bénévole : accès refusé
    for (const [pseudoRole, mdp] of [[coureur, MOT_DE_PASSE], [benevole, MOT_DE_PASSE_BENEVOLE_CREE]]) {
      const contexte = await browser.newContext();
      const p = await contexte.newPage();
      await p.goto('/administration/benevoles');
      await expect(p).toHaveURL(RETOUR_BENEVOLES);
      await saisir(p, pseudoRole, mdp);
      await p.getByTestId('bouton-connexion').click();
      await expect(p).toHaveURL(/\/acces-refuse$/);
      await expect(p.getByTestId('titre-acces-refuse')).toHaveText('Accès refusé');
      await contexte.close();
    }

    // admin non master : lien bénévoles présent, lien administrateurs absent
    const contexteAdmin = await browser.newContext();
    const pageAdmin = await contexteAdmin.newPage();
    await connecter(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pageAdmin).toHaveURL(/\/administration$/);
    await expect(pageAdmin.getByTestId('lien-gestion-benevoles')).toBeVisible();
    await expect(pageAdmin.getByTestId('lien-gestion-admins')).toHaveCount(0);
    await contexteAdmin.close();
  });

  test('CA20 - les erreurs 500, 401 et 403 des appels de gestion des bénévoles sont gérées', async ({ page }) => {
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    const motif = '**/api/administration/benevoles';

    await page.route(motif, (route) =>
      route.request().method() === 'GET' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue(),
    );
    await page.goto('/administration/benevoles');
    await expect(page.getByTestId('benevoles-erreur')).toHaveText('Impossible de charger la liste des bénévoles. Réessayez plus tard.');
    await expect(page.getByTestId('benevoles-erreur')).not.toContainText('500');
    await expect(page.getByTestId('benevole-champ-pseudo')).toBeEnabled();
    await expect(page.getByTestId('benevole-bouton-creer')).toBeEnabled();
    await page.unroute(motif);

    await page.route(motif, (route) =>
      route.request().method() === 'POST' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue(),
    );
    await page.goto('/administration/benevoles');
    await expect(page.getByTestId('liste-benevoles').or(page.getByTestId('benevoles-vide'))).toBeVisible();
    const pseudo = pseudoBenevoleUnique();
    await remplirFormulaire(page, pseudo, MOT_DE_PASSE_BENEVOLE_CREE, MOT_DE_PASSE_BENEVOLE_CREE);
    await page.getByTestId('benevole-bouton-creer').click();
    await expect(page.getByTestId('benevole-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('benevole-champ-pseudo')).toHaveValue(pseudo);
    await expect(page.getByTestId('benevole-champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('benevole-champ-confirmation')).toHaveValue('');
    await page.unroute(motif);

    await page.route(motif, (route) =>
      route.request().method() === 'GET' ? route.fulfill(probleme(401, 'NON_AUTHENTIFIE')) : route.continue(),
    );
    await page.goto('/administration/benevoles');
    await expect(page).toHaveURL(RETOUR_BENEVOLES);
    await page.unroute(motif);

    await page.goto('/');
    await page.route(motif, (route) =>
      route.request().method() === 'GET' ? route.fulfill(probleme(403, 'ACCES_REFUSE')) : route.continue(),
    );
    await page.goto('/administration/benevoles');
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });
});

test.describe('Accueil bénévole', () => {
  test('CA21 - le bénévole arrive sur son espace vide, les autres rôles en sont écartés', async ({ page, browser, request, playwright }) => {
    const benevole = pseudoBenevoleUnique();
    await avecContexteApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);
    const admin = pseudoAdminUnique();
    await avecContexteApi(playwright, (ctx) => creerAdminParApi(ctx, admin));

    await connecter(page, benevole.toUpperCase(), MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page).toHaveURL(/\/benevole$/);
    await expect(page.getByTestId('accueil-benevole-titre')).toHaveText('Espace bénévole');
    await expect(page.getByTestId('accueil-benevole-vide')).toHaveText('Aucune course à scanner pour le moment.');
    await expect(page.getByTestId('entete-pseudo')).toHaveText(benevole);
    await expect(page.getByTestId('lien-espace-benevole')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Administration', exact: true })).toHaveCount(0);

    await page.reload();
    await expect(page).toHaveURL(/\/benevole$/);
    await expect(page.getByTestId('accueil-benevole-titre')).toBeVisible();

    await page.goto('/administration');
    await expect(page).toHaveURL(/\/acces-refuse$/);

    // coureur et admin
    for (const [pseudoRole, mdp] of [[coureur, MOT_DE_PASSE], [admin, MOT_DE_PASSE_ADMIN_CREE]]) {
      const contexte = await browser.newContext();
      const p = await contexte.newPage();
      await connecter(p, pseudoRole, mdp);
      await expect(p.getByTestId('entete-pseudo')).toHaveText(pseudoRole);
      await p.goto('/benevole');
      await expect(p).toHaveURL(/\/acces-refuse$/);
      await contexte.close();
    }

    // anonyme
    const contexteAnonyme = await browser.newContext();
    const pageAnonyme = await contexteAnonyme.newPage();
    await pageAnonyme.goto('/benevole');
    await expect(pageAnonyme).toHaveURL(RETOUR_BENEVOLE);
    await contexteAnonyme.close();

    // bénévole avec retour=/administration
    const contexteRetour = await browser.newContext();
    const pageRetour = await contexteRetour.newPage();
    await connecter(pageRetour, benevole, MOT_DE_PASSE_BENEVOLE_CREE, '/connexion?retour=%2Fadministration');
    await expect(pageRetour).toHaveURL(/\/acces-refuse$/);
    await contexteRetour.close();
  });
});
