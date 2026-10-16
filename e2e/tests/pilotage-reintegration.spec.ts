import { expect, test } from '@playwright/test';
import {
  MOT_DE_PASSE_ADMIN_MASTER,
  PSEUDO_ADMIN_MASTER,
  creerAdminParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
} from './aide-admin';
import { abandonnerParApi, abandonnerParEcran, connecterAdmin, connecterPatron, lire, ouvrirPilotage, urlPilotage } from './aide-abandon';
import { MOT_DE_PASSE, seConnecter } from './aide-connexion';
import { placerStatutCourseEnBase } from './aide-coureur';
import { avecApi } from './aide-horloge';
import { boutonReintegrer, compterPostsReintegration, reintegrerParApi, reintegrerParEcran } from './aide-reintegration';
import {
  lireInscriptionEnBase,
  lirePassagesDetailEnBase,
  lirePassagesEnBase,
  lireStatutCourseEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  seConnecterVers,
  verifierSucces,
} from './aide-scan';

/*
 * Incrément 4.12 : réintégration depuis le pilotage (CA14, CA16 début, CA19 et vérifications réelles de CA13/CA17).
 * Course de 60 min : elle reste dans la Boucle 1, aucune avance d'horloge (projet chromium). Tout est préparé par l'API.
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

test.describe('Réintégration au pilotage', () => {
  test('CA14 - Nadia abandonne Tom (« Manuel ») puis le réintègre : 0 boucle corrigée, pas de badge, Tom scanné normalement', async ({ page, browser, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const [bruno, , tom] = course.coureurs;
    const nadia = await creerNadia(playwright);
    const contexteBenevole = await browser.newContext();
    try {
      const scan = await contexteBenevole.newPage();
      await ouvrirEcranDeScan(scan, course);

      await connecterAdmin(page, nadia);
      await ouvrirPilotage(page, course.id, 3);
      await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveCount(0);
      await abandonnerParEcran(page, 3, tom.pseudo, 'MANUEL');
      await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
      await expect(lire(page, 'pilotage-abandons-colonne-action')).toHaveText('Action');
      await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveText('Réintégrer');
      await expect(boutonReintegrer(page, 3, tom.pseudo)).toBeVisible();

      await reintegrerParEcran(page, 3, tom.pseudo, 0);
      await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);
      await expect(lire(page, 'pilotage-abandon')).toHaveCount(0);
      await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');
      await expect(lire(page, 'pilotage-compteur')).toHaveText('3 coureurs en course sur 3 inscrits');
      await expect(lire(page, 'pilotage-inscrit-corrige')).toHaveCount(0);
      await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['0', '0', '0']);

      expect(lireInscriptionEnBase(course.id, 3)).toEqual({ statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' });
      expect(lirePassagesDetailEnBase(course.id)).toEqual([]);

      // Léo scanne Tom : accepté, Boucle 1, aucun Passage CORRECTION
      await scannerParEntree(scan, tom.jeton);
      await verifierSucces(scan, tom.dossard, tom.pseudo, 1);
      await scannerParEntree(scan, bruno.jeton);
      await verifierSucces(scan, bruno.dossard, bruno.pseudo, 1);
      expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '3:1:SCAN']);
      expect(lireStatutCourseEnBase(course.id)).toBe('EN_COURS');
    } finally {
      await contexteBenevole.close();
    }
  });

  test('CA14 - un seul panneau à la fois : « Abandonner » ferme « Réintégrer » et réciproquement ; Patron voit les mêmes boutons', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const [bruno, chloe, tom] = course.coureurs;
    for (const c of [chloe, tom]) {
      expect(await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, c.inscriptionId, 'VOLONTAIRE', ADMIN_MASTER))).toBe(200);
    }
    await connecterPatron(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(2);
    await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveText(['Réintégrer', 'Réintégrer']);
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveCount(1);

    await page.getByRole('button', { name: `Abandonner le coureur n° 1 ${bruno.pseudo}` }).click();
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toBeVisible();

    await boutonReintegrer(page, 3, tom.pseudo).click();
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
    await expect(lire(page, 'pilotage-reintegrer-titre')).toBeFocused();
    await expect(boutonReintegrer(page, 3, tom.pseudo)).toHaveAttribute('aria-expanded', 'true');

    // un seul panneau de réintégration, même en changeant de ligne
    await boutonReintegrer(page, 2, chloe.pseudo).click();
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(1);
    await expect(lire(page, 'pilotage-reintegrer-titre')).toHaveText(`Réintégrer le coureur n° 2 ${chloe.pseudo} ?`);

    await page.getByRole('button', { name: `Abandonner le coureur n° 1 ${bruno.pseudo}` }).click();
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
    expect(lireInscriptionEnBase(course.id, 2).statut).toBe('ABANDON');
    expect(lireInscriptionEnBase(course.id, 3).statut).toBe('ABANDON');
  });

  test('CA13 - le panneau a le texte de la spec, « Annuler » et Échap ne font aucun appel et rendent le focus au bouton', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const tom = course.coureurs[2];
    expect(await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, tom.inscriptionId, 'AUTRE', ADMIN_MASTER))).toBe(200);
    const posts = compterPostsReintegration(page);
    await connecterPatron(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);

    const bouton = boutonReintegrer(page, 3, tom.pseudo);
    await expect(bouton).toHaveAttribute('aria-expanded', 'false');
    await bouton.click();
    await expect(lire(page, 'pilotage-reintegrer-titre')).toHaveText(`Réintégrer le coureur n° 3 ${tom.pseudo} ?`);
    await expect(lire(page, 'pilotage-reintegrer-titre')).toBeFocused();
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toContainText(
      "Le coureur sera remis en course. Les boucles qu'il a manquées seront enregistrées comme passages corrigés, ce qui ne peut pas être annulé. Il devra scanner normalement la boucle en cours.",
    );
    await expect(lire(page, 'pilotage-reintegrer-confirmer')).toHaveText('Confirmer la réintégration');
    await expect(lire(page, 'pilotage-reintegrer-annuler')).toHaveText('Annuler');

    await lire(page, 'pilotage-reintegrer-annuler').click();
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
    await expect(bouton).toBeFocused();

    await bouton.click();
    await expect(lire(page, 'pilotage-reintegrer-titre')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
    await expect(bouton).toBeFocused();

    expect(posts.lire()).toBe(0);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    expect(lireInscriptionEnBase(course.id, 3).statut).toBe('ABANDON');
    expect(lirePassagesDetailEnBase(course.id)).toEqual([]);
  });

  test('CA13 - un rafraîchissement du polling ne ferme pas le panneau de réintégration ni ne fait perdre le focus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const tom = course.coureurs[2];
    expect(await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, tom.inscriptionId, 'VOLONTAIRE', ADMIN_MASTER))).toBe(200);
    await connecterPatron(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);

    await boutonReintegrer(page, 3, tom.pseudo).click();
    await expect(lire(page, 'pilotage-reintegrer-titre')).toBeFocused();
    await lire(page, 'pilotage-reintegrer-confirmer').focus();
    await page.waitForResponse((r) => r.request().method() === 'GET' && /\/pilotage$/.test(new URL(r.url()).pathname), { timeout: 8000 });
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-reintegrer-confirmer')).toBeFocused();
    await expect(lire(page, 'pilotage-reintegrer-titre')).toHaveText(`Réintégrer le coureur n° 3 ${tom.pseudo} ?`);
    expect(lireInscriptionEnBase(course.id, 3).statut).toBe('ABANDON');
  });

  test('CA17 - coureur réintégré par un autre admin pendant la confirmation : panneau fermé avec « Ce coureur n\'est plus en abandon. », aucun Passage en double', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const tom = course.coureurs[2];
    expect(await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, tom.inscriptionId, 'MANUEL', ADMIN_MASTER))).toBe(200);
    const nadia = await creerNadia(playwright);
    await connecterAdmin(page, nadia);
    await ouvrirPilotage(page, course.id, 2);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await boutonReintegrer(page, 3, tom.pseudo).click();
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toBeVisible();

    const autre = await avecApi(playwright, (ctx) => reintegrerParApi(ctx, course.id, tom.inscriptionId, ADMIN_MASTER));
    expect(autre.statut).toBe(200);
    expect(autre.corps['bouclesCorrigees']).toEqual([]);

    await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveText("Ce coureur n'est plus en abandon.", { timeout: 8000 });
    await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveAttribute('role', 'alert');
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);
    expect(lirePassagesDetailEnBase(course.id)).toEqual([]);
  });

  test('CA16 - Tom abandonné puis réintégré : refusé au scan avant, accepté après (Boucle 1, un seul Passage SCAN)', async ({ page, browser, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const tom = course.coureurs[2];
    const nadia = await creerNadia(playwright);
    const contexteBenevole = await browser.newContext();
    try {
      const scan = await contexteBenevole.newPage();
      await ouvrirEcranDeScan(scan, course);

      await connecterAdmin(page, nadia);
      await ouvrirPilotage(page, course.id, 3);
      await abandonnerParEcran(page, 3, tom.pseudo, 'MANUEL');
      await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);

      await scannerParEntree(scan, tom.jeton);
      await expect(lire(scan, 'scan-refus')).toBeVisible();
      await expect(lire(scan, 'scan-refus-motif')).toHaveText('Coureur en abandon');
      await expect(lire(scan, 'scan-resultat-erreur')).toHaveText("Ce coureur n'est plus en course.");
      expect(lirePassagesEnBase(course.id)).toEqual([]);

      await reintegrerParEcran(page, 3, tom.pseudo, 0);

      await scannerParEntree(scan, tom.jeton);
      await verifierSucces(scan, tom.dossard, tom.pseudo, 1);
      expect(lirePassagesDetailEnBase(course.id)).toEqual(['3:1:SCAN:avecDate']);
      expect(lireInscriptionEnBase(course.id, 3).statut).toBe('EN_COURSE');
    } finally {
      await contexteBenevole.close();
    }
  });

  test('CA19 - un coureur et un bénévole sur l\'URL de pilotage sont refusés, un anonyme est renvoyé à la connexion', async ({ page, browser, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [coureur, tom] = course.coureurs;
    expect(await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, tom.inscriptionId, 'MANUEL', ADMIN_MASTER))).toBe(200);

    await page.goto(urlPilotage(course.id));
    await expect(page).toHaveURL(/\/connexion\?retour=/);
    expect(decodeURIComponent(page.url())).toContain(`retour=${urlPilotage(course.id)}`);

    await seConnecter(page, coureur.pseudo, MOT_DE_PASSE);
    await page.goto(urlPilotage(course.id));
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveCount(0);

    const contexte = await browser.newContext();
    try {
      const pageBenevole = await contexte.newPage();
      await seConnecterVers(pageBenevole, course.benevole, 'mot-de-passe-benevole-1');
      await expect(pageBenevole).toHaveURL(/\/benevole$/);
      await pageBenevole.goto(urlPilotage(course.id));
      await expect(pageBenevole).toHaveURL(/\/acces-refuse$/);
      await expect(lire(pageBenevole, 'pilotage-abandon-reintegrer')).toHaveCount(0);
    } finally {
      await contexte.close();
    }
    expect(lireInscriptionEnBase(course.id, tom.dossard).statut).toBe('ABANDON');
  });

  test('CA19 - une course terminée posée en base n\'offre aucun bouton « Réintégrer » ni « Abandonner »', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const tom = course.coureurs[2];
    expect(await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, tom.inscriptionId, 'MANUEL', ADMIN_MASTER))).toBe(200);
    placerStatutCourseEnBase(course.id, 'TERMINEE');
    await connecterPatron(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée');
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveCount(0);
    await expect(lire(page, 'pilotage-abandons-colonne-action')).toHaveCount(0);
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Réintégrer|Abandonner/ })).toHaveCount(0);
    expect(lireInscriptionEnBase(course.id, 3).statut).toBe('ABANDON');
  });

  test('CA19 - le POST de réintégration sur une course terminée est refusé en 409 COURSE_TERMINEE, même pour une inscription inconnue', async ({ playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const tom = course.coureurs[1];
    expect(await avecApi(playwright, (ctx) => abandonnerParApi(ctx, course.id, tom.inscriptionId, 'MANUEL', ADMIN_MASTER))).toBe(200);
    placerStatutCourseEnBase(course.id, 'TERMINEE');
    const connue = await avecApi(playwright, (ctx) => reintegrerParApi(ctx, course.id, tom.inscriptionId, ADMIN_MASTER));
    expect(connue.statut).toBe(409);
    expect(connue.corps['code']).toBe('COURSE_TERMINEE');
    const inconnue = await avecApi(playwright, (ctx) => reintegrerParApi(ctx, course.id, 'abc', ADMIN_MASTER));
    expect(inconnue.statut).toBe(409);
    expect(lireInscriptionEnBase(course.id, tom.dossard).statut).toBe('ABANDON');
    expect(lirePassagesEnBase(course.id)).toEqual([]);
  });
});
