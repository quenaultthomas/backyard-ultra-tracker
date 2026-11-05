import { expect, test, type Page, type Request } from '@playwright/test';
import {
  affecterBenevolesParApi,
  creerAdminParApi,
  creerBenevoleParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  connecterParApi,
} from './aide-admin';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, saisir } from './aide-connexion';
import { connecterCoureur, seDesinscrireParApi } from './aide-coureur';
import { ouvrirMenuCompte } from './aide-entete';
import { PROBLEME, avecApi, prefixeUnique } from './aide-recherche-coureurs';
import {
  MESSAGE_CSRF,
  MESSAGE_INTROUVABLE,
  MESSAGE_SERVICE_INDISPONIBLE,
  boutonPasser,
  chercher,
  confirmerPassage,
  creerCoureur,
  creerCourse,
  demarrer,
  estPassage,
  estPassageUrl,
  inscrire,
  ligneBenevole,
  ouvrirConfirmation,
  ouvrirEnAdmin,
  ouvrirEnMaster,
  resultatDe,
  t,
  texteConfirmation,
} from './aide-passage-benevole';

/*
 * Incrément 9.6b : « Passer en bénévole » depuis la recherche de la page Bénévoles (CA26 à CA29).
 * CA30 : accessibilité dans passage-en-benevole-accessibilite.spec.ts et adaptation de recherche-coureurs.spec.ts.
 * Les cas où le coureur a couru une Course TERMINEE (E4) : passage-en-benevole.horloge.spec.ts.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

async function nouvelAdmin(playwright: Parameters<typeof creerCoureur>[0]): Promise<string> {
  const nadia = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
  return nadia;
}

/** Appels hors lecture et lectures de listes émis par la page. */
function espionner(page: Page): { requetes: Request[] } {
  const requetes: Request[] = [];
  page.on('request', (r) => {
    if (new URL(r.url()).pathname.startsWith('/api/')) requetes.push(r);
  });
  return { requetes };
}

