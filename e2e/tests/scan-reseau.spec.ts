import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { MESSAGE_ATTENTE, MOTIF_PASSAGES, UUID_V4, avancerJusquaRequete, identifiantDe, installerHorloge, laisserPasserLeReseau, probleme, reponseRejeu, routePassagesChronometree } from './aide-file-scan';
import { champ, lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, urlScan, verifierSucces } from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test.describe('Écran de scan, pannes réseau et idempotence', () => {
  // 5.1a (RG17) : une panne n'est plus un message « injoignable » avec revalidation manuelle ; le scan est gardé sur
  // l'appareil et renvoyé automatiquement avec le même identifiantScan.
  test('CA20 - après une réponse 500, le scan est gardé sur l\'appareil puis renvoyé automatiquement avec le même identifiantScan, et la réponse 200 s\'affiche en succès', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, (n, route) =>
      n === 1 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.fulfill(reponseRejeu(un.dossard, un.pseudo)),
    );

    await scannerParEntree(page, un.jeton);
    await expect(page.getByTestId('scan-resultat-attente')).toHaveText(MESSAGE_ATTENTE);
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveCount(0);
    await expect(page.getByTestId('scan-compteur-attente')).toHaveText('1 en attente');
    await expect(champ(page)).toHaveValue('');
    await expect(page.getByTestId('scan-bouton-enregistrer')).toBeEnabled();
    await expect(page.getByTestId('scan-bouton-enregistrer')).toHaveText('Enregistrer le passage');

    await avancerJusquaRequete(page, releve, 2, 1.6, 2.4);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    await expect(page.getByTestId('scan-compteur-attente')).toHaveCount(0);
    expect(releve.identifiants).toHaveLength(2);
    expect(releve.identifiants[1]).toBe(releve.identifiants[0]);
    expect(releve.identifiants[0]).toMatch(UUID_V4);
  });

  test('CA20 - une requête interrompue est gardée sur l\'appareil, puis le rejeu automatique réel n\'écrit qu\'un passage', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, (n, route) => (n === 1 ? route.abort('failed') : route.continue()));

    await scannerParEntree(page, un.jeton);
    await expect(page.getByTestId('scan-resultat-attente')).toHaveText(MESSAGE_ATTENTE);
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveCount(0);
    await expect(champ(page)).toHaveValue('');
    await expect(page.getByTestId('scan-bouton-enregistrer')).toBeEnabled();

    await avancerJusquaRequete(page, releve, 2, 1.6, 2.4);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    expect(releve.identifiants[1]).toBe(releve.identifiants[0]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });

  test('CA20 - après une réponse 409, aucune nouvelle tentative automatique et le scan suivant du même jeton utilise un nouvel identifiantScan', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const identifiants: string[] = [];
    await page.route(MOTIF_PASSAGES, async (route) => {
      identifiants.push(identifiantDe(route));
      return route.fulfill(probleme(409, 'PASSAGE_DEJA_ENREGISTRE', 'Ce coureur a déjà un passage sur la boucle 1.'));
    });

    await scannerParEntree(page, un.jeton);
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('Ce coureur a déjà un passage sur la boucle 1.');
    await page.clock.runFor(60_000);
    await laisserPasserLeReseau(page);
    expect(identifiants).toHaveLength(1);
    await champ(page).press('Enter');
    await expect.poll(() => identifiants.length).toBe(2);
    await expect(page.getByTestId('scan-resultat-erreur')).toBeVisible();
    expect(identifiants[1]).not.toBe(identifiants[0]);
  });

  test('CA20 - pendant l\'envoi, le bouton reste actif, « Envoi… » s\'affiche à part et le champ est libre', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    let liberer!: () => void;
    const attente = new Promise<void>((resolve) => (liberer = resolve));
    await page.route(MOTIF_PASSAGES, async (route) => {
      await attente;
      await route.continue();
    });

    await scannerParEntree(page, un.jeton);
    const bouton = page.getByTestId('scan-bouton-enregistrer');
    await expect(page.getByTestId('scan-resultat-envoi')).toHaveText('Envoi…');
    await expect(bouton).toBeEnabled();
    await expect(bouton).toHaveText('Enregistrer le passage');
    await expect(champ(page)).toHaveValue('');
    await expect(champ(page)).toBeFocused();
    liberer();
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    await expect(bouton).toBeEnabled();
    await expect(bouton).toHaveText('Enregistrer le passage');
  });

  test('CA20 - une réponse 401 renvoie à la connexion avec le retour', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(`/connexion?retour=${encodeURIComponent(urlScan(course.id))}`);
  });

  test('CA20 - une réponse 403 ACCES_REFUSE renvoie à /acces-refuse', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });

  test('CA20 - une réponse 403 BENEVOLE_NON_AFFECTE reste sur l\'écran avec le détail', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(403, 'BENEVOLE_NON_AFFECTE', 'Vous n\'êtes pas affecté à cette course.')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('Vous n\'êtes pas affecté à cette course.');
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
  });
});
