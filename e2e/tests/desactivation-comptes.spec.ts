import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs, type Request, type Route } from '@playwright/test';
import { creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  affecterBenevolesParApi,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  dateDansJours,
  demarrerCourseParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { sinscrireParApi } from './aide-coureur';
import { changerActivationParApi } from './aide-activation-comptes';

/*
 * Incrément 8.1 : désactivation et réactivation des comptes ADMIN et BENEVOLE (CA19 à CA21).
 * Les listes étant globales, chaque test crée ses propres comptes à pseudo unique et ne touche
 * jamais aux comptes de démo. Les courses démarrées sont datées du jour à Paris (dateDansJours(0)).
 * Le refus « dernier administrateur actif » n'est pas atteignable sur une base partagée avec d'autres
 * admins actifs : il est simulé par interception du POST.
 */
type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const MESSAGE_ECHEC = 'Pseudo ou mot de passe incorrect.';

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

async function connecter(page: Page, pseudo: string, motDePasse: string, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

async function ouvrirListe(page: Page, ecran: 'admins' | 'benevoles'): Promise<void> {
  await page.goto(`/administration/${ecran}`);
  await expect(page.getByTestId(`titre-${ecran}`)).toBeVisible();
  await expect(page.getByTestId(`liste-${ecran}`)).toBeVisible();
}

const ligne = (page: Page, prefixe: 'admin' | 'benevole', pseudo: string) =>
  page.getByTestId(`ligne-${prefixe}`).filter({ hasText: pseudo });

const estPostActivation = (r: Request) =>
  r.method() === 'POST' && /\/api\/administration\/(admins|benevoles)\/[^/]+\/(desactivation|reactivation)$/.test(new URL(r.url()).pathname);

/** Course du jour avec un coureur inscrit et les bénévoles donnés affectés, démarrée ou non. */
async function creerCourse(
  playwright: PlaywrightLib,
  options: { benevoles?: string[]; demarree?: boolean } = {},
): Promise<{ id: string; nom: string }> {
  const nom = nomCourseUnique();
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date: dateDansJours(options.demarree ? 0 : 30) })));
  const id = c['id'] as string;
  if (options.benevoles?.length) await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, id, options.benevoles!));
  if (options.demarree) {
    const coureur = pseudoUnique('coureur');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, coureur));
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, coureur, id));
    await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, id));
  }
  return { id, nom };
}

async function ouvrirFiche(page: Page, course: { id: string; nom: string }): Promise<void> {
  await page.goto(`/administration/courses/${course.id}`);
  await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);
  await expect(page.getByTestId('fiche-bouton-enregistrer')).toBeVisible();
}

const ligneFiche = (page: Page, pseudo: string) => page.getByTestId('fiche-ligne-benevole').filter({ hasText: pseudo });

