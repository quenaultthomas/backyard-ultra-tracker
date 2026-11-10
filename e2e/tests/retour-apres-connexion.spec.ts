import { expect, test, type Browser, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  connecterAdminMaster,
  connecterParApi,
  creerAdminParApi,
  creerBenevoleParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import { connecterCoureur } from './aide-coureur';
import { ouvrirMenuCompte } from './aide-entete';
import { BASE_URL, avecApi, prefixeUnique } from './aide-recherche-coureurs';
import { chercher, confirmerPassage, ouvrirConfirmation, ouvrirEnAdmin, ouvrirEnMaster, t } from './aide-passage-benevole';
import { chercherAdmin, confirmerPassageAdmin, ouvrirConfirmationAdmin } from './aide-passage-admin';

/*
 * Correctif « destination après connexion avec ?retour= » (docs/specs/correctif-retour-apres-connexion.md, CA1 à CA10).
 * Écran « Connexion » seul concerné. Chaque parcours vérifie aussi que /acces-refuse n'est jamais traversé.
 */
type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

const RETOUR_INSCRIPTIONS = '/connexion?retour=%2Fcoureur%2Finscriptions';
const ORIGINE = new URL(BASE_URL).origin;

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

/** Chemins visités par le cadre principal (y compris les navigations internes de l'application). */
function surveillerNavigations(page: Page): string[] {
  const chemins: string[] = [];
  page.on('framenavigated', (cadre) => {
    if (cadre === page.mainFrame()) chemins.push(new URL(cadre.url()).pathname);
  });
  return chemins;
}

const accesRefuseTraverse = (chemins: string[]): string[] => chemins.filter((c) => c === '/acces-refuse');

async function nouvelleCoureuse(playwright: PlaywrightLib): Promise<string> {
  const pseudo = pseudoUnique('alice');
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  return pseudo;
}

async function nouveauBenevole(playwright: PlaywrightLib): Promise<string> {
  const pseudo = pseudoBenevoleUnique();
  await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, pseudo));
  return pseudo;
}

async function nouvelAdmin(playwright: PlaywrightLib): Promise<string> {
  const pseudo = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, pseudo));
  return pseudo;
}

/** Ouvre `/connexion?retour=<retour encodé>` sans session, saisit les identifiants et valide. */
async function seConnecterAvecRetour(page: Page, retour: string, pseudo: string, motDePasse: string): Promise<void> {
  await page.context().clearCookies();
  await ouvrirConnexion(page, `/connexion?retour=${encodeURIComponent(retour)}`);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

/** Session API dans le contexte du navigateur (accès direct ensuite par `goto`). */
async function ouvrirSession(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await page.context().clearCookies();
  await connecterParApi(page.request, pseudo, motDePasse);
}

/**
 * Fenêtre A : un coureur connecté sur « Mes inscriptions » est promu par `promoteur`, son clic suivant le
 * ramène à `/connexion?retour=%2Fcoureur%2Finscriptions`, puis il se reconnecte. Renvoie la page A et les
 * chemins visités pendant la reconnexion.
 */
async function reconnexionApresPromotion(
  page: Page,
  browser: Browser,
  playwright: PlaywrightLib,
  promotion: 'benevole' | 'admin',
): Promise<{ pageA: Page; chemins: string[]; fermer: () => Promise<void> }> {
  const coureur = `${prefixeUnique()}${promotion === 'benevole' ? 'e5' : 'e6'}`;
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, coureur));
  const contexteA = await browser.newContext({ serviceWorkers: 'block' });
  const pageA = await contexteA.newPage();
  await connecterCoureur(pageA, coureur);

  if (promotion === 'benevole') {
    const nadia = await nouvelAdmin(playwright);
    await ouvrirEnAdmin(page, nadia, '/administration/benevoles', 'titre-benevoles');
    await chercher(page, coureur);
    await ouvrirConfirmation(page, coureur);
    await confirmerPassage(page);
    await expect(t(page, 'benevole-message-action')).toHaveText(`Le coureur ${coureur} est maintenant bénévole.`);
  } else {
    await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
    await chercherAdmin(page, coureur);
    await ouvrirConfirmationAdmin(page, coureur);
    await confirmerPassageAdmin(page);
    await expect(t(page, 'admin-message-action')).toHaveText(`Le coureur ${coureur} est maintenant administrateur.`);
  }

  // au clic suivant dans A : retour à la connexion avec le chemin d'origine
  await ouvrirMenuCompte(pageA);
  await pageA.getByTestId('menu-lien-mes-inscriptions').click();
  await expect(pageA).toHaveURL(new RegExp(`${RETOUR_INSCRIPTIONS.replace('?', '\\?')}$`));

  // reconnexion avec les mêmes identifiants, depuis cette même URL de connexion
  await ouvrirConnexion(pageA, RETOUR_INSCRIPTIONS);
  const chemins = surveillerNavigations(pageA);
  await saisir(pageA, coureur, MOT_DE_PASSE);
  await pageA.getByTestId('bouton-connexion').click();
  return { pageA, chemins, fermer: () => contexteA.close() };
}

