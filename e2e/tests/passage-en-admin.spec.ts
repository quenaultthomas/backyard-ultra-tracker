import { expect, test, type Page, type Request } from '@playwright/test';
import { connecterParApi, creerAdminParApi, exigerIdentifiantsAdminMaster, nomCourseUnique, pseudoAdminUnique } from './aide-admin';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, saisir } from './aide-connexion';
import { connecterCoureur, seDesinscrireParApi } from './aide-coureur';
import { ouvrirMenuCompte } from './aide-entete';
import { PROBLEME, avecApi, prefixeUnique } from './aide-recherche-coureurs';
import {
  MESSAGE_CSRF,
  MESSAGE_INTROUVABLE,
  MESSAGE_SERVICE_INDISPONIBLE,
  boutonPasserAdmin,
  chercherAdmin,
  confirmerPassageAdmin,
  creerCoureur,
  creerCourse,
  demarrer,
  estPassageAdmin,
  estPassageAdminUrl,
  inscrire,
  ligneAdmin,
  ouvrirConfirmationAdmin,
  ouvrirEnAdmin,
  ouvrirEnMaster,
  resultatAdminDe,
  t,
  texteConfirmationAdmin,
} from './aide-passage-admin';

/*
 * Incrément 9.7 : « Passer en admin » depuis la recherche de la page Admins (CA17 à CA21).
 * CA22 : accessibilité dans passage-en-admin-accessibilite.spec.ts. CA23 : adaptation de recherche-coureurs.spec.ts.
 * Les cas où le coureur a couru une Course TERMINEE (E4) : passage-en-admin.horloge.spec.ts.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

function espionner(page: Page): { requetes: Request[] } {
  const requetes: Request[] = [];
  page.on('request', (r) => {
    if (new URL(r.url()).pathname.startsWith('/api/')) requetes.push(r);
  });
  return { requetes };
}

test.describe('Passer un coureur en admin', () => {
  test('CA17 - confirmation sans appel réseau, une seule confirmation à la fois, un seul POST, succès et liste des admins', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const p = prefixeUnique();
    const e1 = `${p}e1`;
    const adminExistant = `${p}ad`;
    await creerCoureur(playwright, e1);
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, adminExistant));
    const { requetes } = espionner(page);

    await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
    await chercherAdmin(page, e1);
    await expect(t(page, 'admin-promotion-compte')).toHaveText('1 coureur trouvé');
    await expect(boutonPasserAdmin(page, e1)).toHaveText('Passer en admin');
    await expect(t(page, 'admin-promotion-resultat')).toContainText(e1);

    // ouvrir : aucun appel réseau, texte exact, focus sur Annuler
    const avant = requetes.length;
    await ouvrirConfirmationAdmin(page, e1);
    expect(requetes.length).toBe(avant);
    await expect(t(page, 'admin-promotion-confirmation-texte')).toHaveText(texteConfirmationAdmin(e1));
    await expect(t(page, 'admin-promotion-confirmation-texte')).toContainText('irréversible');
    await expect(t(page, 'admin-promotion-bouton-confirmer')).toHaveText('Confirmer le passage en admin');
    await expect(t(page, 'admin-promotion-bouton-annuler')).toHaveText('Annuler');

    // Annuler : fermée, aucun appel, focus rendu au bouton du résultat
    await t(page, 'admin-promotion-bouton-annuler').click();
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await expect(boutonPasserAdmin(page, e1)).toBeFocused();
    expect(requetes.length).toBe(avant);

    // Échap ferme aussi et rend le focus
    await ouvrirConfirmationAdmin(page, e1);
    await page.keyboard.press('Escape');
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await expect(boutonPasserAdmin(page, e1)).toBeFocused();

    // une seule confirmation à la fois : « Supprimer » d'une ligne ferme celle du résultat, et inversement
    await ouvrirConfirmationAdmin(page, e1);
    await ligneAdmin(page, adminExistant).getByTestId('admin-bouton-supprimer').click();
    await expect(ligneAdmin(page, adminExistant).getByTestId('admin-suppression-confirmation')).toBeVisible();
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await boutonPasserAdmin(page, e1).click();
    await expect(t(page, 'admin-promotion-confirmation')).toBeVisible();
    await expect(t(page, 'admin-suppression-confirmation')).toHaveCount(0);

    // une nouvelle recherche ferme la confirmation ; la fermeture du panneau aussi
    await t(page, 'admin-promotion-recherche').fill(`${e1}x`);
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await chercherAdmin(page, e1);
    await ouvrirConfirmationAdmin(page, e1);
    await t(page, 'admin-promotion-bouton-fermer').click();
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await chercherAdmin(page, e1);
    await ouvrirConfirmationAdmin(page, e1);

    // Confirmer, avec un double clic : un seul POST, précédé de GET /api/csrf
    const posts: Request[] = [];
    page.on('request', (r) => {
      if (estPassageAdmin(r)) posts.push(r);
    });
    const depart = requetes.length;
    await t(page, 'admin-promotion-bouton-confirmer').dblclick();
    await expect(t(page, 'admin-message-action')).toHaveText(`Le coureur ${e1} est maintenant administrateur.`);
    expect(posts).toHaveLength(1);
    const chemins = requetes.slice(depart).map((r) => `${r.method()} ${new URL(r.url()).pathname}`);
    expect(chemins.indexOf('GET /api/csrf')).toBeGreaterThanOrEqual(0);
    expect(chemins.indexOf('GET /api/csrf')).toBeLessThan(chemins.findIndex((c) => c.startsWith('POST') && c.endsWith('/passage-admin')));

    // après succès : confirmation fermée, E1 sorti des résultats, dans la liste des admins, panneau ouvert, focus dans le champ
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await expect(resultatAdminDe(page, e1)).toHaveCount(0);
    await expect(t(page, 'admin-promotion-vide')).toBeVisible();
    await expect(ligneAdmin(page, e1)).toHaveCount(1);
    await expect(ligneAdmin(page, e1).getByTestId('admin-etat')).toHaveText('Actif');
    await expect(t(page, 'admin-promotion')).toBeVisible();
    await expect(t(page, 'admin-promotion-recherche')).toBeFocused();
    await expect(t(page, 'admin-message-action')).toHaveAttribute('role', 'status');
  });

  test('CA18 - refus 409 avec les noms de courses (préparation, en cours, pluriel, texte brut), puis succès après désinscription', async ({ page, playwright }) => {
    test.setTimeout(150_000);
    const p = prefixeUnique();
    const [e2, e3, e6, e7] = ['e2', 'e3', 'e6', 'e7'].map((s) => `${p}${s}`);
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

    await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
    const erreur = t(page, 'admin-erreur-action');

    await chercherAdmin(page, e2);
    await ouvrirConfirmationAdmin(page, e2);
    await confirmerPassageAdmin(page);
    await expect(erreur).toHaveText(`Impossible : ce coureur est inscrit à la course « ${nomPrep} » qui n'a pas démarré. Il doit d'abord se désinscrire.`);
    await expect(erreur).toHaveAttribute('role', 'alert');
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await expect(resultatAdminDe(page, e2)).toHaveCount(1);
    await expect(ligneAdmin(page, e2)).toHaveCount(0);
    await expect(t(page, 'admin-message-action')).toHaveCount(0);

    await chercherAdmin(page, e6);
    await ouvrirConfirmationAdmin(page, e6);
    await confirmerPassageAdmin(page);
    await expect(erreur).toContainText(`« ${nomPrepB} »`);
    await expect(erreur).toContainText(`« ${nomHtml} »`);
    await expect(erreur).toContainText('Impossible : ce coureur est inscrit aux courses « ');
    await expect(erreur).toContainText("» qui n'ont pas démarré. Il doit d'abord se désinscrire.");
    await expect(erreur.locator('b')).toHaveCount(0);
    await expect(ligneAdmin(page, e6)).toHaveCount(0);

    await chercherAdmin(page, e3);
    await ouvrirConfirmationAdmin(page, e3);
    await confirmerPassageAdmin(page);
    await expect(erreur).toHaveText(`Impossible : ce coureur participe à la course en cours « ${nomEnCours} ». Réessayez à la fin de la course.`);
    await expect(resultatAdminDe(page, e3)).toHaveCount(1);
    await expect(ligneAdmin(page, e3)).toHaveCount(0);

    await chercherAdmin(page, e7);
    await ouvrirConfirmationAdmin(page, e7);
    await confirmerPassageAdmin(page);
    await expect(erreur).toContainText('Impossible : ce coureur participe aux courses en cours « ');
    await expect(erreur).toContainText(`« ${nomEnCours} »`);
    await expect(erreur).toContainText(`« ${nomEnCoursB} »`);
    await expect(erreur).toContainText('Réessayez à la fin des courses.');

    // E2 se désinscrit depuis son propre compte, la même action réussit
    expect(await avecApi(playwright, (ctx) => seDesinscrireParApi(ctx, e2, inscriptionE2.id))).toBeLessThan(300);
    await chercherAdmin(page, e2);
    await ouvrirConfirmationAdmin(page, e2);
    await confirmerPassageAdmin(page);
    await expect(t(page, 'admin-message-action')).toHaveText(`Le coureur ${e2} est maintenant administrateur.`);
    await expect(erreur).toHaveCount(0);
    await expect(ligneAdmin(page, e2)).toHaveCount(1);
    await expect(resultatAdminDe(page, e2)).toHaveCount(0);
  });

  test('CA19 - erreurs simulées : 404, 403 CSRF, 500, 409 sans courses, messages effacés, 401, 403 accès refusé', async ({ page, playwright }) => {
    test.setTimeout(150_000);
    const e1 = `${prefixeUnique()}e1`;
    await creerCoureur(playwright, e1);
    const { requetes } = espionner(page);
    const motif = estPassageAdminUrl;

    await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
    await chercherAdmin(page, e1);
    const erreur = t(page, 'admin-erreur-action');
    const depuis = (n: number) => requetes.slice(n).map((r) => `${r.method()} ${new URL(r.url()).pathname}`);

    // 404 : message, recherche relancée
    await page.route(motif, (route) => route.fulfill(PROBLEME(404, 'COMPTE_INTROUVABLE')));
    let n = requetes.length;
    await ouvrirConfirmationAdmin(page, e1);
    await confirmerPassageAdmin(page);
    await expect(erreur).toHaveText(MESSAGE_INTROUVABLE);
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await expect.poll(() => depuis(n).filter((c) => c === 'GET /api/administration/coureurs').length).toBeGreaterThanOrEqual(1);

    // 403 CSRF : message, nouveau jeton, message précédent effacé
    await page.unroute(motif);
    await page.route(motif, (route) => route.fulfill(PROBLEME(403, 'CSRF_INVALIDE')));
    await ouvrirConfirmationAdmin(page, e1);
    n = requetes.length;
    await confirmerPassageAdmin(page);
    await expect(erreur).toHaveText(MESSAGE_CSRF);
    await expect(erreur).not.toContainText(MESSAGE_INTROUVABLE);
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await expect.poll(() => depuis(n).filter((c) => c === 'GET /api/csrf').length).toBeGreaterThanOrEqual(2);
    expect(depuis(n)).not.toContain('GET /api/administration/admins');
    expect(depuis(n)).not.toContain('GET /api/administration/coureurs');

    // 500 : réponse retardée, l'ancien message est déjà effacé pendant l'envoi
    await page.unroute(motif);
    let liberer!: () => void;
    const feu = new Promise<void>((resolve) => (liberer = resolve));
    await page.route(motif, async (route) => {
      await feu;
      await route.fulfill(PROBLEME(500, 'ERREUR_INTERNE')).catch(() => undefined);
    });
    await ouvrirConfirmationAdmin(page, e1);
    await confirmerPassageAdmin(page);
    await expect(t(page, 'admin-promotion-bouton-confirmer')).toBeDisabled();
    await expect(t(page, 'admin-promotion-bouton-annuler')).toBeDisabled();
    await expect(erreur).toHaveCount(0);
    liberer();
    await expect(erreur).toHaveText(MESSAGE_SERVICE_INDISPONIBLE);
    await expect(boutonPasserAdmin(page, e1)).toBeFocused();

    // 409 sans courses
    await page.unroute(motif);
    await page.route(motif, (route) => route.fulfill(PROBLEME(409, 'COUREUR_INSCRIT_COURSE_EN_PREPARATION')));
    await ouvrirConfirmationAdmin(page, e1);
    await confirmerPassageAdmin(page);
    await expect(erreur).toHaveText('Impossible : ce coureur est inscrit à une course.');
    await page.unroute(motif);

    // 403 ACCES_REFUSE
    await page.route(motif, (route) => route.fulfill(PROBLEME(403, 'ACCES_REFUSE')));
    await ouvrirConfirmationAdmin(page, e1);
    await confirmerPassageAdmin(page);
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await page.unroute(motif);

    // 401
    await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
    await chercherAdmin(page, e1);
    await page.route(motif, (route) => route.fulfill(PROBLEME(401, 'NON_AUTHENTIFIE')));
    await ouvrirConfirmationAdmin(page, e1);
    await confirmerPassageAdmin(page);
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fadmins$/);
    await page.unroute(motif);
  });

  test('CA20 - un admin simple n\'a pas la page Admins, ne voit que « Passer en bénévole » et reçoit 403 sur l\'API', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const e1 = `${prefixeUnique()}e1`;
    const nadia = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    await creerCoureur(playwright, e1);

    await page.context().clearCookies();
    await connecterParApi(page.request, nadia, 'mot-de-passe-admin-1');
    await page.goto('/administration/admins');
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(t(page, 'admin-bouton-promouvoir')).toHaveCount(0);

    await page.goto('/administration');
    await expect(t(page, 'menu-administration-courses')).toBeVisible();
    await expect(t(page, 'menu-administration-admins')).toHaveCount(0);

    await page.goto('/administration/benevoles');
    await expect(t(page, 'titre-benevoles')).toBeVisible();
    await t(page, 'benevole-bouton-promouvoir').click();
    await t(page, 'benevole-promotion-recherche').fill(e1);
    await expect(t(page, 'benevole-promotion-resultat')).toHaveCount(1);
    await expect(page.getByRole('button', { name: `Passer en bénévole le coureur ${e1}`, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /Passer en admin/ })).toHaveCount(0);

    // POST direct : 403 ACCES_REFUSE, même pour un identifiant bidon
    const reponse = await page.evaluate(async () => {
      await fetch('/api/csrf');
      const jeton = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '');
      const r = await fetch('/api/administration/coureurs/00000000-0000-0000-0000-000000000000/passage-admin', { method: 'POST', headers: { 'X-XSRF-TOKEN': jeton } });
      return { statut: r.status, corps: (await r.json()) as { code: string } };
    });
    expect(reponse.statut).toBe(403);
    expect(reponse.corps.code).toBe('ACCES_REFUSE');
  });

  test('CA21 - le coureur promu est déconnecté, se reconnecte en admin sans la page Admins ; l\'admin master le désactive puis le supprime', async ({ page, browser, playwright }) => {
    test.setTimeout(150_000);
    const e5 = `${prefixeUnique()}e5`;
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, e5));

    const contexteA = await browser.newContext({ serviceWorkers: 'block' });
    try {
      const pageA = await contexteA.newPage();
      await connecterCoureur(pageA, e5);

      await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
      await chercherAdmin(page, e5);
      await ouvrirConfirmationAdmin(page, e5);
      await confirmerPassageAdmin(page);
      await expect(t(page, 'admin-message-action')).toHaveText(`Le coureur ${e5} est maintenant administrateur.`);

      // au clic suivant dans A : retour à la connexion
      await ouvrirMenuCompte(pageA);
      await pageA.getByTestId('menu-lien-mes-inscriptions').click();
      await expect(pageA).toHaveURL(/\/connexion\?retour=/);

      // reconnexion avec les mêmes identifiants : administration, sans entrée Admins
      await ouvrirConnexion(pageA);
      await saisir(pageA, e5, MOT_DE_PASSE);
      await pageA.getByTestId('bouton-connexion').click();
      await expect(pageA).toHaveURL(/\/administration$/);
      await expect(pageA.getByTestId('menu-administration-courses')).toBeVisible();
      await expect(pageA.getByTestId('menu-administration-benevoles')).toBeVisible();
      await expect(pageA.getByTestId('menu-administration-admins')).toHaveCount(0);
      await pageA.goto('/administration/admins');
      await expect(pageA).toHaveURL(/\/acces-refuse$/);

      // l'admin master le voit, le désactive, puis le supprime
      await page.reload();
      await expect(ligneAdmin(page, e5)).toHaveCount(1);
      await ligneAdmin(page, e5).getByTestId('admin-bouton-desactiver').click();
      await ligneAdmin(page, e5).getByTestId('admin-bouton-confirmer').click();
      await expect(ligneAdmin(page, e5).getByTestId('admin-etat')).toHaveText('Désactivé');
      await ligneAdmin(page, e5).getByTestId('admin-bouton-supprimer').click();
      await ligneAdmin(page, e5).getByTestId('admin-suppression-bouton-confirmer').click();
      await expect(ligneAdmin(page, e5)).toHaveCount(0);
    } finally {
      await contexteA.close();
    }
  });
});

test.describe('Passer en admin : page Bénévoles inchangée', () => {
  test('CA20 - sur la page Bénévoles, l\'admin master garde « Passer en bénévole » et n\'a aucun « Passer en admin »', async ({ page, playwright }) => {
    const e1 = `${prefixeUnique()}e1`;
    await creerCoureur(playwright, e1);
    await ouvrirEnMaster(page, '/administration/benevoles', 'titre-benevoles');
    await t(page, 'benevole-bouton-promouvoir').click();
    await t(page, 'benevole-promotion-recherche').fill(e1);
    await expect(page.getByRole('button', { name: `Passer en bénévole le coureur ${e1}`, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /Passer en admin/ })).toHaveCount(0);
  });
});
