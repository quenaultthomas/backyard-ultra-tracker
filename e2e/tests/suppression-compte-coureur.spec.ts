import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { ouvrirMenuVisiteur } from './aide-entete';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir, seConnecter } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { inscriptionExisteEnBase, placerStatutCourseEnBase, sinscrireParApi } from './aide-coureur';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const MAX = 50;
const FAUX = 'mauvais-mot-de-passe-1';
const estSuppression = (r: Request) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/comptes/moi/suppression';
const MOTIF_SUPPRESSION = '**/api/comptes/moi/suppression';

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

async function creerCourse(playwright: PlaywrightLib, prefixe: string): Promise<{ id: string; nom: string }> {
  const nom = nomCourseUnique(prefixe);
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, nombreMaxParticipants: MAX })));
  return { id: c['id'] as string, nom };
}

async function creerCoureur(playwright: PlaywrightLib, prefixe: string): Promise<string> {
  const pseudo = pseudoUnique(prefixe);
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  return pseudo;
}

async function ouvrirMonCompte(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  await page.getByTestId('lien-mon-compte').click();
  await expect(page).toHaveURL(/\/mon-compte$/);
  await expect(page.getByTestId('titre-mon-compte')).toBeVisible();
}

async function ouvrirConfirmation(page: Page): Promise<void> {
  await page.getByTestId('mon-compte-bouton-supprimer').click();
  await expect(page.getByTestId('mon-compte-confirmation-suppression')).toBeVisible();
}

async function confirmerAvec(page: Page, motDePasse: string): Promise<void> {
  await page.getByTestId('mon-compte-champ-suppression-mot-de-passe').fill(motDePasse);
  await page.getByTestId('mon-compte-bouton-confirmer-suppression').click();
}

async function postManuel(page: Page): Promise<number> {
  await page.request.get('/api/csrf');
  const jeton = (await page.request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  const reponse = await page.request.post('/api/comptes/moi/suppression', {
    headers: { 'X-XSRF-TOKEN': jeton! },
    data: { motDePasseActuel: 'peu-importe-12345' },
  });
  return reponse.status();
}

const probleme = (status: number, code: string, extra: Record<string, unknown> = {}) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code, ...extra }),
});

