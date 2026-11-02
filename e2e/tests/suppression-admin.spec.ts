import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs, type Request, type Route } from '@playwright/test';
import { ouvrirConnexion, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  PSEUDO_ADMIN_MASTER,
  connecterAdminMaster,
  creerAdminParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
} from './aide-admin';
import { changerActivationParApi } from './aide-activation-comptes';
import { ouvrirFormulaire } from './aide-formulaire';

/*
 * Incrément 9.4 : suppression d'un admin par l'admin master (CA12 à CA14).
 * Les listes étant globales, chaque test crée ses propres admins jetables à pseudo unique et ne touche
 * jamais aux comptes de démo. Le refus « dernier admin actif » est simulé par interception (la base E2E
 * contient de nombreux admins) ; il est vérifié réellement en intégration.
 */
type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const MESSAGE_ECHEC = 'Pseudo ou mot de passe incorrect.';
const FOCUS = 'rgb(168, 67, 0)';
const CIBLE_DELETE = '**/api/administration/admins/*';

const probleme = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code }),
});

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

async function connecter(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await ouvrirConnexion(page, '/connexion');
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

async function ouvrirListe(page: Page): Promise<void> {
  await page.goto('/administration/admins');
  await expect(page.getByTestId('titre-admins')).toBeVisible();
  await expect(page.getByTestId('liste-admins')).toBeVisible();
}

const ligne = (page: Page, pseudo: string) => page.getByTestId('ligne-admin').filter({ hasText: pseudo });

const estDelete = (r: Request) => r.method() === 'DELETE' && /\/api\/administration\/admins\/[^/]+$/.test(new URL(r.url()).pathname);

const texteConfirmation = (pseudo: string) =>
  `Supprimer définitivement l'administrateur ${pseudo} ? Son compte sera supprimé, son pseudo pourra être réutilisé et ses sessions seront fermées. Cette action est irréversible.`;

async function ouvrirConfirmationSuppression(page: Page, pseudo: string): Promise<void> {
  await ligne(page, pseudo).getByTestId('admin-bouton-supprimer').click();
  await expect(ligne(page, pseudo).getByTestId('admin-suppression-confirmation')).toBeVisible();
}

test.describe('Suppression d\'un administrateur', () => {
  test('CA12 - l\'admin master supprime un admin actif ou désactivé après confirmation, son pseudo est libéré et ses sessions fermées', async ({ page, browser, playwright }) => {
    const a = pseudoAdminUnique();
    const b = pseudoAdminUnique();
    const c = pseudoAdminUnique();
    const nadia = pseudoAdminUnique();
    for (const p of [a, b, c, nadia]) await avecApi(playwright, (ctx) => creerAdminParApi(ctx, p));
    await avecApi(playwright, (ctx) => changerActivationParApi(ctx, 'admins', b, 'desactivation'));

    // session ouverte de C (second contexte)
    const contexteC = await browser.newContext();
    const pageC = await contexteC.newPage();
    await connecter(pageC, c, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pageC).toHaveURL(/\/administration$/);

    const deletes: Request[] = [];
    const appelsApi: Request[] = [];
    page.on('request', (r) => {
      if (estDelete(r)) deletes.push(r);
      if (new URL(r.url()).pathname.startsWith('/api/')) appelsApi.push(r);
    });
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);

    // un bouton Supprimer par ligne, compte désactivé compris, aucune ligne pour l'admin master
    for (const p of [a, b, c, nadia]) await expect(ligne(page, p).getByTestId('admin-bouton-supprimer')).toBeVisible();
    await expect(ligne(page, b).getByTestId('admin-etat')).toHaveText('Désactivé');
    expect(await page.getByTestId('ligne-admin').count()).toBe(await page.getByTestId('admin-bouton-supprimer').count());
    await expect(page.getByTestId('liste-admins').getByText(PSEUDO_ADMIN_MASTER, { exact: true })).toHaveCount(0);

    // ouvrir : aucun appel réseau, texte, focus sur Annuler
    const ligneA = ligne(page, a);
    const avant = appelsApi.length;
    await ouvrirConfirmationSuppression(page, a);
    expect(appelsApi.length).toBe(avant);
    await expect(ligneA.getByTestId('admin-suppression-confirmation-texte')).toHaveText(texteConfirmation(a));
    await expect(ligneA.getByTestId('admin-suppression-bouton-confirmer')).toHaveText('Confirmer la suppression');
    await expect(ligneA.getByTestId('admin-suppression-bouton-annuler')).toBeFocused();

    // annuler : fermé sans appel, focus rendu à Supprimer
    await ligneA.getByTestId('admin-suppression-bouton-annuler').click();
    await expect(page.getByTestId('admin-suppression-confirmation')).toHaveCount(0);
    await expect(ligneA.getByTestId('admin-bouton-supprimer')).toBeFocused();
    expect(appelsApi.length).toBe(avant);

    // Échap ferme aussi
    await ouvrirConfirmationSuppression(page, a);
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('admin-suppression-confirmation')).toHaveCount(0);
    expect(appelsApi.length).toBe(avant);

    // une seule confirmation ouverte à la fois, toutes actions confondues
    await ouvrirConfirmationSuppression(page, a);
    await ligneA.getByTestId('admin-bouton-desactiver').click();
    await expect(ligneA.getByTestId('admin-confirmation')).toBeVisible();
    await expect(page.getByTestId('admin-suppression-confirmation')).toHaveCount(0);
    await expect(page.getByTestId('admin-confirmation')).toHaveCount(1);
    await ligneA.getByTestId('admin-bouton-supprimer').click();
    await expect(page.getByTestId('admin-confirmation')).toHaveCount(0);
    await expect(page.getByTestId('admin-suppression-confirmation')).toHaveCount(1);
    expect(appelsApi.length).toBe(avant);

    // confirmer (double clic : un seul DELETE)
    await ligneA.getByTestId('admin-suppression-bouton-confirmer').dblclick();
    await expect(page.getByTestId('admin-message-action')).toHaveText(`L'administrateur ${a} a été supprimé.`);
    await expect(page.getByTestId('admin-suppression-confirmation')).toHaveCount(0);
    await expect(ligne(page, a)).toHaveCount(0);
    expect(deletes).toHaveLength(1);

    // un compte désactivé se supprime de la même façon
    await ouvrirConfirmationSuppression(page, b);
    await ligne(page, b).getByTestId('admin-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('admin-message-action')).toHaveText(`L'administrateur ${b} a été supprimé.`);
    await expect(ligne(page, b)).toHaveCount(0);
    expect(deletes).toHaveLength(2);

    // la connexion de A est refusée dans une fenêtre privée
    const contexteNeuf = await browser.newContext();
    const pageNeuve = await contexteNeuf.newPage();
    await connecter(pageNeuve, a, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pageNeuve.getByTestId('erreur-generale')).toHaveText(MESSAGE_ECHEC);
    await expect(pageNeuve).toHaveURL(/\/connexion/);
    await contexteNeuf.close();

    // le pseudo est libéré : « Nouvel admin » accepte A
    await ouvrirFormulaire(page, 'admin');
    await page.getByTestId('admin-champ-pseudo').fill(a);
    await page.getByTestId('admin-champ-mot-de-passe').fill('mot-de-passe-jetable-2');
    await page.getByTestId('admin-champ-confirmation').fill('mot-de-passe-jetable-2');
    await page.getByTestId('admin-bouton-creer').click();
    await expect(page.getByTestId('admin-message-succes')).toHaveText(`L'administrateur ${a} a été créé.`);
    await expect(ligne(page, a)).toHaveCount(1);
    await expect(ligne(page, a).getByTestId('admin-etat')).toHaveText('Actif');

    // la session ouverte de C est fermée à sa suppression
    await ouvrirConfirmationSuppression(page, c);
    await ligne(page, c).getByTestId('admin-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('admin-message-action')).toHaveText(`L'administrateur ${c} a été supprimé.`);
    await expect(ligne(page, c)).toHaveCount(0);
    await pageC.goto('/administration');
    await expect(pageC).toHaveURL(/\/connexion/);
    await contexteC.close();

    // un admin simple n'accède pas à l'écran, et son DELETE direct est refusé
    const listeMaster = await page.request.get('/api/administration/admins');
    expect(listeMaster.status()).toBe(200);
    const idNadia = ((await listeMaster.json()) as Array<{ id: string; pseudo: string }>).find((x) => x.pseudo === nadia)!.id;
    const contexteNadia = await browser.newContext();
    const pageNadia = await contexteNadia.newPage();
    await connecter(pageNadia, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pageNadia).toHaveURL(/\/administration$/);
    await pageNadia.goto('/administration/admins');
    await expect(pageNadia).toHaveURL(/\/acces-refuse$/);
    expect((await pageNadia.request.get('/api/csrf')).status()).toBe(204);
    const jeton = (await contexteNadia.cookies()).find((x) => x.name === 'XSRF-TOKEN')?.value;
    expect(jeton).toBeTruthy();
    const refus = await pageNadia.request.delete(`/api/administration/admins/${idNadia}`, { headers: { 'X-XSRF-TOKEN': jeton! } });
    expect(refus.status()).toBe(403);
    expect((await refus.json()).code).toBe('ACCES_REFUSE');
    await contexteNadia.close();
    await ouvrirListe(page);
    await expect(ligne(page, nadia)).toHaveCount(1);
  });

  test('CA13 - les erreurs de suppression sont traduites à l\'écran, le pseudo est affiché en texte', async ({ page, playwright }) => {
    const x = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, x));

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);
    const ligneX = ligne(page, x);

    let lecturesListe = 0;
    let lecturesCsrf = 0;
    page.on('request', (r) => {
      if (r.method() !== 'GET') return;
      const chemin = new URL(r.url()).pathname;
      if (chemin === '/api/administration/admins') lecturesListe++;
      if (chemin === '/api/csrf') lecturesCsrf++;
    });

    const cas: Array<{ nom: string; reponse: ReturnType<typeof probleme>; message: string; rechargement: boolean | null; nouveauJeton: boolean }> = [
      { nom: 'dernier admin actif', reponse: probleme(409, 'DERNIER_ADMIN_ACTIF'), message: "Impossible : c'est le dernier administrateur actif.", rechargement: true, nouveauJeton: false },
      { nom: 'admin master', reponse: probleme(409, 'ADMIN_MASTER_NON_SUPPRIMABLE'), message: "Impossible : l'administrateur principal ne peut pas être supprimé.", rechargement: true, nouveauJeton: false },
      { nom: 'compte inexistant', reponse: probleme(404, 'COMPTE_INTROUVABLE'), message: "Ce compte n'existe plus.", rechargement: true, nouveauJeton: false },
      { nom: 'jeton CSRF', reponse: probleme(403, 'CSRF_INVALIDE'), message: 'La page a expiré, veuillez réessayer.', rechargement: false, nouveauJeton: true },
      { nom: 'erreur serveur', reponse: probleme(500, 'ERREUR_INTERNE'), message: 'Service indisponible, veuillez réessayer plus tard.', rechargement: null, nouveauJeton: false },
    ];
    for (const cas1 of cas) {
      const gestionnaire = (route: Route) => (route.request().method() === 'DELETE' ? route.fulfill(cas1.reponse) : route.fallback());
      await page.route(CIBLE_DELETE, gestionnaire);
      await ouvrirListe(page);
      const listeAvant = lecturesListe;
      const csrfAvant = lecturesCsrf;
      await ouvrirConfirmationSuppression(page, x);
      await ligneX.getByTestId('admin-suppression-bouton-confirmer').click();
      await expect(page.getByTestId('admin-erreur-action'), cas1.nom).toHaveText(cas1.message);
      await expect(page.getByTestId('admin-suppression-confirmation'), cas1.nom).toHaveCount(0);
      await expect(ligneX, cas1.nom).toHaveCount(1);
      if (cas1.rechargement === true) await expect.poll(() => lecturesListe, cas1.nom).toBeGreaterThan(listeAvant);
      else if (cas1.rechargement === false) expect(lecturesListe, `${cas1.nom} : liste non rechargée`).toBe(listeAvant);
      if (cas1.nouveauJeton) await expect.poll(() => lecturesCsrf, cas1.nom).toBeGreaterThan(csrfAvant);
      await page.unroute(CIBLE_DELETE, gestionnaire);
    }

    // un pseudo est affiché en texte, jamais interprété comme du HTML
    const html = '<b>x</b>';
    const gestionnaireListe = async (route: Route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const reponse = await route.fetch();
      const liste = (await reponse.json()) as Array<{ pseudo: string }>;
      const cible = liste.find((e) => e.pseudo === x);
      if (cible) cible.pseudo = `${x}${html}`;
      return route.fulfill({ response: reponse, json: liste });
    };
    await page.route('**/api/administration/admins', gestionnaireListe);
    await ouvrirListe(page);
    await ouvrirConfirmationSuppression(page, x);
    await expect(ligneX.getByTestId('admin-suppression-confirmation-texte')).toHaveText(texteConfirmation(`${x}${html}`));
    await expect(ligneX.getByTestId('admin-suppression-confirmation-texte').locator('b')).toHaveCount(0);
    await page.unroute('**/api/administration/admins', gestionnaireListe);

    // les messages précédents sont effacés à chaque nouvelle action : succès simulé, l'erreur disparaît
    await ouvrirListe(page);
    await page.route(CIBLE_DELETE, (route) => (route.request().method() === 'DELETE' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.fallback()));
    await ouvrirConfirmationSuppression(page, x);
    await ligneX.getByTestId('admin-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('admin-erreur-action')).toBeVisible();
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    await page.route(CIBLE_DELETE, (route) => (route.request().method() === 'DELETE' ? route.fulfill({ status: 204 }) : route.fallback()));
    await ouvrirConfirmationSuppression(page, x);
    await ligneX.getByTestId('admin-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('admin-message-action')).toHaveText(`L'administrateur ${x} a été supprimé.`);
    await expect(page.getByTestId('admin-erreur-action')).toHaveCount(0);
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    // ... et inversement : une erreur efface le message de succès
    await page.route(CIBLE_DELETE, (route) => (route.request().method() === 'DELETE' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.fallback()));
    await ouvrirConfirmationSuppression(page, x);
    await ligneX.getByTestId('admin-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('admin-erreur-action')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('admin-message-action')).toHaveCount(0);
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    // 403 ACCES_REFUSE puis 401 : redirections
    await page.route(CIBLE_DELETE, (route) => (route.request().method() === 'DELETE' ? route.fulfill(probleme(403, 'ACCES_REFUSE')) : route.fallback()));
    await ouvrirConfirmationSuppression(page, x);
    await ligneX.getByTestId('admin-suppression-bouton-confirmer').click();
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    await ouvrirListe(page);
    await page.route(CIBLE_DELETE, (route) => (route.request().method() === 'DELETE' ? route.fulfill(probleme(401, 'NON_AUTHENTIFIE')) : route.fallback()));
    await ouvrirConfirmationSuppression(page, x);
    await ligneX.getByTestId('admin-suppression-bouton-confirmer').click();
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fadmins$/);
    await page.unrouteAll({ behavior: 'ignoreErrors' });
  });

  test('CA14 - sur la page Admins, l\'ordre de Tab d\'une ligne est Désactiver (ou Réactiver) puis Supprimer, contour de focus entier', async ({ page, playwright }) => {
    const actif = pseudoAdminUnique();
    const inactif = pseudoAdminUnique();
    for (const p of [actif, inactif]) await avecApi(playwright, (ctx) => creerAdminParApi(ctx, p));
    await avecApi(playwright, (ctx) => changerActivationParApi(ctx, 'admins', inactif, 'desactivation'));

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);

    for (const [pseudo, premier] of [[actif, 'admin-bouton-desactiver'], [inactif, 'admin-bouton-reactiver']] as const) {
      const l = ligne(page, pseudo);
      await l.getByTestId(premier).focus();
      await page.keyboard.press('Tab');
      const supprimer = l.getByTestId('admin-bouton-supprimer');
      await expect(supprimer, `${pseudo} : Tab depuis ${premier}`).toBeFocused();
      const contour = await supprimer.evaluate((el) => {
        const cs = getComputedStyle(el);
        const ancetres: string[] = [];
        for (let n = el.parentElement; n && n.tagName !== 'MAIN' && n !== document.body; n = n.parentElement) {
          const s = getComputedStyle(n);
          if (s.overflowX !== 'visible' || s.overflowY !== 'visible') ancetres.push(`${n.tagName.toLowerCase()}.${n.className}`);
        }
        return { style: cs.outlineStyle, largeur: cs.outlineWidth, couleur: cs.outlineColor, ancetres };
      });
      expect(contour.style).toBe('solid');
      expect(contour.largeur).toBe('3px');
      expect(contour.couleur).toBe(FOCUS);
      expect(contour.ancetres).toEqual([]);
      expect((await supprimer.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      // Maj+Tab revient au bouton précédent
      await page.keyboard.press('Shift+Tab');
      await expect(l.getByTestId(premier)).toBeFocused();
    }

    // à 320 px, le bouton reste entier dans la fenêtre, sans défilement horizontal
    await page.setViewportSize({ width: 320, height: 640 });
    const supprimer = ligne(page, actif).getByTestId('admin-bouton-supprimer');
    await supprimer.scrollIntoViewIfNeeded();
    const boite = (await supprimer.boundingBox())!;
    expect(boite.x).toBeGreaterThanOrEqual(0);
    expect(boite.x + boite.width).toBeLessThanOrEqual(320);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  });
});