test.describe('Désactivation des comptes', () => {
  test('CA19 - un admin désactive puis réactive un bénévole depuis la gestion des bénévoles', async ({ page, browser, playwright }) => {
    const nadia = pseudoAdminUnique();
    const leo = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, leo));
    const preparation = await creerCourse(playwright, { benevoles: [leo] });
    const demarree = await creerCourse(playwright, { benevoles: [leo], demarree: true });

    // session de Léo ouverte avant la désactivation (second contexte)
    const contexteLeo = await browser.newContext();
    const pageLeo = await contexteLeo.newPage();
    await connecter(pageLeo, leo, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(pageLeo).toHaveURL(/\/benevole$/);

    const posts: Request[] = [];
    page.on('request', (r) => {
      if (estPostActivation(r)) posts.push(r);
    });
    await connecter(page, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page, 'benevoles');
    const ligneLeo = ligne(page, 'benevole', leo);
    await expect(ligneLeo.getByTestId('benevole-etat')).toHaveText('Actif');
    await expect(ligneLeo.getByTestId('benevole-bouton-desactiver')).toBeVisible();
    await expect(ligneLeo.getByTestId('benevole-bouton-reactiver')).toHaveCount(0);

    // ouvrir puis annuler : aucun appel réseau
    await ligneLeo.getByTestId('benevole-bouton-desactiver').click();
    await expect(ligneLeo.getByTestId('benevole-confirmation')).toBeVisible();
    await expect(ligneLeo.getByTestId('benevole-confirmation-texte')).toHaveText(
      `Désactiver le bénévole ${leo} ? Il ne pourra plus se connecter et sera retiré des courses qui n'ont pas démarré. Ses passages et ses affectations aux courses démarrées sont conservés.`,
    );
    await ligneLeo.getByTestId('benevole-bouton-annuler').click();
    await expect(page.getByTestId('benevole-confirmation')).toHaveCount(0);
    await expect(ligneLeo.getByTestId('benevole-etat')).toHaveText('Actif');
    expect(posts).toHaveLength(0);

    // confirmer (double clic : un seul POST)
    await ligneLeo.getByTestId('benevole-bouton-desactiver').click();
    await expect(ligneLeo.getByTestId('benevole-bouton-confirmer')).toHaveText('Confirmer la désactivation');
    await ligneLeo.getByTestId('benevole-bouton-confirmer').dblclick();
    await expect(page.getByTestId('benevole-message-action')).toHaveText(`Le compte ${leo} a été désactivé.`);
    await expect(page.getByTestId('benevole-confirmation')).toHaveCount(0);
    await expect(ligneLeo.getByTestId('benevole-etat')).toHaveText('Désactivé');
    await expect(ligneLeo).toHaveAttribute('data-etat', 'desactive');
    await expect(ligneLeo.getByTestId('benevole-bouton-reactiver')).toBeVisible();
    await expect(ligneLeo.getByTestId('benevole-bouton-desactiver')).toHaveCount(0);
    expect(posts).toHaveLength(1);

    // fiches des courses : retiré de la course en préparation, conservé (désactivé) sur la course démarrée
    await ouvrirFiche(page, preparation);
    await expect(ligneFiche(page, leo)).toHaveCount(0);
    await ouvrirFiche(page, demarree);
    await expect(ligneFiche(page, leo)).toContainText(`${leo} (désactivé)`);
    await expect(ligneFiche(page, leo).getByTestId('fiche-benevole-case')).toBeChecked();
    await expect(ligneFiche(page, leo).getByTestId('fiche-benevole-desactive')).toBeVisible();

    // connexion refusée dans une fenêtre privée, session préalable fermée
    const contexteNeuf = await browser.newContext();
    const pageNeuve = await contexteNeuf.newPage();
    await connecter(pageNeuve, leo, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(pageNeuve.getByTestId('erreur-generale')).toHaveText(MESSAGE_ECHEC);
    await expect(pageNeuve).toHaveURL(/\/connexion/);
    await contexteNeuf.close();
    await pageLeo.goto('/benevole');
    await expect(pageLeo).toHaveURL(/\/connexion/);

    // réactivation
    await ouvrirListe(page, 'benevoles');
    await ligneLeo.getByTestId('benevole-bouton-reactiver').click();
    await expect(ligneLeo.getByTestId('benevole-confirmation-texte')).toHaveText(
      `Réactiver le compte ${leo} ? Il pourra de nouveau se connecter avec son mot de passe.`,
    );
    await expect(ligneLeo.getByTestId('benevole-bouton-confirmer')).toHaveText('Confirmer la réactivation');
    await ligneLeo.getByTestId('benevole-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-message-action')).toHaveText(`Le compte ${leo} a été réactivé.`);
    await expect(ligneLeo.getByTestId('benevole-etat')).toHaveText('Actif');
    expect(posts).toHaveLength(2);

    await connecter(pageLeo, leo, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(pageLeo).toHaveURL(/\/benevole$/);
    await expect(pageLeo.getByTestId('entete-pseudo')).toHaveText(leo);
    await contexteLeo.close();
  });

  test('CA20 - l\'admin master désactive et réactive un admin, avec les refus visibles à l\'écran', async ({ page, browser, playwright }) => {
    const paul = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, paul));

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page, 'admins');
    const lignePaul = ligne(page, 'admin', paul);
    await expect(lignePaul.getByTestId('admin-etat')).toHaveText('Actif');

    // annuler : aucun changement
    await lignePaul.getByTestId('admin-bouton-desactiver').click();
    await expect(lignePaul.getByTestId('admin-confirmation-texte')).toHaveText(
      `Désactiver l'administrateur ${paul} ? Il ne pourra plus se connecter. Ses sessions seront fermées.`,
    );
    await lignePaul.getByTestId('admin-bouton-annuler').click();
    await expect(page.getByTestId('admin-confirmation')).toHaveCount(0);
    await expect(lignePaul.getByTestId('admin-etat')).toHaveText('Actif');

    // désactiver
    await lignePaul.getByTestId('admin-bouton-desactiver').click();
    await lignePaul.getByTestId('admin-bouton-confirmer').click();
    await expect(page.getByTestId('admin-message-action')).toHaveText(`Le compte ${paul} a été désactivé.`);
    await expect(lignePaul.getByTestId('admin-etat')).toHaveText('Désactivé');
    await expect(lignePaul.getByTestId('admin-bouton-reactiver')).toBeVisible();

    // la connexion de Paul échoue
    const contexte = await browser.newContext();
    const pagePaul = await contexte.newPage();
    await connecter(pagePaul, paul, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pagePaul.getByTestId('erreur-generale')).toHaveText(MESSAGE_ECHEC);
    await expect(pagePaul).toHaveURL(/\/connexion/);

    // réactiver : la connexion réussit avec le mot de passe inchangé
    await lignePaul.getByTestId('admin-bouton-reactiver').click();
    await lignePaul.getByTestId('admin-bouton-confirmer').click();
    await expect(page.getByTestId('admin-message-action')).toHaveText(`Le compte ${paul} a été réactivé.`);
    await expect(lignePaul.getByTestId('admin-etat')).toHaveText('Actif');
    await connecter(pagePaul, paul, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pagePaul).toHaveURL(/\/administration$/);

    // un admin n'accède pas à la gestion des administrateurs, mais à celle des bénévoles
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    await pagePaul.goto('/administration/admins');
    await expect(pagePaul).toHaveURL(/\/acces-refuse$/);
    await expect(pagePaul.getByTestId('admin-bouton-desactiver')).toHaveCount(0);
    await ouvrirListe(pagePaul, 'benevoles');
    await expect(ligne(pagePaul, 'benevole', benevole).getByTestId('benevole-bouton-desactiver')).toBeVisible();
    await contexte.close();

    // refus simulés : le serveur décide, l'écran affiche le message prévu
    const cas: Array<{ nom: string; statut: number; code: string; message: string; rechargement: boolean }> = [
      { nom: 'dernier admin actif', statut: 409, code: 'DERNIER_ADMIN_ACTIF', message: "Impossible : c'est le dernier administrateur actif.", rechargement: true },
      { nom: 'admin master', statut: 409, code: 'ADMIN_MASTER_NON_DESACTIVABLE', message: "L'administrateur principal ne peut pas être désactivé.", rechargement: true },
      { nom: 'soi-même', statut: 409, code: 'AUTO_DESACTIVATION_INTERDITE', message: 'Vous ne pouvez pas désactiver votre propre compte.', rechargement: true },
      { nom: 'compte inexistant', statut: 404, code: 'COMPTE_INTROUVABLE', message: "Ce compte n'existe plus.", rechargement: true },
      { nom: 'jeton CSRF', statut: 403, code: 'CSRF_INVALIDE', message: 'La page a expiré, veuillez réessayer.', rechargement: false },
      { nom: 'erreur serveur', statut: 500, code: 'ERREUR_INTERNE', message: 'Service indisponible, veuillez réessayer plus tard.', rechargement: true },
    ];
    await ouvrirListe(page, 'admins');
    for (const c of cas) {
      const gestionnaire = (route: Route) => route.fulfill(probleme(c.statut, c.code));
      await page.route('**/api/administration/admins/*/desactivation', gestionnaire);
      let lectures = 0;
      const compter = (r: Request) => {
        if (r.method() === 'GET' && new URL(r.url()).pathname === '/api/administration/admins') lectures++;
      };
      page.on('request', compter);
      await lignePaul.getByTestId('admin-bouton-desactiver').click();
      await lignePaul.getByTestId('admin-bouton-confirmer').click();
      await expect(page.getByTestId('admin-erreur-action'), c.nom).toHaveText(c.message);
      await expect(page.getByTestId('admin-confirmation'), c.nom).toHaveCount(0);
      await expect(lignePaul.getByTestId('admin-etat'), c.nom).toHaveText('Actif');
      if (c.rechargement) await expect.poll(() => lectures, c.nom).toBeGreaterThanOrEqual(1);
      else expect(lectures, c.nom).toBe(0);
      page.off('request', compter);
      await page.unroute('**/api/administration/admins/*/desactivation', gestionnaire);
    }

    // 403 ACCES_REFUSE et 401 : redirections
    await page.route('**/api/administration/admins/*/desactivation', (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await lignePaul.getByTestId('admin-bouton-desactiver').click();
    await lignePaul.getByTestId('admin-bouton-confirmer').click();
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    await ouvrirListe(page, 'admins');
    await page.route('**/api/administration/admins/*/desactivation', (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await lignePaul.getByTestId('admin-bouton-desactiver').click();
    await lignePaul.getByTestId('admin-bouton-confirmer').click();
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fadmins$/);
  });

  test('CA20 - un admin désactivé par ailleurs apparaît « Désactivé » avec le bouton Réactiver', async ({ page, playwright }) => {
    const paul = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, paul));
    await avecApi(playwright, (ctx) => changerActivationParApi(ctx, 'admins', paul, 'desactivation'));
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page, 'admins');
    const lignePaul = ligne(page, 'admin', paul);
    await expect(lignePaul.getByTestId('admin-etat')).toHaveText('Désactivé');
    await expect(lignePaul.getByTestId('admin-bouton-reactiver')).toBeVisible();
    await expect(lignePaul.getByTestId('admin-bouton-desactiver')).toHaveCount(0);
  });

  test('CA21 - la fiche d\'une course ne propose que les bénévoles actifs et les désactivés déjà affectés', async ({ page, playwright }) => {
    const leo = pseudoBenevoleUnique();
    const marc = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, leo));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, marc));
    await avecApi(playwright, (ctx) => changerActivationParApi(ctx, 'benevoles', marc, 'desactivation'));

    const coureur = pseudoUnique('coureur');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, coureur));
    const autre = await creerCourse(playwright);
    // course datée d'aujourd'hui (Paris) avec un inscrit : condition du démarrage
    const nom = nomCourseUnique();
    const aDemarrer = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date: dateDansJours(0) })));
    const idX = aDemarrer['id'] as string;
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, coureur, idX));
    const x = { id: idX, nom };

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirFiche(page, x);
    await expect(ligneFiche(page, leo)).toHaveCount(1);
    await expect(ligneFiche(page, marc)).toHaveCount(0);
    await expect(ligneFiche(page, leo).getByTestId('fiche-benevole-desactive')).toHaveCount(0);

    await ligneFiche(page, leo).getByTestId('fiche-benevole-case').check();
    await page.getByTestId('fiche-bouton-enregistrer').click();
    await expect(page.getByTestId('fiche-message-succes')).toBeVisible();
    await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('1 bénévole affecté');

    // démarrage puis désactivation : le bénévole reste affecté, signalé « désactivé », décochable
    await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, idX));
    await avecApi(playwright, (ctx) => changerActivationParApi(ctx, 'benevoles', leo, 'desactivation'));
    await page.reload();
    await expect(page.getByTestId('fiche-titre')).toHaveText(nom);
    await expect(ligneFiche(page, leo)).toContainText(`${leo} (désactivé)`);
    await expect(ligneFiche(page, leo).getByTestId('fiche-benevole-desactive')).toBeVisible();
    await expect(ligneFiche(page, leo).getByTestId('fiche-benevole-case')).toBeChecked();
    await expect(ligneFiche(page, marc)).toHaveCount(0);
    await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('1 bénévole affecté');
    await ligneFiche(page, leo).getByTestId('fiche-benevole-case').uncheck();
    await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('0 bénévole affecté');

    // 409 BENEVOLE_DESACTIVE simulé sur une autre course : message et liste rechargée
    await ouvrirFiche(page, autre);
    let lectures = 0;
    page.on('request', (r) => {
      if (r.method() === 'GET' && new URL(r.url()).pathname === '/api/administration/benevoles') lectures++;
    });
    await page.route(`**/api/administration/courses/${autre.id}/benevoles`, (route) =>
      route.request().method() === 'PUT' ? route.fulfill(probleme(409, 'BENEVOLE_DESACTIVE')) : route.continue(),
    );
    await page.getByTestId('fiche-benevole-case').first().check();
    await page.getByTestId('fiche-bouton-enregistrer').click();
    await expect(page.getByTestId('fiche-erreur')).toHaveText('Un des bénévoles choisis est désactivé. Rechargez la page.');
    await expect.poll(() => lectures).toBeGreaterThanOrEqual(1);
  });
});