test.describe('Destination après connexion avec retour', () => {
  test('CA1 - un coureur promu bénévole qui se reconnecte depuis retour=/coureur/inscriptions arrive sur /benevole, jamais sur /acces-refuse', async ({ page, browser, playwright }) => {
    test.setTimeout(150_000);
    const { pageA, chemins, fermer } = await reconnexionApresPromotion(page, browser, playwright, 'benevole');
    try {
      await expect(pageA).toHaveURL(`${ORIGINE}/benevole`);
      await expect(pageA.getByTestId('accueil-benevole-titre')).toBeVisible();
      expect(accesRefuseTraverse(chemins), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);
      await expect(pageA.getByTestId('titre-acces-refuse')).toHaveCount(0);
    } finally {
      await fermer();
    }
  });

  test('CA2 - un coureur promu admin qui se reconnecte depuis retour=/coureur/inscriptions arrive sur /administration, jamais sur /acces-refuse', async ({ page, browser, playwright }) => {
    test.setTimeout(150_000);
    const { pageA, chemins, fermer } = await reconnexionApresPromotion(page, browser, playwright, 'admin');
    try {
      await expect(pageA).toHaveURL(`${ORIGINE}/administration`);
      await expect(pageA.getByTestId('menu-administration')).toBeVisible();
      expect(accesRefuseTraverse(chemins), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);
    } finally {
      await fermer();
    }
  });

  test('CA3 - Nadia (admin) avec retour=/administration/admins arrive sur /administration sans entrée Admins ; Patron (admin master) arrive sur /administration/admins', async ({ page, playwright }) => {
    const nadia = await nouvelAdmin(playwright);
    const chemins = surveillerNavigations(page);

    await seConnecterAvecRetour(page, '/administration/admins', nadia, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).toHaveURL(`${ORIGINE}/administration`);
    await expect(page.getByTestId('menu-administration-courses')).toBeVisible();
    await expect(page.getByTestId('menu-administration-benevoles')).toBeVisible();
    await expect(page.getByTestId('menu-administration-admins')).toHaveCount(0);
    expect(accesRefuseTraverse(chemins), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);

    await page.context().clearCookies();
    await connecterAdminMaster(page, `/connexion?retour=${encodeURIComponent('/administration/admins')}`);
    await expect(page).toHaveURL(`${ORIGINE}/administration/admins`);
    await expect(t(page, 'titre-admins')).toBeVisible();
    expect(accesRefuseTraverse(chemins), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);
  });

  test('CA4 - un retour externe ou malveillant mène à l\'accueil du rôle, sur l\'origine de l\'application', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await nouvelleCoureuse(playwright);
    const leo = await nouveauBenevole(playwright);
    const chemins = surveillerNavigations(page);

    for (const retour of ['//exemple.org', '/\\exemple.org', 'https://exemple.org', '']) {
      await seConnecterAvecRetour(page, retour, alice, MOT_DE_PASSE);
      await expect(page.getByTestId('entete-pseudo'), `Alice, retour « ${retour} »`).toHaveText(alice);
      await expect(page, `Alice, retour « ${retour} »`).toHaveURL(`${ORIGINE}/`);
      expect(new URL(page.url()).origin).toBe(ORIGINE);

      await seConnecterAvecRetour(page, retour, leo, MOT_DE_PASSE_BENEVOLE_CREE);
      await expect(page, `Léo, retour « ${retour} »`).toHaveURL(`${ORIGINE}/benevole`);
      expect(new URL(page.url()).origin).toBe(ORIGINE);
    }
    expect(chemins.filter((c) => c === '/acces-refuse' || c.includes('exemple')), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);
  });

  test('CA5 - un accès direct à une route interdite mène à /acces-refuse (Alice sur /administration, Léo et Nadia sur /administration/admins)', async ({ page, playwright }) => {
    const alice = await nouvelleCoureuse(playwright);
    const leo = await nouveauBenevole(playwright);
    const nadia = await nouvelAdmin(playwright);

    await ouvrirSession(page, alice, MOT_DE_PASSE);
    await page.goto('/administration');
    await expect(page).toHaveURL(`${ORIGINE}/acces-refuse`);

    await ouvrirSession(page, leo, MOT_DE_PASSE_BENEVOLE_CREE);
    await page.goto('/administration/admins');
    await expect(page).toHaveURL(`${ORIGINE}/acces-refuse`);

    await ouvrirSession(page, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await page.goto('/administration/admins');
    await expect(page).toHaveURL(`${ORIGINE}/acces-refuse`);
  });

  test('CA6 - avec retour=/mon-compte, Alice et Léo arrivent sur /mon-compte', async ({ page, playwright }) => {
    const alice = await nouvelleCoureuse(playwright);
    const leo = await nouveauBenevole(playwright);
    const chemins = surveillerNavigations(page);

    await seConnecterAvecRetour(page, '/mon-compte', alice, MOT_DE_PASSE);
    await expect(page).toHaveURL(`${ORIGINE}/mon-compte`);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(alice);

    await seConnecterAvecRetour(page, '/mon-compte', leo, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page).toHaveURL(`${ORIGINE}/mon-compte`);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(leo);
    expect(accesRefuseTraverse(chemins), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);
  });

  test('CA7 - un anonyme sur /administration/admins passe par /connexion?retour=… puis Patron arrive sur /administration/admins', async ({ page }) => {
    await page.context().clearCookies();
    await page.goto('/administration/admins');
    await expect(page).toHaveURL(`${ORIGINE}/connexion?retour=%2Fadministration%2Fadmins`);
    const chemins = surveillerNavigations(page);

    await connecterAdminMaster(page, '/connexion?retour=%2Fadministration%2Fadmins');
    await expect(page).toHaveURL(`${ORIGINE}/administration/admins`);
    await expect(t(page, 'titre-admins')).toBeVisible();
    expect(accesRefuseTraverse(chemins), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);
  });

  test('CA8 - retour=/connexion et retour=/creer-compte sont traités comme absents : accueil du rôle', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const nadia = await nouvelAdmin(playwright);
    const alice = await nouvelleCoureuse(playwright);
    const chemins = surveillerNavigations(page);

    for (const retour of ['/connexion', '/creer-compte', '/connexion?x=1']) {
      await seConnecterAvecRetour(page, retour, nadia, MOT_DE_PASSE_ADMIN_CREE);
      await expect(page, `Nadia, retour ${retour}`).toHaveURL(`${ORIGINE}/administration`);
      await seConnecterAvecRetour(page, retour, alice, MOT_DE_PASSE);
      await expect(page, `Alice, retour ${retour}`).toHaveURL(`${ORIGINE}/`);
    }
    expect(accesRefuseTraverse(chemins), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);
  });

  test('CA9 - déjà connecté dans un autre onglet (409 DEJA_CONNECTE), Léo valide retour=/coureur/inscriptions et arrive sur /benevole', async ({ context, playwright }) => {
    const leo = await nouveauBenevole(playwright);
    await context.clearCookies();

    // onglet 2 : page de connexion ouverte en anonyme avec le retour (le garde renverrait un Compte connecté vers son accueil)
    const onglet2 = await context.newPage();
    const chemins = surveillerNavigations(onglet2);
    await ouvrirConnexion(onglet2, RETOUR_INSCRIPTIONS);

    // onglet 1 (même contexte, donc même session) : Léo se connecte par l'écran
    const onglet1 = await context.newPage();
    await ouvrirConnexion(onglet1);
    await saisir(onglet1, leo, MOT_DE_PASSE_BENEVOLE_CREE);
    await onglet1.getByTestId('bouton-connexion').click();
    await expect(onglet1).toHaveURL(`${ORIGINE}/benevole`);

    // onglet 2 : la validation répond 409 DEJA_CONNECTE, la session est relue puis la destination suit RG2
    await saisir(onglet2, leo, MOT_DE_PASSE_BENEVOLE_CREE);
    const reponse = onglet2.waitForResponse((r) => r.url().endsWith('/api/connexion') && r.request().method() === 'POST');
    await onglet2.getByTestId('bouton-connexion').click();
    const connexion = await reponse;
    expect(connexion.status()).toBe(409);
    expect(((await connexion.json()) as { code?: string }).code).toBe('DEJA_CONNECTE');

    await expect(onglet2).toHaveURL(`${ORIGINE}/benevole`);
    expect(accesRefuseTraverse(chemins), `chemins visités : ${chemins.join(' > ')}`).toHaveLength(0);
  });

  test('CA10 - après CA1, Précédent puis accès direct à /coureur/inscriptions mène à /acces-refuse, sans boucle de redirection', async ({ page, browser, playwright }) => {
    test.setTimeout(150_000);
    const { pageA, fermer } = await reconnexionApresPromotion(page, browser, playwright, 'benevole');
    try {
      await expect(pageA).toHaveURL(`${ORIGINE}/benevole`);

      // « Précédent » : l'indicateur de connexion n'est pas rejoué
      await pageA.goBack();
      await pageA.goto('/coureur/inscriptions');
      await expect(pageA).toHaveURL(`${ORIGINE}/acces-refuse`);

      // la page se stabilise : un rechargement reste sur /acces-refuse et le nombre de navigations ne croît pas
      const chemins = surveillerNavigations(pageA);
      await pageA.reload();
      await expect(pageA).toHaveURL(`${ORIGINE}/acces-refuse`);
      await pageA.waitForLoadState('networkidle');
      const apres = chemins.length;
      await pageA.waitForLoadState('networkidle');
      expect(chemins.length, `chemins visités : ${chemins.join(' > ')}`).toBe(apres);
      expect(chemins.at(-1)).toBe('/acces-refuse');
      await expect(pageA).toHaveURL(`${ORIGINE}/acces-refuse`);
    } finally {
      await fermer();
    }
  });
});