test.describe('Supprimer son compte coureur', () => {
  test('CA13 - la coureuse supprime son compte : inscriptions en préparation annulées, résultats conservés, pseudo libéré', async ({ page, browser, playwright }) => {
    const pseudo = pseudoUnique('Zoé');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
    const courseA = await creerCourse(playwright, 'A');
    const courseB = await creerCourse(playwright, 'B');
    const insA = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, courseA.id));
    const insB = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, courseB.id));
    expect([insA.dossard, insB.dossard]).toEqual([1, 1]);
    placerStatutCourseEnBase(courseB.id, 'EN_COURS');

    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));

    await ouvrirMonCompte(page, pseudo, MOT_DE_PASSE);
    await expect(page.getByTestId('mon-compte-suppression')).toBeVisible();
    await expect(page.getByTestId('mon-compte-bouton-supprimer')).toBeVisible();
    await expect(page.getByTestId('mon-compte-confirmation-suppression')).toHaveCount(0);

    const appels: string[] = [];
    const surRequete = (r: Request) => {
      if (new URL(r.url()).pathname.startsWith('/api/')) appels.push(`${r.method()} ${new URL(r.url()).pathname}`);
    };
    page.on('request', surRequete);

    // Ouverture / annulation : aucun appel réseau
    await ouvrirConfirmation(page);
    await page.getByTestId('mon-compte-champ-suppression-mot-de-passe').fill('quelque-chose');
    await page.getByTestId('mon-compte-bouton-annuler-suppression').click();
    await expect(page.getByTestId('mon-compte-confirmation-suppression')).toHaveCount(0);
    await ouvrirConfirmation(page);
    await expect(page.getByTestId('mon-compte-champ-suppression-mot-de-passe')).toHaveValue('');

    // Mot de passe vide : message, aucun appel
    await page.getByTestId('mon-compte-bouton-confirmer-suppression').click();
    await expect(page.getByTestId('mon-compte-erreur-suppression-mot-de-passe')).toHaveText('Le mot de passe est obligatoire.');
    expect(appels).toEqual([]);

    // Mauvais mot de passe
    await confirmerAvec(page, FAUX);
    await expect(page.getByTestId('mon-compte-erreur-suppression-mot-de-passe')).toHaveText('Le mot de passe est incorrect.');
    await expect(page.getByTestId('mon-compte-confirmation-suppression')).toBeVisible();
    expect(inscriptionExisteEnBase(insA.id)).toBe(true);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);

    // Bon mot de passe, double clic : un seul POST
    appels.length = 0;
    await page.getByTestId('mon-compte-champ-suppression-mot-de-passe').fill(MOT_DE_PASSE);
    await page.getByTestId('mon-compte-bouton-confirmer-suppression').dblclick();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('message-compte-supprime')).toHaveText('Votre compte a été supprimé.');
    await expect(page.getByTestId('message-deconnexion')).toHaveCount(0);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    expect(appels.filter((a) => a === 'POST /api/comptes/moi/suppression')).toHaveLength(1);
    page.off('request', surRequete);

    // Connexion impossible avec l'ancien mot de passe
    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page.getByTestId('erreur-generale')).toHaveText('Pseudo ou mot de passe incorrect.');
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);

    // Base : inscription en préparation annulée, inscription en cours conservée
    expect(inscriptionExisteEnBase(insA.id)).toBe(false);
    expect(inscriptionExisteEnBase(insB.id)).toBe(true);

    // Vue de l'admin
    const contexteAdmin = await browser.newContext();
    try {
      const pa = await contexteAdmin.newPage();
      await ouvrirConnexion(pa);
      await saisir(pa, admin, MOT_DE_PASSE_ADMIN_CREE);
      await pa.getByTestId('bouton-connexion').click();
      await expect(pa).toHaveURL(/\/administration$/);

      await pa.goto(`/administration/courses/${courseA.id}`);
      await expect(pa.getByTestId('fiche-titre')).toHaveText(courseA.nom);
      await expect(pa.getByTestId('fiche-inscrits-compteur')).toHaveText(`0 inscrit sur ${MAX}`);
      await expect(pa.getByTestId('fiche-inscrits-vide')).toHaveText('Aucun inscrit pour le moment.');

      await pa.goto(`/administration/courses/${courseB.id}`);
      await expect(pa.getByTestId('fiche-titre')).toHaveText(courseB.nom);
      const lignes = pa.getByTestId('fiche-inscrits-ligne');
      await expect(lignes).toHaveCount(1);
      await expect(lignes.first().getByTestId('fiche-inscrit-pseudo')).toHaveText('Coureur anonyme');
      await expect(lignes.first().getByTestId('fiche-inscrit-dossard')).toHaveText('1');
      await expect(lignes.first().getByTestId('fiche-inscrit-statut')).toHaveText('En course');
    } finally {
      await contexteAdmin.close();
    }

    // Pseudo libéré : nouveau compte sans inscription
    await page.goto('/creer-compte');
    await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
    await page.getByTestId('champ-pseudo').fill(pseudo);
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
    await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('message-succes')).toContainText(`Compte créé pour ${pseudo}.`);
    await seConnecter(page, pseudo);
    await page.getByTestId('lien-mes-inscriptions').click();
    await expect(page.getByTestId('inscriptions-vide')).toHaveText("Vous n'êtes inscrit à aucune course.");
  });

  test('CA14 - la section est absente pour les autres rôles et les erreurs de suppression sont gérées', async ({ page, browser, playwright }) => {
    // Rôles autres que coureur : section absente, appel manuel refusé en 403
    const admin = pseudoAdminUnique();
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    const ctxAdmin = await browser.newContext();
    const ctxBenevole = await browser.newContext();
    const ctxMaster = await browser.newContext();
    try {
      const cas: Array<[Page, () => Promise<void>]> = [];
      const pAdmin = await ctxAdmin.newPage();
      cas.push([pAdmin, async () => {
        await ouvrirConnexion(pAdmin);
        await saisir(pAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
        await pAdmin.getByTestId('bouton-connexion').click();
      }]);
      const pBenevole = await ctxBenevole.newPage();
      cas.push([pBenevole, async () => {
        await ouvrirConnexion(pBenevole);
        await saisir(pBenevole, benevole, MOT_DE_PASSE_BENEVOLE_CREE);
        await pBenevole.getByTestId('bouton-connexion').click();
      }]);
      const pMaster = await ctxMaster.newPage();
      cas.push([pMaster, async () => { await connecterAdminMaster(pMaster); }]);

      for (const [p, connecter] of cas) {
        await connecter();
        await expect(p.getByTestId('lien-mon-compte')).toBeVisible();
        await p.getByTestId('lien-mon-compte').click();
        await expect(p).toHaveURL(/\/mon-compte$/);
        await expect(p.getByTestId('titre-mon-compte')).toBeVisible();
        await expect(p.getByTestId('mon-compte-suppression')).toHaveCount(0);
        expect(await postManuel(p)).toBe(403);
      }
    } finally {
      await ctxAdmin.close();
      await ctxBenevole.close();
      await ctxMaster.close();
    }

    // Erreurs côté coureur (réponses interceptées : le compte n'est jamais supprimé)
    const pseudo = await creerCoureur(playwright, 'Eva');
    await ouvrirMonCompte(page, pseudo, MOT_DE_PASSE);
    await ouvrirConfirmation(page);

    const secret = 'secret-unique-a-ne-pas-afficher';
    await page.route(MOTIF_SUPPRESSION, (route) => route.fulfill(probleme(500, 'ERREUR_INTERNE', { type: 'about:blank', title: 'Erreur interne', detail: 'Une erreur inattendue est survenue. Réessayez plus tard.', instance: '/api/comptes/moi/suppression' })));
    await confirmerAvec(page, secret);
    await expect(page.getByTestId('mon-compte-erreur-suppression')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('mon-compte-confirmation-suppression')).toBeVisible();
    await expect(page.getByTestId('mon-compte-champ-suppression-mot-de-passe')).toHaveValue('');
    expect(await page.content()).not.toContain(secret);

    await page.unroute(MOTIF_SUPPRESSION);
    await page.route(MOTIF_SUPPRESSION, (route) => route.fulfill(probleme(403, 'CSRF_INVALIDE')));
    await confirmerAvec(page, secret);
    await expect(page.getByTestId('mon-compte-erreur-suppression')).toHaveText('La page a expiré, veuillez réessayer.');
    await expect(page.getByTestId('mon-compte-champ-suppression-mot-de-passe')).toHaveValue('');

    await page.unroute(MOTIF_SUPPRESSION);
    await page.route(MOTIF_SUPPRESSION, (route) => route.fulfill(probleme(429, 'TENTATIVES_EXCESSIVES')));
    await confirmerAvec(page, secret);
    await expect(page.getByTestId('mon-compte-erreur-suppression')).toHaveText(/^\s*Trop de tentatives\. Réessayez plus tard\.\s*$/);
    expect(await page.content()).not.toContain(secret);

    await page.unroute(MOTIF_SUPPRESSION);
    await page.route(MOTIF_SUPPRESSION, (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await confirmerAvec(page, secret);
    await expect(page).toHaveURL(/\/acces-refuse$/);

    // 401 : retour à la connexion avec retour vers /mon-compte
    await page.unroute(MOTIF_SUPPRESSION);
    await page.goto('/mon-compte');
    await expect(page.getByTestId('titre-mon-compte')).toBeVisible();
    await ouvrirConfirmation(page);
    await page.route(MOTIF_SUPPRESSION, (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await confirmerAvec(page, secret);
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fmon-compte$/);
  });

  test('CA14 - la suppression ferme les sessions des autres appareils', async ({ browser, playwright }) => {
    const pseudo = await creerCoureur(playwright, 'Lou');
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    try {
      const p1 = await ctx1.newPage();
      const p2 = await ctx2.newPage();
      await ouvrirMonCompte(p1, pseudo, MOT_DE_PASSE);
      await ouvrirMonCompte(p2, pseudo, MOT_DE_PASSE);

      await ouvrirConfirmation(p1);
      await confirmerAvec(p1, MOT_DE_PASSE);
      await expect(p1).toHaveURL(/\/connexion$/);
      await expect(p1.getByTestId('message-compte-supprime')).toBeVisible();

      await p2.getByTestId('lien-mes-inscriptions').click();
      await expect(p2).toHaveURL(/\/connexion/);
      await expect(p2.getByTestId('entete-pseudo')).toHaveCount(0);
    } finally {
      await ctx1.close();
      await ctx2.close();
    }
  });

  test('CA15 - le message de suppression ne s\'affiche ni à la première visite ni après une simple déconnexion', async ({ page, playwright }) => {
    await ouvrirConnexion(page);
    await expect(page.getByTestId('message-compte-supprime')).toHaveCount(0);
    await expect(page.getByTestId('message-deconnexion')).toHaveCount(0);

    const pseudo = await creerCoureur(playwright, 'Ian');
    await seConnecter(page, pseudo);
    await page.getByTestId('bouton-deconnexion').click();
    await expect(page.getByTestId('bouton-menu')).toBeVisible();
    await ouvrirMenuVisiteur(page);
    await page.getByTestId('menu-lien-se-connecter').click();
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await expect(page.getByTestId('message-compte-supprime')).toHaveCount(0);
  });
});
