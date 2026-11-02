import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { creerCompteParApi, MOT_DE_PASSE, ouvrirConnexion, pseudoUnique, saisir, seConnecter } from './aide-connexion';
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
import { ouvrirFormulaire } from './aide-formulaire';

/*
 * Incrément 9.3 : suppression d'un bénévole (CA15 à CA17). CA18 est porté par l'adaptation de
 * theme-ecrans-benevole-comptes.spec.ts CA9 (prédicat estBoutonLigne) et par la non-régression des suites existantes.
 * Les listes étant globales, chaque test crée ses propres bénévoles à pseudo unique et ne touche
 * jamais aux comptes de démo. Les courses démarrées sont datées du jour à Paris (dateDansJours(0)).
 */
type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const MESSAGE_ECHEC = 'Pseudo ou mot de passe incorrect.';

const probleme = (status: number, code: string, extra: Record<string, unknown> = {}) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code, ...extra }),
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

async function ouvrirListe(page: Page): Promise<void> {
  await page.goto('/administration/benevoles');
  await expect(page.getByTestId('titre-benevoles')).toBeVisible();
  await expect(page.getByTestId('liste-benevoles')).toBeVisible();
}

const ligne = (page: Page, pseudo: string) => page.getByTestId('ligne-benevole').filter({ hasText: pseudo });

const estDelete = (r: Request) => r.method() === 'DELETE' && /\/api\/administration\/benevoles\/[^/]+$/.test(new URL(r.url()).pathname);

const ligneFiche = (page: Page, pseudo: string) => page.getByTestId('fiche-ligne-benevole').filter({ hasText: pseudo });

async function ouvrirFiche(page: Page, course: { id: string; nom: string }): Promise<void> {
  await page.goto(`/administration/courses/${course.id}`);
  await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);
  await expect(page.getByTestId('fiche-bouton-enregistrer')).toBeVisible();
}

