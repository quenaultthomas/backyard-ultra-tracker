import { expect, test } from '@playwright/test';
import {
  MOT_DE_PASSE_ADMIN_MASTER,
  PSEUDO_ADMIN_MASTER,
  creerAdminParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
} from './aide-admin';
import { abandonnerParApi, abandonnerParEcran, compterPostsAbandon, connecterAdmin, connecterPatron, lire, ouvrirPilotage, urlPilotage } from './aide-abandon';
import { MOT_DE_PASSE, seConnecter } from './aide-connexion';
import { placerStatutCourseEnBase } from './aide-coureur';
import { avecApi } from './aide-horloge';
import {
  lireInscriptionEnBase,
  lirePassagesEnBase,
  lireStatutCourseEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  seConnecterVers,
  verifierSucces,
} from './aide-scan';

/*
 * Incrément 4.11 : abandon manuel depuis le pilotage (CA13, CA14, CA15, CA19). Course de 60 min : elle reste dans la Boucle 1,
 * aucune avance d'horloge (projet chromium). Courses, comptes et jetons préparés par l'API.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const ADMIN_MASTER = { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER };

async function creerNadia(playwright: Parameters<typeof avecApi>[0]): Promise<string> {
  const nadia = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
  return nadia;
}

test.describe('Abandon manuel au pilotage', () => {
  test('CA13 - Nadia abandonne Tom : panneau de confirmation, motif obligatoire, annulation, puis abandon enregistré', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const [bruno, chloe, tom] = course.coureurs;
    const nadia = await creerNadia(playwright);
    const posts = compterPostsAbandon(page);

    await connecterAdmin(page, nadia);
    await ouvrirPilotage(page, course.id, 3);
    await expect(lire(page, 'pilotage-colonne-action')).toHaveText('Action');
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveCount(3);
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveText(['Abandonner', 'Abandonner', 'Abandonner']);
    for (const c of [bruno, chloe, tom]) {
      await expect(page.getByRole('button', { name: `Abandonner le coureur n° ${c.dossard} ${c.pseudo}` })).toBeVisible();
    }
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);

    const boutonTom = page.getByRole('button', { name: `Abandonner le coureur n° 3 ${tom.pseudo}` });
    await boutonTom.click();
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-abandonner-titre')).toHaveText(`Abandonner le coureur n° 3 ${tom.pseudo} ?`);
    await expect(lire(page, 'pilotage-abandonner-titre')).toBeFocused();
    await expect(lire(page, 'pilotage-abandonner-avertissement-dernier')).toHaveCount(0);
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toContainText('Le coureur sera mis en abandon et son QR code ne sera plus accepté au scan.');
    for (const motif of ['VOLONTAIRE', 'MANUEL', 'AUTRE']) {
      await expect(lire(page, `pilotage-abandonner-motif-${motif}`)).not.toBeChecked();
    }

    // motif obligatoire : aucun appel
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(lire(page, 'pilotage-abandonner-erreur-motif')).toHaveText("Choisissez un motif d'abandon.");
    expect(posts.lire()).toBe(0);

    // Annuler : fermé, focus rendu au bouton, Tom toujours en course
    await lire(page, 'pilotage-abandonner-annuler').click();
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
    await expect(boutonTom).toBeFocused();
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);
    expect(posts.lire()).toBe(0);
    expect(lireInscriptionEnBase(course.id, 3).statut).toBe('EN_COURSE');

    // « Volontaire » puis confirmation
    await boutonTom.click();
    await expect(lire(page, 'pilotage-abandonner-erreur-motif')).toHaveCount(0);
    await lire(page, 'pilotage-abandonner-motif-VOLONTAIRE').check();
    await lire(page, 'pilotage-abandonner-confirmer').click();

    await expect(lire(page, 'pilotage-abandonner-succes')).toHaveText(`Abandon enregistré : coureur n° 3 ${tom.pseudo}.`);
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
    expect(posts.lire()).toBe(1);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await expect(lire(page, 'pilotage-abandon-dossard')).toHaveText('3');
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(tom.pseudo);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 1');
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('Volontaire');
    await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText('0');
    await expect(lire(page, 'pilotage-compteur')).toHaveText('2 coureurs en course sur 3 inscrits');
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');

    expect(lireInscriptionEnBase(course.id, 3)).toEqual({ statut: 'ABANDON', motifAbandon: 'VOLONTAIRE', boucleAbandon: '1' });
    expect(lireInscriptionEnBase(course.id, 1).statut).toBe('EN_COURSE');
    expect(lireInscriptionEnBase(course.id, 2).statut).toBe('EN_COURSE');
    expect(lirePassagesEnBase(course.id)).toEqual([]);
    expect(lireStatutCourseEnBase(course.id)).toBe('EN_COURS');
  });

  test('CA13 - Patron voit les mêmes boutons ; un rafraîchissement du polling ne ferme pas le panneau et conserve le motif et le focus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const chloe = course.coureurs[1];
    await connecterPatron(page);
    await ouvrirPilotage(page, course.id, 3);
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveCount(3);

    await page.getByRole('button', { name: `Abandonner le coureur n° 2 ${chloe.pseudo}` }).click();
    await expect(lire(page, 'pilotage-abandonner-titre')).toBeFocused();
    await lire(page, 'pilotage-abandonner-motif-MANUEL').check();

    await lire(page, 'pilotage-abandonner-motif-MANUEL').focus();
    await page.waitForResponse((r) => r.request().method() === 'GET' && /\/pilotage$/.test(new URL(r.url()).pathname), { timeout: 8000 });

    await expect(lire(page, 'pilotage-abandonner-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-abandonner-motif-MANUEL')).toBeChecked();
    await expect(lire(page, 'pilotage-abandonner-titre')).toHaveText(`Abandonner le coureur n° 2 ${chloe.pseudo} ?`);
    await expect(lire(page, 'pilotage-abandonner-motif-MANUEL')).toBeFocused();
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);
    expect(lireInscriptionEnBase(course.id, 2).statut).toBe('EN_COURSE');

    await page.keyboard.press('Escape');
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
    await expect(page.getByRole('button', { name: `Abandonner le coureur n° 2 ${chloe.pseudo}` })).toBeFocused();
  });

  test('CA14 - le QR d\'un coureur abandonné est refusé au scan, celui d\'un coureur en course est accepté', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const [bruno, , tom] = course.coureurs;
    const statut = await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, tom.inscriptionId, 'VOLONTAIRE', ADMIN_MASTER));
    expect(statut).toBe(200);

    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, tom.jeton);
    await expect(lire(page, 'scan-refus')).toBeVisible();
    await expect(lire(page, 'scan-refus-motif')).toHaveText('Coureur en abandon');
    await expect(lire(page, 'scan-resultat-erreur')).toHaveText("Ce coureur n'est plus en course.");
    expect(lirePassagesEnBase(course.id).filter((l) => l.startsWith('3:'))).toEqual([]);

    await scannerParEntree(page, bruno.jeton);
    await verifierSucces(page, bruno.dossard, bruno.pseudo, 1);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
    expect(lireInscriptionEnBase(course.id, 1).statut).toBe('EN_COURSE');
    expect(lireInscriptionEnBase(course.id, 3).statut).toBe('ABANDON');
  });

  test('CA15 - l\'abandon du dernier coureur termine la course sans vainqueur : « Aucun vainqueur », plus de bouton, scan refusé', async ({ page, browser, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const [bruno, chloe, tom] = course.coureurs;
    const nadia = await creerNadia(playwright);
    // Léo ouvre l'écran de scan avant la fin de la course
    const contexteBenevole = await browser.newContext();
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);

    await connecterAdmin(page, nadia);
    await ouvrirPilotage(page, course.id, 3);
    await abandonnerParEcran(page, 1, bruno.pseudo, 'MANUEL');
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await abandonnerParEcran(page, 2, chloe.pseudo, 'AUTRE');
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(1);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');

    await page.getByRole('button', { name: `Abandonner le coureur n° 3 ${tom.pseudo}` }).click();
    await expect(lire(page, 'pilotage-abandonner-avertissement-dernier')).toHaveText(
      "C'est le dernier coureur en course : la course sera terminée, sans vainqueur.",
    );
    await lire(page, 'pilotage-abandonner-motif-MANUEL').check();
    await lire(page, 'pilotage-abandonner-confirmer').click();

    await expect(lire(page, 'pilotage-abandonner-succes')).toContainText(`Abandon enregistré : coureur n° 3 ${tom.pseudo}.`);
    await expect(lire(page, 'pilotage-abandonner-succes')).toContainText('La course est terminée : plus aucun coureur en course.');
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée');
    await expect(lire(page, 'pilotage-message-terminee')).toHaveText('La course est terminée.');
    await expect(lire(page, 'pilotage-aucun-vainqueur')).toHaveText('Aucun vainqueur : tous les coureurs ont abandonné.');
    await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
    await expect(lire(page, 'pilotage-inscrits-vide')).toBeVisible();
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(3);
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Abandonner/ })).toHaveCount(0);

    expect([1, 2, 3].map((d) => lireInscriptionEnBase(course.id, d).statut)).toEqual(['ABANDON', 'ABANDON', 'ABANDON']);
    expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');

    // Léo scanne ensuite le jeton de Bruno : « Course terminée », aucun Passage, plus de bouton « Scanner »
    try {
      await scannerParEntree(scan, bruno.jeton);
      await expect(lire(scan, 'scan-refus-motif')).toHaveText('Course terminée');
      await expect(lire(scan, 'scan-resultat-erreur')).toHaveText('La course est terminée.');
      expect(lirePassagesEnBase(course.id)).toEqual([]);
      await scan.goto('/benevole');
      const carte = scan.getByTestId('benevole-ligne-course').filter({ hasText: course.nom });
      await expect(carte).toHaveCount(1);
      await expect(carte.getByTestId('benevole-lien-scanner')).toHaveCount(0);
    } finally {
      await contexteBenevole.close();
    }
  });

  test('CA19 - un coureur et un bénévole sur l\'URL de pilotage sont refusés, un anonyme est renvoyé à la connexion', async ({ page, browser, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [coureur] = course.coureurs;

    await page.goto(urlPilotage(course.id));
    await expect(page).toHaveURL(/\/connexion\?retour=/);
    expect(decodeURIComponent(page.url())).toContain(`retour=${urlPilotage(course.id)}`);

    await seConnecter(page, coureur.pseudo, MOT_DE_PASSE);
    await page.goto(urlPilotage(course.id));
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveCount(0);

    const contexte = await browser.newContext();
    try {
      const pageBenevole = await contexte.newPage();
      await seConnecterVers(pageBenevole, course.benevole, 'mot-de-passe-benevole-1');
      await expect(pageBenevole).toHaveURL(/\/benevole$/);
      await pageBenevole.goto(urlPilotage(course.id));
      await expect(pageBenevole).toHaveURL(/\/acces-refuse$/);
    } finally {
      await contexte.close();
    }
  });

  test('CA19 - une course terminée posée en base n\'offre aucun bouton « Abandonner »', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    placerStatutCourseEnBase(course.id, 'TERMINEE');
    await connecterPatron(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée');
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveCount(0);
    await expect(lire(page, 'pilotage-colonne-action')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Abandonner/ })).toHaveCount(0);
  });

  test('CA19 - le POST d\'abandon sur une course terminée est refusé en 409 COURSE_TERMINEE, même pour une inscription inconnue', async ({ playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    placerStatutCourseEnBase(course.id, 'TERMINEE');
    const statut = await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, 'abc', 'MANUEL', ADMIN_MASTER));
    expect(statut).toBe(409);
  });
});