test.describe('Passer un coureur en bénévole', () => {
  test('CA26 - confirmation sans appel réseau, une seule confirmation à la fois, un seul POST, succès et affectation', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const p = prefixeUnique();
    const e1 = `${p}e1`;
    const benevoleExistant = `${p}bn`;
    const nadia = await nouvelAdmin(playwright);
    await creerCoureur(playwright, e1);
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevoleExistant));
    const nomCourse = nomCourseUnique();
    const courseId = await creerCourse(playwright, nomCourse);
    const { requetes } = espionner(page);

    await ouvrirEnAdmin(page, nadia, '/administration/benevoles', 'titre-benevoles');
    await chercher(page, e1);
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('1 coureur trouvé');
    await expect(boutonPasser(page, e1)).toHaveText('Passer en bénévole');

    // ouvrir : aucun appel réseau, texte exact, focus sur Annuler
    const avant = requetes.length;
    await ouvrirConfirmation(page, e1);
    expect(requetes.length).toBe(avant);
    await expect(t(page, 'benevole-promotion-confirmation-texte')).toHaveText(texteConfirmation(e1));
    await expect(t(page, 'benevole-promotion-bouton-confirmer')).toHaveText('Confirmer le passage en bénévole');
    await expect(t(page, 'benevole-promotion-bouton-annuler')).toHaveText('Annuler');

    // Annuler : fermée, aucun appel, focus rendu au bouton du résultat
    await t(page, 'benevole-promotion-bouton-annuler').click();
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await expect(boutonPasser(page, e1)).toBeFocused();
    expect(requetes.length).toBe(avant);

    // Échap ferme aussi et rend le focus
    await ouvrirConfirmation(page, e1);
    await page.keyboard.press('Escape');
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await expect(boutonPasser(page, e1)).toBeFocused();

    // une seule confirmation à la fois : « Supprimer » d'une ligne ferme celle du résultat, et inversement
    await ouvrirConfirmation(page, e1);
    await ligneBenevole(page, benevoleExistant).getByTestId('benevole-bouton-supprimer').click();
    await expect(ligneBenevole(page, benevoleExistant).getByTestId('benevole-suppression-confirmation')).toBeVisible();
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await boutonPasser(page, e1).click();
    await expect(t(page, 'benevole-promotion-confirmation')).toBeVisible();
    await expect(t(page, 'benevole-suppression-confirmation')).toHaveCount(0);

    // une nouvelle recherche ferme la confirmation ; la fermeture du panneau aussi
    await t(page, 'benevole-promotion-recherche').fill(`${e1}x`);
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await chercher(page, e1);
    await ouvrirConfirmation(page, e1);
    await t(page, 'benevole-promotion-bouton-fermer').click();
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await chercher(page, e1);
    await ouvrirConfirmation(page, e1);

    // Confirmer, avec un double clic : un seul POST, précédé de GET /api/csrf
    const posts: Request[] = [];
    page.on('request', (r) => {
      if (estPassage(r)) posts.push(r);
    });
    const depart = requetes.length;
    await t(page, 'benevole-promotion-bouton-confirmer').dblclick();
    await expect(t(page, 'benevole-message-action')).toHaveText(`Le coureur ${e1} est maintenant bénévole.`);
    expect(posts).toHaveLength(1);
    const chemins = requetes.slice(depart).map((r) => `${r.method()} ${new URL(r.url()).pathname}`);
    expect(chemins.indexOf('GET /api/csrf')).toBeGreaterThanOrEqual(0);
    expect(chemins.indexOf('GET /api/csrf')).toBeLessThan(chemins.findIndex((c) => c.startsWith('POST') && c.endsWith('/passage-benevole')));

    // après succès : confirmation fermée, E1 sorti des résultats, dans la liste, panneau ouvert, focus dans le champ
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await expect(resultatDe(page, e1)).toHaveCount(0);
    await expect(t(page, 'benevole-promotion-vide')).toBeVisible();
    await expect(ligneBenevole(page, e1)).toHaveCount(1);
    await expect(t(page, 'benevole-promotion')).toBeVisible();
    await expect(t(page, 'benevole-promotion-recherche')).toBeFocused();
    await expect(t(page, 'benevole-message-action')).toHaveAttribute('role', 'status');

    // la fiche d'une Course EN_PREPARATION propose E1 à l'affectation
    await page.goto(`/administration/courses/${courseId}`);
    await expect(t(page, 'fiche-titre')).toHaveText(nomCourse);
    await expect(t(page, 'fiche-ligne-benevole').filter({ hasText: e1 })).toHaveCount(1);
  });

  test('CA26 - sur la page Admins, l\'admin master voit les résultats sans bouton ; sur la page Bénévoles, l\'admin master a le bouton', async ({ page, playwright }) => {
    const p = prefixeUnique();
    const e1 = `${p}e1`;
    await creerCoureur(playwright, e1);
    await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
    await t(page, 'admin-bouton-promouvoir').click();
    await t(page, 'admin-promotion-recherche').fill(e1);
    await expect(t(page, 'admin-promotion-resultat')).toHaveText([e1]);
    await expect(t(page, 'admin-promotion-liste').getByRole('button')).toHaveCount(0);
    await expect(t(page, 'admin-promotion-liste').getByRole('link')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Passer en bénévole/ })).toHaveCount(0);

    await ouvrirEnMaster(page, '/administration/benevoles', 'titre-benevoles');
    await chercher(page, e1);
    await expect(boutonPasser(page, e1)).toBeVisible();
  });

  test('CA27 - refus 409 avec les noms de courses (préparation, en cours, pluriel, texte brut), puis succès après désinscription', async ({ page, playwright }) => {
    test.setTimeout(150_000);
    const p = prefixeUnique();
    const [e2, e3, e6, e7] = ['e2', 'e3', 'e6', 'e7'].map((s) => `${p}${s}`);
    const nadia = await nouvelAdmin(playwright);
    for (const pseudo of [e2, e3, e6, e7]) await creerCoureur(playwright, pseudo);

    const nomPrep = nomCourseUnique();
    const nomPrepB = nomCourseUnique();
    const nomHtml = `${nomCourseUnique('Cours')} <b>x</b>`;
    const nomEnCours = nomCourseUnique();
    const nomEnCoursB = nomCourseUnique();
    const [idPrep, idPrepB, idHtml, idEnCours, idEnCoursB] = [
      await creerCourse(playwright, nomPrep),
      await creerCourse(playwright, nomPrepB),
      await creerCourse(playwright, nomHtml),
      await creerCourse(playwright, nomEnCours, 0),
      await creerCourse(playwright, nomEnCoursB, 0),
    ];
    const inscriptionE2 = await inscrire(playwright, e2, idPrep);
    await inscrire(playwright, e3, idEnCours);
    await inscrire(playwright, e6, idPrepB);
    await inscrire(playwright, e6, idHtml);
    await inscrire(playwright, e7, idEnCours);
    await inscrire(playwright, e7, idEnCoursB);
    await demarrer(playwright, idEnCours);
    await demarrer(playwright, idEnCoursB);

    await ouvrirEnAdmin(page, nadia, '/administration/benevoles', 'titre-benevoles');
    const erreur = t(page, 'benevole-erreur-action');

    // E2 : une course non démarrée
    await chercher(page, e2);
    await ouvrirConfirmation(page, e2);
    await confirmerPassage(page);
    await expect(erreur).toHaveText(`Impossible : ce coureur est inscrit à la course « ${nomPrep} » qui n'a pas démarré. Il doit d'abord se désinscrire.`);
    await expect(erreur).toHaveAttribute('role', 'alert');
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await expect(resultatDe(page, e2)).toHaveCount(1);
    await expect(ligneBenevole(page, e2)).toHaveCount(0);
    await expect(t(page, 'benevole-message-action')).toHaveCount(0);

    // E6 : deux courses non démarrées, dont un nom avec du HTML affiché en texte
    await chercher(page, e6);
    await ouvrirConfirmation(page, e6);
    await confirmerPassage(page);
    await expect(erreur).toContainText(`« ${nomPrepB} »`);
    await expect(erreur).toContainText(`« ${nomHtml} »`);
    await expect(erreur).toContainText("Impossible : ce coureur est inscrit aux courses « ");
    await expect(erreur).toContainText("» qui n'ont pas démarré. Il doit d'abord se désinscrire.");
    await expect(erreur.locator('b')).toHaveCount(0);
    await expect(ligneBenevole(page, e6)).toHaveCount(0);

    // E3 : course en cours
    await chercher(page, e3);
    await ouvrirConfirmation(page, e3);
    await confirmerPassage(page);
    await expect(erreur).toHaveText(`Impossible : ce coureur participe à la course en cours « ${nomEnCours} ». Réessayez à la fin de la course.`);
    await expect(resultatDe(page, e3)).toHaveCount(1);
    await expect(ligneBenevole(page, e3)).toHaveCount(0);

    // E7 : deux courses en cours
    await chercher(page, e7);
    await ouvrirConfirmation(page, e7);
    await confirmerPassage(page);
    await expect(erreur).toContainText("Impossible : ce coureur participe aux courses en cours « ");
    await expect(erreur).toContainText(`« ${nomEnCours} »`);
    await expect(erreur).toContainText(`« ${nomEnCoursB} »`);
    await expect(erreur).toContainText('Réessayez à la fin des courses.');

    // E2 se désinscrit depuis son propre compte (second contexte), la même action réussit
    expect(await avecApi(playwright, (ctx) => seDesinscrireParApi(ctx, e2, inscriptionE2.id))).toBeLessThan(300);
    await chercher(page, e2);
    await ouvrirConfirmation(page, e2);
    await confirmerPassage(page);
    await expect(t(page, 'benevole-message-action')).toHaveText(`Le coureur ${e2} est maintenant bénévole.`);
    await expect(erreur).toHaveCount(0);
    await expect(ligneBenevole(page, e2)).toHaveCount(1);
    await expect(resultatDe(page, e2)).toHaveCount(0);
  });

  test('CA28 - erreurs simulées : 404, 403 CSRF, 500, 409 sans courses, messages effacés, 401, 403 accès refusé, coureur refusé', async ({ page, browser, playwright }) => {
    test.setTimeout(150_000);
    const p = prefixeUnique();
    const e1 = `${p}e1`;
    const nadia = await nouvelAdmin(playwright);
    await creerCoureur(playwright, e1);
    const alice = `${p}al`;
    await creerCoureur(playwright, alice);
    const { requetes } = espionner(page);
    const motif = estPassageUrl;

    await ouvrirEnAdmin(page, nadia, '/administration/benevoles', 'titre-benevoles');
    await chercher(page, e1);
    const erreur = t(page, 'benevole-erreur-action');
    const depuis = (n: number) => requetes.slice(n).map((r) => `${r.method()} ${new URL(r.url()).pathname}`);

    // 404 : message, recherche relancée
    await page.route(motif, (route) => route.fulfill(PROBLEME(404, 'COMPTE_INTROUVABLE')));
    let n = requetes.length;
    await ouvrirConfirmation(page, e1);
    await confirmerPassage(page);
    await expect(erreur).toHaveText(MESSAGE_INTROUVABLE);
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await expect.poll(() => depuis(n).filter((c) => c === 'GET /api/administration/coureurs').length).toBeGreaterThanOrEqual(1);

    // 403 CSRF : message, nouveau jeton, aucune liste rechargée ; et le message précédent est effacé pendant l'action
    await page.unroute(motif);
    await page.route(motif, (route) => route.fulfill(PROBLEME(403, 'CSRF_INVALIDE')));
    await ouvrirConfirmation(page, e1);
    n = requetes.length;
    await confirmerPassage(page);
    await expect(erreur).toHaveText(MESSAGE_CSRF);
    await expect(erreur).not.toContainText(MESSAGE_INTROUVABLE);
    await expect(t(page, 'benevole-promotion-confirmation')).toHaveCount(0);
    await expect.poll(() => depuis(n).filter((c) => c === 'GET /api/csrf').length).toBeGreaterThanOrEqual(2);
    expect(depuis(n)).not.toContain('GET /api/administration/benevoles');
    expect(depuis(n)).not.toContain('GET /api/administration/coureurs');

    // 500 : réponse retardée, l'ancien message est déjà effacé pendant l'envoi
    await page.unroute(motif);
    let liberer!: () => void;
    const feu = new Promise<void>((resolve) => (liberer = resolve));
    await page.route(motif, async (route) => {
      await feu;
      await route.fulfill(PROBLEME(500, 'ERREUR_INTERNE')).catch(() => undefined);
    });
    await ouvrirConfirmation(page, e1);
    await confirmerPassage(page);
    await expect(t(page, 'benevole-promotion-bouton-confirmer')).toBeDisabled();
    await expect(t(page, 'benevole-promotion-bouton-annuler')).toBeDisabled();
    await expect(erreur).toHaveCount(0);
    liberer();
    await expect(erreur).toHaveText(MESSAGE_SERVICE_INDISPONIBLE);
    await expect(boutonPasser(page, e1)).toBeFocused();

    // 409 sans courses
    await page.unroute(motif);
    await page.route(motif, (route) => route.fulfill(PROBLEME(409, 'COUREUR_INSCRIT_COURSE_EN_PREPARATION')));
    await ouvrirConfirmation(page, e1);
    await confirmerPassage(page);
    await expect(erreur).toHaveText('Impossible : ce coureur est inscrit à une course.');
    await page.unroute(motif);

    // 403 ACCES_REFUSE
    await page.route(motif, (route) => route.fulfill(PROBLEME(403, 'ACCES_REFUSE')));
    await ouvrirConfirmation(page, e1);
    await confirmerPassage(page);
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await page.unroute(motif);

    // 401
    await ouvrirEnAdmin(page, nadia, '/administration/benevoles', 'titre-benevoles');
    await chercher(page, e1);
    await page.route(motif, (route) => route.fulfill(PROBLEME(401, 'NON_AUTHENTIFIE')));
    await ouvrirConfirmation(page, e1);
    await confirmerPassage(page);
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fbenevoles$/);
    await page.unroute(motif);

    // un coureur sur la page : accès refusé
    const contexte = await browser.newContext();
    try {
      const pageAlice = await contexte.newPage();
      await connecterParApi(pageAlice.request, alice, MOT_DE_PASSE);
      await pageAlice.goto('/administration/benevoles');
      await expect(pageAlice).toHaveURL(/\/acces-refuse$/);
      await expect(t(pageAlice, 'benevole-bouton-promouvoir')).toHaveCount(0);
    } finally {
      await contexte.close();
    }
  });

  test('CA29 - le coureur promu est déconnecté, se reconnecte en bénévole, est affecté, puis supprimé', async ({ page, browser, playwright }) => {
    test.setTimeout(150_000);
    const p = prefixeUnique();
    const e5 = `${p}e5`;
    const nadia = await nouvelAdmin(playwright);
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, e5));
    const nomCourse = nomCourseUnique();
    const idCourse = await creerCourse(playwright, nomCourse);

    // fenêtre A : E5 connecté
    const contexteA = await browser.newContext({ serviceWorkers: 'block' });
    try {
      const pageA = await contexteA.newPage();
      await connecterCoureur(pageA, e5);

      // Nadia le promeut
      await ouvrirEnAdmin(page, nadia, '/administration/benevoles', 'titre-benevoles');
      await chercher(page, e5);
      await ouvrirConfirmation(page, e5);
      await confirmerPassage(page);
      await expect(t(page, 'benevole-message-action')).toHaveText(`Le coureur ${e5} est maintenant bénévole.`);

      // au clic suivant dans A : retour à la connexion
      await ouvrirMenuCompte(pageA);
      await pageA.getByTestId('menu-lien-mes-inscriptions').click();
      await expect(pageA).toHaveURL(/\/connexion\?retour=/);

      // reconnexion (page de connexion sans `retour`) avec les mêmes identifiants : espace bénévole, aucune course
      await ouvrirConnexion(pageA);
      await saisir(pageA, e5, MOT_DE_PASSE);
      await pageA.getByTestId('bouton-connexion').click();
      await expect(pageA).toHaveURL(/\/benevole$/);
      await expect(pageA.getByTestId('benevole-ligne-course')).toHaveCount(0);

      // Nadia l'affecte à une course, il la voit
      await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, idCourse, [e5]));
      await pageA.reload();
      await expect(pageA.getByTestId('benevole-ligne-course').filter({ hasText: nomCourse })).toHaveCount(1);
      await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, idCourse, []));

      // E5 jamais inscrit : sa suppression par Nadia fait disparaître la ligne
      await page.reload();
      await expect(ligneBenevole(page, e5)).toHaveCount(1);
      await ligneBenevole(page, e5).getByTestId('benevole-bouton-supprimer').click();
      await ligneBenevole(page, e5).getByTestId('benevole-suppression-bouton-confirmer').click();
      await expect(ligneBenevole(page, e5)).toHaveCount(0);
    } finally {
      await contexteA.close();
    }
  });
});