/** Course avec les bénévoles donnés affectés ; du jour avec un coureur inscrit et démarrée si `demarree`. */
async function creerCourse(
  playwright: PlaywrightLib,
  options: { nom?: string; benevoles?: string[]; demarree?: boolean } = {},
): Promise<{ id: string; nom: string }> {
  const nom = options.nom ?? nomCourseUnique();
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

async function ouvrirConfirmationSuppression(page: Page, pseudo: string): Promise<void> {
  await ligne(page, pseudo).getByTestId('benevole-bouton-supprimer').click();
  await expect(ligne(page, pseudo).getByTestId('benevole-suppression-confirmation')).toBeVisible();
}

test.describe('Suppression d\'un bénévole', () => {
  test('CA15 - un admin supprime un bénévole après confirmation, son pseudo est libéré et ses affectations retirées', async ({ page, browser, playwright }) => {
    const nadia = pseudoAdminUnique();
    const j1 = pseudoBenevoleUnique();
    const j2 = pseudoBenevoleUnique();
    const j3 = pseudoBenevoleUnique();
    const autre = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    for (const p of [j1, j2, j3, autre]) await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, p));
    await avecApi(playwright, (ctx) => changerActivationParApi(ctx, 'benevoles', j2, 'desactivation'));
    const courseX = await creerCourse(playwright, { benevoles: [j3, autre] });

    // session de J3 ouverte au préalable (second contexte)
    const contexteJ3 = await browser.newContext();
    const pageJ3 = await contexteJ3.newPage();
    await connecter(pageJ3, j3, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(pageJ3).toHaveURL(/\/benevole$/);

    const deletes: Request[] = [];
    const appels: Request[] = [];
    page.on('request', (r) => {
      if (estDelete(r)) deletes.push(r);
      if (r.method() !== 'GET') appels.push(r);
    });
    await connecter(page, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);

    // un bouton Supprimer par ligne, compte désactivé compris
    for (const p of [j1, j2, j3]) await expect(ligne(page, p).getByTestId('benevole-bouton-supprimer')).toBeVisible();
    await expect(ligne(page, j2).getByTestId('benevole-etat')).toHaveText('Désactivé');
    expect(await page.getByTestId('ligne-benevole').count()).toBe(await page.getByTestId('benevole-bouton-supprimer').count());

    // ouvrir : aucun appel réseau, texte de la confirmation, focus sur Annuler
    const avant = appels.length;
    const ligneJ1 = ligne(page, j1);
    await ouvrirConfirmationSuppression(page, j1);
    expect(appels.length).toBe(avant);
    await expect(ligneJ1.getByTestId('benevole-suppression-confirmation-texte')).toHaveText(
      `Supprimer définitivement le bénévole ${j1} ? Son compte sera supprimé et son pseudo pourra être réutilisé. Il sera retiré de toutes ses courses, y compris terminées. Les passages qu'il a scannés sont conservés. Cette action est irréversible.`,
    );
    await expect(ligneJ1.getByTestId('benevole-suppression-bouton-confirmer')).toHaveText('Confirmer la suppression');
    await expect(ligneJ1.getByTestId('benevole-suppression-bouton-annuler')).toHaveText('Annuler');
    await expect(ligneJ1.getByTestId('benevole-suppression-bouton-annuler')).toBeFocused();

    // annuler : fermé sans appel, focus rendu à Supprimer
    await ligneJ1.getByTestId('benevole-suppression-bouton-annuler').click();
    await expect(page.getByTestId('benevole-suppression-confirmation')).toHaveCount(0);
    await expect(ligneJ1.getByTestId('benevole-bouton-supprimer')).toBeFocused();
    expect(appels.length).toBe(avant);

    // Échap ferme aussi
    await ouvrirConfirmationSuppression(page, j1);
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('benevole-suppression-confirmation')).toHaveCount(0);
    expect(appels.length).toBe(avant);

    // une seule confirmation ouverte à la fois, toutes actions confondues
    await ouvrirConfirmationSuppression(page, j1);
    await ligneJ1.getByTestId('benevole-bouton-desactiver').click();
    await expect(ligneJ1.getByTestId('benevole-confirmation')).toBeVisible();
    await expect(page.getByTestId('benevole-suppression-confirmation')).toHaveCount(0);
    await expect(page.getByTestId('benevole-confirmation')).toHaveCount(1);
    await ligneJ1.getByTestId('benevole-bouton-supprimer').click();
    await expect(page.getByTestId('benevole-confirmation')).toHaveCount(0);
    await expect(page.getByTestId('benevole-suppression-confirmation')).toHaveCount(1);
    expect(appels.length).toBe(avant);

    // confirmer (double clic : un seul DELETE)
    await ligneJ1.getByTestId('benevole-suppression-bouton-confirmer').dblclick();
    await expect(page.getByTestId('benevole-message-action')).toHaveText(`Le bénévole ${j1} a été supprimé.`);
    await expect(page.getByTestId('benevole-suppression-confirmation')).toHaveCount(0);
    await expect(ligne(page, j1)).toHaveCount(0);
    await expect(page.getByTestId('benevole-bouton-nouveau')).toBeFocused();
    expect(deletes).toHaveLength(1);

    // un compte désactivé se supprime de la même façon
    await ouvrirConfirmationSuppression(page, j2);
    await ligne(page, j2).getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-message-action')).toHaveText(`Le bénévole ${j2} a été supprimé.`);
    await expect(ligne(page, j2)).toHaveCount(0);
    expect(deletes).toHaveLength(2);

    // J3 affecté à la course X : fiche avant / après
    await ouvrirFiche(page, courseX);
    await expect(ligneFiche(page, j3)).toHaveCount(1);
    await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('2 bénévoles affectés');
    await ouvrirListe(page);
    await ouvrirConfirmationSuppression(page, j3);
    await ligne(page, j3).getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-message-action')).toHaveText(`Le bénévole ${j3} a été supprimé.`);
    await expect(ligne(page, j3)).toHaveCount(0);
    await ouvrirFiche(page, courseX);
    await expect(ligneFiche(page, j3)).toHaveCount(0);
    await expect(ligneFiche(page, autre)).toHaveCount(1);
    await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('1 bénévole affecté');

    // la connexion de J1 est refusée dans une fenêtre privée
    const contexteNeuf = await browser.newContext();
    const pageNeuve = await contexteNeuf.newPage();
    await connecter(pageNeuve, j1, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(pageNeuve.getByTestId('erreur-generale')).toHaveText(MESSAGE_ECHEC);
    await expect(pageNeuve).toHaveURL(/\/connexion/);
    await contexteNeuf.close();

    // la session ouverte de J3 est fermée
    await pageJ3.goto('/benevole');
    await expect(pageJ3).toHaveURL(/\/connexion/);
    await contexteJ3.close();

    // le pseudo est libéré : « Nouveau bénévole » accepte J1
    await ouvrirListe(page);
    await ouvrirFormulaire(page, 'benevole');
    await page.getByTestId('benevole-champ-pseudo').fill(j1);
    await page.getByTestId('benevole-champ-mot-de-passe').fill('mot-de-passe-jetable-2');
    await page.getByTestId('benevole-champ-confirmation').fill('mot-de-passe-jetable-2');
    await page.getByTestId('benevole-bouton-creer').click();
    await expect(page.getByTestId('benevole-message-succes')).toHaveText(`Le bénévole ${j1} a été créé.`);
    await expect(ligne(page, j1)).toHaveCount(1);
    await expect(ligne(page, j1).getByTestId('benevole-etat')).toHaveText('Actif');
  });

  test('CA16 - la suppression d\'un bénévole affecté à une course en cours est refusée avec le nom de la course', async ({ page, playwright }) => {
    const nadia = pseudoAdminUnique();
    const j4 = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, j4));
    const enCours = await creerCourse(playwright, { nom: nomCourseUnique('Course en cours J4'), benevoles: [j4], demarree: true });
    const preparation = await creerCourse(playwright, { benevoles: [j4] });

    await connecter(page, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);

    await ouvrirConfirmationSuppression(page, j4);
    await ligne(page, j4).getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-erreur-action')).toHaveText(
      `Impossible : ce bénévole est affecté à la course en cours « ${enCours.nom} ». Retirez-le de cette course avant de le supprimer.`,
    );
    await expect(page.getByTestId('benevole-suppression-confirmation')).toHaveCount(0);
    await expect(ligne(page, j4)).toHaveCount(1);
    await expect(page.getByTestId('benevole-message-action')).toHaveCount(0);

    // la course en préparation le montre toujours coché (rien n'a été modifié)
    await ouvrirFiche(page, preparation);
    await expect(ligneFiche(page, j4).getByTestId('fiche-benevole-case')).toBeChecked();

    // retrait de la course en cours par sa fiche, puis suppression
    await ouvrirFiche(page, enCours);
    await expect(ligneFiche(page, j4).getByTestId('fiche-benevole-case')).toBeChecked();
    await ligneFiche(page, j4).getByTestId('fiche-benevole-case').uncheck();
    await page.getByTestId('fiche-bouton-enregistrer').click();
    await expect(page.getByTestId('fiche-message-succes')).toBeVisible();

    await ouvrirListe(page);
    await ouvrirConfirmationSuppression(page, j4);
    await ligne(page, j4).getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-message-action')).toHaveText(`Le bénévole ${j4} a été supprimé.`);
    await expect(ligne(page, j4)).toHaveCount(0);
    await expect(page.getByTestId('benevole-erreur-action')).toHaveCount(0);
  });

  test('CA16 - avec deux courses en cours, le message est au pluriel avec les deux noms', async ({ page, playwright }) => {
    const nadia = pseudoAdminUnique();
    const j5 = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, j5));
    const suffixe = nomCourseUnique('');
    const a = await creerCourse(playwright, { nom: `Alpha J5${suffixe}`, benevoles: [j5], demarree: true });
    const b = await creerCourse(playwright, { nom: `Zèbre J5${suffixe}`, benevoles: [j5], demarree: true });

    await connecter(page, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);
    await ouvrirConfirmationSuppression(page, j5);
    await ligne(page, j5).getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-erreur-action')).toHaveText(
      `Impossible : ce bénévole est affecté aux courses en cours « ${a.nom} », « ${b.nom} ». Retirez-le de ces courses avant de le supprimer.`,
    );
    await expect(ligne(page, j5)).toHaveCount(1);
  });

  test('CA17 - les erreurs de suppression sont traduites à l\'écran et les droits sont respectés', async ({ page, browser, playwright }) => {
    const nadia = pseudoAdminUnique();
    const j6 = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, j6));

    await connecter(page, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);
    const ligneJ6 = ligne(page, j6);

    const cibleDelete = '**/api/administration/benevoles/*';
    let lecturesListe = 0;
    let lecturesCsrf = 0;
    page.on('request', (r) => {
      if (r.method() !== 'GET') return;
      const chemin = new URL(r.url()).pathname;
      if (chemin === '/api/administration/benevoles') lecturesListe++;
      if (chemin === '/api/csrf') lecturesCsrf++;
    });

    const cas: Array<{ nom: string; reponse: ReturnType<typeof probleme>; message: string; rechargement: boolean; nouveauJeton: boolean }> = [
      { nom: 'compte inexistant', reponse: probleme(404, 'COMPTE_INTROUVABLE'), message: "Ce compte n'existe plus.", rechargement: true, nouveauJeton: false },
      { nom: 'jeton CSRF', reponse: probleme(403, 'CSRF_INVALIDE'), message: 'La page a expiré, veuillez réessayer.', rechargement: false, nouveauJeton: true },
      { nom: 'erreur serveur', reponse: probleme(500, 'ERREUR_INTERNE'), message: 'Service indisponible, veuillez réessayer plus tard.', rechargement: true, nouveauJeton: false },
      {
        nom: 'conflit sans courses',
        reponse: probleme(409, 'BENEVOLE_AFFECTE_COURSE_EN_COURS'),
        message: 'Impossible : ce bénévole est affecté à une course en cours.',
        rechargement: true,
        nouveauJeton: false,
      },
    ];
    for (const c of cas) {
      const gestionnaire = (route: import('@playwright/test').Route) =>
        route.request().method() === 'DELETE' ? route.fulfill(c.reponse) : route.fallback();
      await page.route(cibleDelete, gestionnaire);
      await ouvrirListe(page);
      const listeAvant = lecturesListe;
      const csrfAvant = lecturesCsrf;
      await ouvrirConfirmationSuppression(page, j6);
      await ligneJ6.getByTestId('benevole-suppression-bouton-confirmer').click();
      await expect(page.getByTestId('benevole-erreur-action'), c.nom).toHaveText(c.message);
      await expect(page.getByTestId('benevole-suppression-confirmation'), c.nom).toHaveCount(0);
      await expect(ligneJ6, c.nom).toHaveCount(1);
      if (c.rechargement) await expect.poll(() => lecturesListe, c.nom).toBeGreaterThan(listeAvant);
      else {
        expect(lecturesListe, `${c.nom} : liste non rechargée`).toBe(listeAvant);
      }
      if (c.nouveauJeton) await expect.poll(() => lecturesCsrf, c.nom).toBeGreaterThan(csrfAvant);
      await page.unroute(cibleDelete, gestionnaire);
    }

    // un nom de course est affiché en texte, jamais interprété comme du HTML
    const html = '<b>x</b> Course piégée';
    const gestionnaireHtml = (route: import('@playwright/test').Route) =>
      route.request().method() === 'DELETE'
        ? route.fulfill(probleme(409, 'BENEVOLE_AFFECTE_COURSE_EN_COURS', { courses: [{ id: '00000000-0000-0000-0000-000000000001', nom: html }] }))
        : route.fallback();
    await page.route(cibleDelete, gestionnaireHtml);
    await ouvrirConfirmationSuppression(page, j6);
    await ligneJ6.getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-erreur-action')).toHaveText(
      `Impossible : ce bénévole est affecté à la course en cours « ${html} ». Retirez-le de cette course avant de le supprimer.`,
    );
    await expect(page.getByTestId('benevole-erreur-action').locator('b')).toHaveCount(0);
    await page.unroute(cibleDelete, gestionnaireHtml);

    // les messages précédents sont effacés à chaque nouvelle action : succès simulé, l'erreur disparaît
    await page.route(cibleDelete, (route) => (route.request().method() === 'DELETE' ? route.fulfill({ status: 204 }) : route.fallback()));
    await ouvrirConfirmationSuppression(page, j6);
    await ligneJ6.getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-message-action')).toHaveText(`Le bénévole ${j6} a été supprimé.`);
    await expect(page.getByTestId('benevole-erreur-action')).toHaveCount(0);
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    // ... et inversement : une erreur efface le message de succès
    await page.route(cibleDelete, (route) => (route.request().method() === 'DELETE' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.fallback()));
    await ouvrirConfirmationSuppression(page, j6);
    await ligneJ6.getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page.getByTestId('benevole-erreur-action')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('benevole-message-action')).toHaveCount(0);
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    // 403 ACCES_REFUSE puis 401 : redirections
    await page.route(cibleDelete, (route) => (route.request().method() === 'DELETE' ? route.fulfill(probleme(403, 'ACCES_REFUSE')) : route.fallback()));
    await ouvrirConfirmationSuppression(page, j6);
    await ligneJ6.getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    await ouvrirListe(page);
    await page.route(cibleDelete, (route) => (route.request().method() === 'DELETE' ? route.fulfill(probleme(401, 'NON_AUTHENTIFIE')) : route.fallback()));
    await ouvrirConfirmationSuppression(page, j6);
    await ligneJ6.getByTestId('benevole-suppression-bouton-confirmer').click();
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fbenevoles$/);
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    // 9.4 : l'écran des administrateurs propose un bouton de suppression par ligne (RG18.4)
    const contexteMaster = await browser.newContext();
    const pageMaster = await contexteMaster.newPage();
    await connecterAdminMaster(pageMaster);
    await expect(pageMaster).toHaveURL(/\/administration$/);
    await pageMaster.goto('/administration/admins');
    await expect(pageMaster.getByTestId('titre-admins')).toBeVisible();
    await expect(pageMaster.getByTestId('liste-admins')).toBeVisible();
    await expect(pageMaster.getByTestId('ligne-admin').first()).toBeVisible();
    const lignesAdmin = pageMaster.getByTestId('ligne-admin');
    const nombreLignes = await lignesAdmin.count();
    expect(nombreLignes).toBeGreaterThan(0);
    await expect(pageMaster.getByTestId('admin-bouton-supprimer')).toHaveCount(nombreLignes);
    for (let i = 0; i < nombreLignes; i++) await expect(lignesAdmin.nth(i).getByTestId('admin-bouton-supprimer')).toHaveCount(1);
    await contexteMaster.close();

    // un coureur n'accède pas à l'écran
    const coureur = pseudoUnique('coureur');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, coureur));
    const contexteCoureur = await browser.newContext();
    const pageCoureur = await contexteCoureur.newPage();
    await seConnecter(pageCoureur, coureur, MOT_DE_PASSE);
    await pageCoureur.goto('/administration/benevoles');
    await expect(pageCoureur).toHaveURL(/\/acces-refuse$/);
    await expect(pageCoureur.getByTestId('benevole-bouton-supprimer')).toHaveCount(0);
    await contexteCoureur.close();
  });
});
