import { expect, test, type Page } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique } from './aide-connexion';
import { MOT_DE_PASSE_ADMIN_CREE, MOT_DE_PASSE_BENEVOLE_CREE, creerAdminParApi, exigerIdentifiantsAdminMaster, pseudoAdminUnique } from './aide-admin';
import { placerStatutCourseEnBase, placerStatutInscriptionEnBase } from './aide-coureur';
import { avecApi } from './aide-horloge';
import {
  champ,
  creerCourseAffectee,
  lirePassagesEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  seConnecterVers,
  urlScan,
  verifierSucces,
} from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const erreur = (page: Page) => page.getByTestId('scan-resultat-erreur');

test.describe('Écran de scan, parcours nominal', () => {
  test('CA16 - le bénévole scanne deux coureurs au clavier depuis l\'accueil, le jeton n\'est conservé nulle part', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright);
    const [un, deux] = course.coureurs;
    expect([un.dossard, deux.dossard]).toEqual([1, 2]);

    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page).toHaveURL(/\/benevole$/);
    await page.getByTestId('benevole-lien-scanner').click();
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await expect(page.getByTestId('scan-course-nom')).toHaveText(course.nom);
    await expect(page.getByTestId('scan-course-statut')).toHaveText('En cours');
    await expect(champ(page)).toBeVisible();

    await champ(page).fill(un.jeton);
    await champ(page).press('Enter');
    await verifierSucces(page, 1, un.pseudo, 1);
    await expect(page.getByTestId('scan-resultat-succes')).toHaveAttribute('role', 'status');
    await expect(champ(page)).toHaveValue('');
    await expect(champ(page)).toBeFocused();

    // Sans toucher à la souris : saisie au clavier du jeton suivant puis Entrée.
    await page.keyboard.type(deux.jeton);
    await expect(page.getByTestId('scan-resultat-succes')).toHaveCount(0);
    await page.keyboard.press('Enter');
    await verifierSucces(page, 2, deux.pseudo, 1);
    await expect(page.getByTestId('scan-resultat-dossard')).toHaveCount(1);
    await expect(champ(page)).toHaveValue('');
    await expect(champ(page)).toBeFocused();

    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);

    // Le jeton n'apparaît ni dans l'URL, ni dans le DOM, ni dans les stockages du navigateur.
    for (const { jeton } of [un, deux]) {
      expect(page.url()).not.toContain(jeton);
      expect(await page.content()).not.toContain(jeton);
      const stockages = await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]));
      expect(stockages).not.toContain(jeton);
    }
  });

  test('CA16 - le bouton « Enregistrer le passage » valide aussi la saisie', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await champ(page).fill(course.coureurs[0].jeton);
    await page.getByTestId('scan-bouton-enregistrer').click();
    await verifierSucces(page, 1, course.coureurs[0].pseudo, 1);
  });
});

test.describe('Écran de scan, refus affichés par le mécanisme générique', () => {
  test('CA17 - un coureur déjà scanné est refusé, la valeur reste sélectionnée avec le focus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, un.jeton);
    await verifierSucces(page, 1, un.pseudo, 1);

    await scannerParEntree(page, un.jeton);
    await expect(erreur(page)).toHaveText('Ce coureur a déjà un passage sur la boucle 1.');
    await expect(erreur(page)).toHaveAttribute('role', 'alert');
    await expect(page.getByTestId('scan-resultat-succes')).toHaveCount(0);
    await expect(champ(page)).toHaveValue(un.jeton);
    await expect(champ(page)).toBeFocused();
    const selection = await champ(page).evaluate((el: HTMLInputElement) => [el.selectionStart, el.selectionEnd, el.value.length]);
    expect(selection).toEqual([0, un.jeton.length, un.jeton.length]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });

  test('CA17 - un jeton inconnu est refusé', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, 'abc');
    await expect(erreur(page)).toHaveText('Jeton inconnu pour cette course.');
    await expect(champ(page)).toHaveValue('abc');
    expect(lirePassagesEnBase(course.id)).toEqual([]);
  });

  test('CA17 - un champ vide affiche « Saisissez le jeton du coureur. » sans aucune requête', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    const envois: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/passages')) envois.push(r.url());
    });
    await page.getByTestId('scan-bouton-enregistrer').click();
    await expect(page.getByTestId('scan-erreur-saisie')).toHaveText('Saisissez le jeton du coureur.');
    await champ(page).fill('   ');
    await champ(page).press('Enter');
    await expect(page.getByTestId('scan-erreur-saisie')).toHaveText('Saisissez le jeton du coureur.');
    await expect(erreur(page)).toHaveCount(0);
    expect(envois).toEqual([]);
  });

  test('CA17 - un jeton collé avec des espaces autour et coupé en deux est accepté', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const milieu = Math.floor(un.jeton.length / 2);
    await scannerParEntree(page, `  ${un.jeton.slice(0, milieu)} \t ${un.jeton.slice(milieu)}  `);
    await verifierSucces(page, 1, un.pseudo, 1);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });

  test('CA17 - le jeton d\'une autre course est traité comme inconnu', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const autre = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, autre.coureurs[0].jeton);
    await expect(erreur(page)).toHaveText('Jeton inconnu pour cette course.');
    expect(lirePassagesEnBase(course.id)).toEqual([]);
    expect(lirePassagesEnBase(autre.id)).toEqual([]);
  });

  test('CA17 - un coureur en abandon est refusé', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    placerStatutInscriptionEnBase(course.coureurs[0].inscriptionId, 'ABANDON');
    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(erreur(page)).toHaveText('Ce coureur n\'est plus en course.');
    expect(lirePassagesEnBase(course.id)).toEqual([]);
  });

  test('CA17 - deux validations rapides n\'envoient qu\'une seule requête', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const envois: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/passages')) envois.push(r.url());
    });
    // Réponse retardée : la seconde validation arrive pendant l'envoi.
    await page.route('**/api/benevole/courses/*/passages', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 600));
      await route.continue();
    });
    await champ(page).fill(un.jeton);
    await champ(page).press('Enter');
    await champ(page).press('Enter');
    await verifierSucces(page, 1, un.pseudo, 1);
    expect(envois).toHaveLength(1);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });
});

test.describe('Écran de scan, accès et états', () => {
  test('CA18 - une course en préparation affiche « La course n\'est pas démarrée. » sans champ', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 0, demarree: false });
    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
    await expect(page.getByTestId('scan-message-etat')).toHaveText('La course n\'est pas démarrée.');
    await expect(page.getByTestId('scan-course-statut')).toHaveText('En préparation');
    await expect(champ(page)).toHaveCount(0);
    await expect(page.getByTestId('scan-bouton-enregistrer')).toHaveCount(0);
  });

  test('CA18 - une course terminée affiche « La course est terminée. » sans champ', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    placerStatutCourseEnBase(course.id, 'TERMINEE');
    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
    await expect(page.getByTestId('scan-message-etat')).toHaveText('La course est terminée.');
    await expect(champ(page)).toHaveCount(0);
  });

  test('CA18 - une course non affectée, un id inconnu ou non UUID affichent « Vous n\'êtes pas affecté à cette course. »', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const nonAffectee = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page).toHaveURL(/\/benevole$/);
    for (const id of [nonAffectee.id, '00000000-0000-4000-8000-000000000000', 'inexistant']) {
      await page.goto(urlScan(id));
      await expect(page.getByTestId('scan-message-etat')).toHaveText('Vous n\'êtes pas affecté à cette course.');
      await expect(champ(page)).toHaveCount(0);
    }
  });

  test('CA18 - un coureur est redirigé vers /acces-refuse', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const pseudo = pseudoUnique('coureur');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
    await seConnecterVers(page, pseudo, MOT_DE_PASSE);
    await expect(page).toHaveURL(/\/$/);
    await page.goto(urlScan(course.id));
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(champ(page)).toHaveCount(0);
  });

  test('CA18 - un admin est redirigé vers /acces-refuse', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    await seConnecterVers(page, admin, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).not.toHaveURL(/\/connexion/);
    await page.goto(urlScan(course.id));
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });

  test('CA18 - un anonyme est redirigé vers la connexion avec le retour', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await page.goto(urlScan(course.id));
    await expect(page).toHaveURL(`/connexion?retour=${encodeURIComponent(urlScan(course.id))}`);
  });

  test('CA18 - un échec du chargement (500) affiche l\'erreur de chargement', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page).toHaveURL(/\/benevole$/);
    await page.route('**/api/benevole/courses', (route) =>
      route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'ERREUR_INTERNE' }) }),
    );
    await page.goto(urlScan(course.id));
    await expect(page.getByTestId('scan-erreur-chargement')).toHaveText('Impossible de charger la course. Réessayez plus tard.');
    await expect(champ(page)).toHaveCount(0);
  });

  test('CA18 - « Changer de course » ramène à l\'accueil bénévole', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.getByTestId('scan-lien-changer').click();
    await expect(page).toHaveURL(/\/benevole$/);
    await expect(page.getByTestId('accueil-benevole-titre')).toBeVisible();
  });

  test('CA18 - une course terminée après l\'ouverture de l\'écran : le scan suivant est refusé par le serveur', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    placerStatutCourseEnBase(course.id, 'TERMINEE');
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(erreur(page)).toHaveText('La course est terminée.');
    expect(lirePassagesEnBase(course.id)).toEqual([]);
  });

  test('CA15 - un bénévole affecté à deux courses les ouvre indépendamment depuis l\'accueil', async ({ page, playwright }) => {
    const premiere = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const seconde = await creerCourseAffectee(playwright, premiere.benevole);
    await seConnecterVers(page, premiere.benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page.getByTestId('benevole-ligne-course')).toHaveCount(2);
    await page.goto(urlScan(seconde.id));
    await expect(page.getByTestId('scan-message-etat')).toHaveText('La course n\'est pas démarrée.');
    await page.goto(urlScan(premiere.id));
    await scannerParEntree(page, premiere.coureurs[0].jeton);
    await verifierSucces(page, 1, premiere.coureurs[0].pseudo, 1);
  });
});
