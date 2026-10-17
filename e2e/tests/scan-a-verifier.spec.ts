import { expect, test, type Locator, type Page } from '@playwright/test';
import { MOT_DE_PASSE_BENEVOLE_CREE, affecterBenevolesParApi, exigerIdentifiantsAdminMaster } from './aide-admin';
import { pseudoUnique } from './aide-connexion';
import { placerStatutInscriptionEnBase } from './aide-coureur';
import { avecApi } from './aide-horloge';
import { seDeconnecterMalgreLesScansEnAttente, seDeconnecterParLeMenu } from './aide-entete';
import {
  MESSAGE_ATTENTE,
  MESSAGE_HORS_LIGNE,
  MESSAGE_STOCKAGE,
  MOTIF_PASSAGES,
  attendreBaseLocaleVide,
  attendreFileVide,
  installerHorloge,
  laisserPasserLeReseau,
  lireFile,
  simulerRetourEnLigne,
  suivreExceptions,
  suivrePassages,
} from './aide-file-scan';
import {
  champ,
  creerBenevole,
  lirePassagesEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  seConnecterVers,
  urlScan,
  verifierSucces,
  type CourseDeScan,
} from './aide-scan';

/*
 * Incrément 5.1b : liste « Scans à vérifier », totaux de l'accueil bénévole et confirmation de déconnexion.
 * Une seule API, inchangée (5.1a CA1) : les refus différés sont provoqués par l'état réel de la base ou par une réponse
 * simulée (415). Hors ligne : `context.setOffline` ; panne sans événement hors ligne : `page.route(...).abort`.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const compteur = (page: Page) => page.getByTestId('scan-compteur-attente');
const envoyes = (page: Page) => page.getByTestId('scan-message-envoyes');
const refus = (page: Page) => page.getByTestId('scan-refus');
const liste = (page: Page) => page.getByTestId('scan-a-verifier');
const lignes = (page: Page) => page.getByTestId('scan-a-verifier-ligne');
const compteurAVerifier = (page: Page) => page.getByTestId('scan-a-verifier-compteur');
const motifs = (page: Page) => page.getByTestId('scan-a-verifier-motif');
const comprisBoutons = (page: Page) => page.getByTestId('scan-a-verifier-compris');
const confirmation = (page: Page) => page.getByTestId('entete-deconnexion-confirmation');
const rester = (page: Page) => page.getByTestId('entete-deconnexion-annuler');
const confirmer = (page: Page) => page.getByTestId('entete-deconnexion-confirmer');
const enAttenteAccueil = (page: Page) => page.getByTestId('benevole-en-attente');
const aVerifierAccueil = (page: Page) => page.getByTestId('benevole-a-verifier');

const AIDE = 'Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur.';
const TEXTE_CONFIRMATION_2 =
  '2 scans ne sont pas encore envoyés. Si vous vous déconnectez, ils resteront sur cet appareil et ne partiront qu\'à votre prochaine connexion avec ce compte.';
const TEXTE_CONFIRMATION_1 =
  '1 scan n\'est pas encore envoyé. Si vous vous déconnectez, il restera sur cet appareil et ne partira qu\'à votre prochaine connexion avec ce compte.';

async function scannerEnFile(page: Page, jetons: string[], dejaEnFile = 0): Promise<void> {
  for (const [i, jeton] of jetons.entries()) {
    await scannerParEntree(page, jeton);
    await expect(page.getByTestId('scan-resultat-attente')).toHaveText(MESSAGE_ATTENTE);
    await expect(compteur(page)).toHaveText(`${dejaEnFile + i + 1} en attente`);
    await expect(champ(page)).toHaveValue('');
  }
}

/** Écran de scan ouvert, puis réseau coupé (navigateur hors ligne). */
async function ouvrirHorsLigne(page: Page, course: CourseDeScan): Promise<void> {
  await ouvrirEcranDeScan(page, course);
  await page.context().setOffline(true);
  await expect(page.getByTestId('scan-hors-ligne')).toHaveText(MESSAGE_HORS_LIGNE);
}

/** Écran de scan ouvert ; un jeton inconnu scanné hors ligne puis renvoyé donne une ligne « Jeton inconnu ». */
async function creerUneLigneAVerifier(page: Page, course: CourseDeScan): Promise<void> {
  await ouvrirHorsLigne(page, course);
  await scannerEnFile(page, ['abc']);
  await page.context().setOffline(false);
  await expect(lignes(page)).toHaveCount(1);
  await expect(motifs(page)).toHaveText('Jeton inconnu');
  await attendreFileVide(page);
}

/** Panne sans événement hors ligne : les scans restent en attente (aucun envoi n'aboutit). */
async function mettreEnAttenteParPanne(page: Page, jetons: string[]): Promise<void> {
  await page.route(MOTIF_PASSAGES, (route) => route.abort('failed'));
  for (const [i, jeton] of jetons.entries()) {
    await scannerParEntree(page, jeton);
    await expect(compteur(page)).toHaveText(`${i + 1} en attente`);
  }
  await expect.poll(async () => (await lireFile(page)).length).toBe(jetons.length);
}

async function affecter(playwright: Parameters<typeof creerBenevole>[0], course: CourseDeScan, ...autres: string[]): Promise<void> {
  await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [course.benevole, ...autres]));
}

test.describe('Scans à vérifier, liste persistante', () => {
  test('CA1 - un doublon hors ligne devient une ligne « à vérifier » persistante, sans zone rouge, sans jeton, jamais rejoué', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirHorsLigne(page, course);
    const requetes = suivrePassages(page);

    await scannerEnFile(page, [un.jeton, un.jeton, deux.jeton]);
    await page.context().setOffline(false);

    await expect(envoyes(page)).toHaveText('2 scans envoyés, dont 1 à vérifier');
    await expect(liste(page)).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: 'Scans à vérifier' })).toBeVisible();
    await expect(lignes(page)).toHaveCount(1);
    await expect(compteurAVerifier(page)).toHaveText('1 à vérifier');
    await expect(compteurAVerifier(page)).toHaveAttribute('role', 'status');
    await expect(motifs(page)).toHaveText('Déjà passé');
    await expect(page.getByTestId('scan-a-verifier-dossard')).toHaveText('Dossard 1');
    await expect(page.getByTestId('scan-a-verifier-pseudo')).toHaveText(un.pseudo);
    await expect(page.getByTestId('scan-a-verifier-boucle')).toHaveText('Boucle 1');
    await expect(page.getByTestId('scan-a-verifier-detail')).toHaveText('Ce coureur a déjà un passage sur la boucle 1.');
    await expect(page.getByTestId('scan-a-verifier-aide')).toHaveCount(0);
    await expect(refus(page)).toHaveCount(0);
    await expect(compteur(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Compris' })).toHaveCount(1);

    await page.clock.runFor(10_000);
    await laisserPasserLeReseau(page);
    expect(requetes.map((r) => r.jetonQr)).toEqual([un.jeton, un.jeton, deux.jeton]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    await attendreFileVide(page);

    const entrees = await lireFile(page);
    expect(entrees).toHaveLength(1);
    expect(entrees[0].etat).toBe('A_VERIFIER');
    expect(entrees[0].code).toBe('PASSAGE_DEJA_ENREGISTRE');
    expect('jetonQr' in entrees[0]).toBe(false);
    expect(JSON.stringify(entrees)).not.toContain(un.jeton);

    // Un refus en direct reste en zone rouge et n'ajoute aucune ligne à la liste.
    await scannerParEntree(page, un.jeton);
    await expect(page.getByTestId('scan-refus-motif')).toHaveText('Déjà passé');
    await expect(lignes(page)).toHaveCount(1);
    await expect(compteurAVerifier(page)).toHaveText('1 à vérifier');
    await attendreFileVide(page);
    expect((await lireFile(page)).filter((e) => e.etat === 'A_VERIFIER')).toHaveLength(1);

    // La ligne survit au rechargement ; « Compris » la supprime définitivement.
    await page.reload();
    await expect(champ(page)).toBeVisible();
    await expect(lignes(page)).toHaveCount(1);
    await expect(motifs(page)).toHaveText('Déjà passé');
    await comprisBoutons(page).click();
    await expect(liste(page)).toHaveCount(0);
    await attendreBaseLocaleVide(page);
    await page.reload();
    await expect(champ(page)).toBeVisible();
    await expect(liste(page)).toHaveCount(0);
  });

  test('CA1 - un refus différé du dernier scan de l\'écran vide la zone de retour (aucune zone rouge ni message de mise en attente)', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirHorsLigne(page, course);
    await scannerEnFile(page, [un.jeton, un.jeton]);
    await page.context().setOffline(false);
    await expect(lignes(page)).toHaveCount(1);
    await expect(envoyes(page)).toHaveText('1 scan envoyé, dont 1 à vérifier');
    await expect(refus(page)).toHaveCount(0);
    await expect(page.getByTestId('scan-resultat-attente')).toHaveCount(0);
  });

  test('CA2 - plusieurs refus dans un même cycle : une ligne chacun, la file continue, « Compris » déplace le focus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    await ouvrirHorsLigne(page, course);
    const requetes = suivrePassages(page);
    await scannerEnFile(page, [un.jeton, 'abc', deux.jeton]);
    placerStatutInscriptionEnBase(un.inscriptionId, 'ABANDON');
    await page.context().setOffline(false);

    await expect(envoyes(page)).toHaveText('1 scan envoyé, dont 2 à vérifier');
    await expect(lignes(page)).toHaveCount(2);
    await expect(compteurAVerifier(page)).toHaveText('2 à vérifier');
    await expect(refus(page)).toHaveCount(0);
    expect(requetes.map((r) => r.jetonQr)).toEqual([un.jeton, 'abc', deux.jeton]);
    expect(lirePassagesEnBase(course.id)).toEqual(['2:1:SCAN']);
    const texte = (await lignes(page).allInnerTexts()).join('\n');
    expect(texte).toContain('Coureur en abandon');
    expect(texte).toContain('Jeton inconnu');
    // L'aide de réintégration n'accompagne que la ligne du coureur en abandon.
    await expect(page.getByTestId('scan-a-verifier-aide')).toHaveCount(1);
    await expect(page.getByTestId('scan-a-verifier-aide')).toHaveText(AIDE);

    // Le bouton « Compris » est nommé « Compris » et décrit par le motif de sa ligne.
    await expect(page.getByRole('button', { name: 'Compris' })).toHaveCount(2);
    await expect(page.getByRole('button', { name: 'Compris', description: 'Coureur en abandon' })).toHaveCount(1);

    await comprisBoutons(page).first().click();
    await expect(lignes(page)).toHaveCount(1);
    await expect(compteurAVerifier(page)).toHaveText('1 à vérifier');
    await expect(comprisBoutons(page).first()).toBeFocused();
    await comprisBoutons(page).first().click();
    await expect(liste(page)).toHaveCount(0);
    await attendreBaseLocaleVide(page);
  });

  test('CA2 - un autre 4xx reçu en différé est une ligne « Scan refusé », jamais retentée, « 1 scan à vérifier » sans envoi réussi', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirHorsLigne(page, course);
    const requetes = suivrePassages(page);
    await scannerEnFile(page, [un.jeton]);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill({ status: 415, contentType: 'text/plain', body: 'Type non géré' }));
    await page.context().setOffline(false);

    await expect(envoyes(page)).toHaveText('1 scan à vérifier');
    await expect(motifs(page)).toHaveText('Scan refusé');
    await expect(page.getByTestId('scan-a-verifier-detail')).toHaveText('Le serveur a refusé ce scan.');
    await expect(page.getByTestId('scan-a-verifier-aide')).toHaveCount(0);
    await expect(refus(page)).toHaveCount(0);
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(1);
    expect(lirePassagesEnBase(course.id)).toEqual([]);
    const entrees = await lireFile(page);
    expect(entrees.map((e) => e.etat)).toEqual(['A_VERIFIER']);
    expect('jetonQr' in entrees[0]).toBe(false);
  });

  test('CA2 - deux refus sans envoi réussi : « 2 scans à vérifier »', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirHorsLigne(page, course);
    await scannerEnFile(page, ['abc', 'def']);
    await page.context().setOffline(false);
    await expect(envoyes(page)).toHaveText('2 scans à vérifier');
    await expect(lignes(page)).toHaveCount(2);
  });
});

test.describe('Scans à vérifier, totaux de l\'accueil bénévole', () => {
  test('CA5 - totaux « en attente » et « à vérifier » du Compte, toutes Courses confondues ; Marc ne voit rien', async ({ page, playwright }) => {
    const x = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const w = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, benevole: x.benevole });
    const marc = await creerBenevole(playwright);
    await ouvrirEcranDeScan(page, x);
    await page.route(MOTIF_PASSAGES, (route) => route.abort('failed'));
    await scannerParEntree(page, x.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await page.goto(urlScan(w.id));
    await expect(champ(page)).toBeVisible();
    await scannerParEntree(page, w.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await scannerParEntree(page, w.coureurs[1].jeton);
    await expect(compteur(page)).toHaveText('2 en attente');
    await expect.poll(async () => (await lireFile(page)).length).toBe(3);

    await page.goto('/benevole');
    await expect(enAttenteAccueil(page)).toHaveText('3 scans en attente');
    await expect(enAttenteAccueil(page)).toHaveAttribute('role', 'status');
    await expect(aVerifierAccueil(page)).toHaveCount(0);

    // Retour en ligne depuis l'accueil : l'envoi se fait sans ouvrir l'écran de scan.
    await page.unroute(MOTIF_PASSAGES);
    await simulerRetourEnLigne(page.context());
    await expect(enAttenteAccueil(page)).toHaveCount(0);
    await attendreFileVide(page);
    await expect(aVerifierAccueil(page)).toHaveCount(0);
    expect(lirePassagesEnBase(x.id)).toEqual(['1:1:SCAN']);
    expect(lirePassagesEnBase(w.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);

    // Un jeton de W scanné dans X est refusé pour X : « 1 à vérifier » sur l'accueil, ligne sur l'écran de X seulement.
    await page.goto(urlScan(x.id));
    await expect(champ(page)).toBeVisible();
    await page.context().setOffline(true);
    await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
    await scannerEnFile(page, [w.coureurs[0].jeton]);
    await page.context().setOffline(false);
    await expect(lignes(page)).toHaveCount(1);
    await expect(motifs(page)).toHaveText('Jeton inconnu');
    await attendreFileVide(page);
    await page.goto('/benevole');
    await expect(aVerifierAccueil(page)).toHaveText('1 à vérifier');
    await expect(aVerifierAccueil(page)).toHaveAttribute('role', 'status');
    await expect(enAttenteAccueil(page)).toHaveCount(0);
    await page.goto(urlScan(w.id));
    await expect(champ(page)).toBeVisible();
    await expect(liste(page)).toHaveCount(0);
    await page.goto(urlScan(x.id));
    await expect(lignes(page)).toHaveCount(1);

    // Marc, sur le même appareil, ne voit ni total ni ligne.
    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await seConnecterVers(page, marc, MOT_DE_PASSE_BENEVOLE_CREE, '/benevole');
    await expect(page.getByTestId('accueil-benevole-titre')).toBeVisible();
    await expect(enAttenteAccueil(page)).toHaveCount(0);
    await expect(aVerifierAccueil(page)).toHaveCount(0);
    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);

    // Léo reconnecté : « Compris » sur X fait disparaître le total.
    await seConnecterVers(page, x.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(x.id));
    await expect(lignes(page)).toHaveCount(1);
    await comprisBoutons(page).click();
    await expect(liste(page)).toHaveCount(0);
    await page.goto('/benevole');
    await expect(page.getByTestId('accueil-benevole-titre')).toBeVisible();
    await expect(aVerifierAccueil(page)).toHaveCount(0);
    await expect(enAttenteAccueil(page)).toHaveCount(0);
  });

  test('CA5 - le total « 1 scan en attente » est au singulier', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await mettreEnAttenteParPanne(page, [course.coureurs[0].jeton]);
    await page.goto('/benevole');
    await expect(enAttenteAccueil(page)).toHaveText('1 scan en attente');
  });
});

test.describe('Scans à vérifier, confirmation de déconnexion', () => {
  test('CA6 - deux scans en attente : confirmation (focus sur « Rester connecté »), « Rester connecté » annule, « Me déconnecter quand même » garde la file, la reconnexion les envoie', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    await mettreEnAttenteParPanne(page, course.coureurs.map((c) => c.jeton));

    await seDeconnecterParLeMenu(page);
    await expect(confirmation(page)).toBeVisible();
    await expect(confirmation(page)).toHaveAttribute('role', 'alert');
    await expect(confirmation(page)).toContainText(TEXTE_CONFIRMATION_2);
    await expect(rester(page)).toHaveText('Rester connecté');
    await expect(confirmer(page)).toHaveText('Me déconnecter quand même');
    await expect(rester(page)).toBeFocused();
    await expect(page.getByTestId('entete-pseudo')).toHaveText(course.benevole);

    await rester(page).click();
    await expect(confirmation(page)).toHaveCount(0);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(course.benevole);
    expect(await lireFile(page)).toHaveLength(2);
    await expect(compteur(page)).toHaveText('2 en attente');

    await seDeconnecterMalgreLesScansEnAttente(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await expect(confirmation(page)).toHaveCount(0);
    await expect(page).toHaveURL(/\/connexion/);
    const entrees = await lireFile(page);
    expect(entrees).toHaveLength(2);
    expect(entrees.every((e) => e.etat !== 'A_VERIFIER')).toBe(true);
    const avant = requetes.length;
    await laisserPasserLeReseau(page);
    expect(lirePassagesEnBase(course.id)).toEqual([]);

    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
    await expect(champ(page)).toBeVisible();
    await expect(compteur(page)).toHaveText('2 en attente');
    expect(requetes.length).toBeGreaterThanOrEqual(avant);
    await page.unroute(MOTIF_PASSAGES);
    await page.getByTestId('scan-renvoyer').click();
    await expect(compteur(page)).toHaveCount(0);
    await expect.poll(() => lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
  });

  test('CA6 - un seul scan en attente : texte au singulier', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await mettreEnAttenteParPanne(page, [course.coureurs[0].jeton]);
    await seDeconnecterParLeMenu(page);
    await expect(confirmation(page)).toContainText(TEXTE_CONFIRMATION_1);
    await expect(rester(page)).toBeFocused();
  });

  test('CA6 - sans scan en attente, « Se déconnecter » déconnecte immédiatement', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await expect(confirmation(page)).toHaveCount(0);
  });

  test('CA6 - avec seulement des lignes « à vérifier », aucune confirmation', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await creerUneLigneAVerifier(page, course);
    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await expect(confirmation(page)).toHaveCount(0);
    expect((await lireFile(page)).map((e) => e.etat)).toEqual(['A_VERIFIER']);
  });

  test('CA6 - Échap équivaut à « Rester connecté » et le focus revient au bouton du menu', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await mettreEnAttenteParPanne(page, [course.coureurs[0].jeton]);
    await seDeconnecterParLeMenu(page);
    await expect(rester(page)).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(confirmation(page)).toHaveCount(0);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(course.benevole);
    await expect(page.getByTestId('bouton-menu')).toBeFocused();
    expect(await lireFile(page)).toHaveLength(1);
  });

  test('CA6 - le nombre de la confirmation est figé au clic, même si la file se vide ensuite', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    await ouvrirEcranDeScan(page, course);
    await mettreEnAttenteParPanne(page, course.coureurs.map((c) => c.jeton));
    await seDeconnecterParLeMenu(page);
    await expect(confirmation(page)).toContainText(TEXTE_CONFIRMATION_2);

    await page.unroute(MOTIF_PASSAGES);
    await simulerRetourEnLigne(page.context());
    await expect.poll(() => lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    await attendreFileVide(page);
    await expect(confirmation(page)).toContainText(TEXTE_CONFIRMATION_2);
    await rester(page).click();
    await expect(confirmation(page)).toHaveCount(0);
  });

  test('CA9 - parcours de la roadmap : succès, doublon hors ligne dans « Scans à vérifier », puis confirmation à la déconnexion', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, un.jeton);
    await verifierSucces(page, un.dossard, un.pseudo, 1);

    await page.context().setOffline(true);
    await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
    await scannerEnFile(page, [un.jeton]);
    await page.context().setOffline(false);
    await expect(liste(page)).toBeVisible();
    await expect(motifs(page)).toHaveText('Déjà passé');
    await expect(page.getByTestId('scan-a-verifier-dossard')).toHaveText('Dossard 1');
    await attendreFileVide(page);

    await page.context().setOffline(true);
    await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
    await scannerEnFile(page, [deux.jeton]);
    await seDeconnecterParLeMenu(page);
    await expect(confirmation(page)).toBeVisible();
    await expect(confirmation(page)).toContainText(TEXTE_CONFIRMATION_1);
    await expect(rester(page)).toBeFocused();
    await rester(page).click();
    await expect(page.getByTestId('entete-pseudo')).toHaveText(course.benevole);
  });
});

test.describe('Scans à vérifier, isolation par Compte et stockage', () => {
  test('CA7 - Marc ne voit ni liste ni total ; Léo reconnecté retrouve sa ligne et son total', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const marc = await creerBenevole(playwright);
    await affecter(playwright, course, marc);
    await creerUneLigneAVerifier(page, course);

    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await seConnecterVers(page, marc, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
    await expect(champ(page)).toBeVisible();
    await expect(liste(page)).toHaveCount(0);
    await page.goto('/benevole');
    await expect(page.getByTestId('accueil-benevole-titre')).toBeVisible();
    await expect(aVerifierAccueil(page)).toHaveCount(0);
    await expect(enAttenteAccueil(page)).toHaveCount(0);
    expect((await lireFile(page)).map((e) => e.etat)).toEqual(['A_VERIFIER']);

    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
    await expect(lignes(page)).toHaveCount(1);
    await expect(motifs(page)).toHaveText('Jeton inconnu');
    await page.goto('/benevole');
    await expect(aVerifierAccueil(page)).toHaveText('1 à vérifier');
  });

  test('CA7 - IndexedDB indisponible : la ligne existe en mémoire, perdue au rechargement, l\'avertissement de stockage reste affiché', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await page.addInitScript(() => {
      IDBFactory.prototype.open = function () {
        throw new DOMException('IndexedDB indisponible (simulé)', 'SecurityError');
      };
    });
    const exceptions = suivreExceptions(page);
    await ouvrirHorsLigne(page, course);
    const avertissement = page.getByTestId('scan-avertissement-stockage');
    await expect(avertissement).toHaveText(MESSAGE_STOCKAGE);
    await scannerEnFile(page, ['abc']);
    await page.context().setOffline(false);
    await expect(lignes(page)).toHaveCount(1);
    await expect(motifs(page)).toHaveText('Jeton inconnu');
    await expect(avertissement).toBeVisible();

    await page.reload();
    await expect(champ(page)).toBeVisible();
    await expect(liste(page)).toHaveCount(0);
    await expect(avertissement).toBeVisible();
    expect(exceptions.map((e) => e.message)).toEqual([]);
  });
});

test.describe('Scans à vérifier, accessibilité et mise en page', () => {
  const configurations = [
    { nom: '375 px', largeur: 375, zoom: false },
    { nom: '320 px', largeur: 320, zoom: false },
    { nom: '320 px avec texte à 200 %', largeur: 320, zoom: true },
  ];

  function luminance([r, g, b]: number[]): number {
    const lineaire = (v: number) => (v / 255 <= 0.03928 ? v / 255 / 12.92 : ((v / 255 + 0.055) / 1.055) ** 2.4);
    return 0.2126 * lineaire(r) + 0.7152 * lineaire(g) + 0.0722 * lineaire(b);
  }

  async function mesurer(cible: Locator) {
    return cible.evaluate((el) => {
      const style = getComputedStyle(el);
      const avant = getComputedStyle(el, '::before');
      const rect = el.getBoundingClientRect();
      const sonde = document.createElement('p');
      sonde.style.backgroundColor = 'var(--couleur-orange-pale)';
      sonde.style.fontSize = 'var(--taille-moyenne)';
      document.body.appendChild(sonde);
      const fondCharte = getComputedStyle(sonde).backgroundColor;
      const tailleMoyenne = getComputedStyle(sonde).fontSize;
      sonde.remove();
      const nombres = (couleur: string) => (couleur.match(/[\d.]+/g) ?? []).map(Number);
      return {
        fond: style.backgroundColor,
        fondCharte,
        tailleMoyenne,
        taille: style.fontSize,
        couleur: nombres(style.color),
        fondRgb: nombres(style.backgroundColor),
        contenuAvant: avant.content,
        masque: avant.maskImage !== 'none' || avant.getPropertyValue('-webkit-mask-image') !== 'none',
        animation: style.animationName,
        transition: style.transitionDuration,
        hauteur: rect.height,
        largeur: rect.width,
        debordeDroite: rect.right > window.innerWidth + 1,
        debordeGauche: rect.left < -1,
      };
    });
  }

  const pasDeDefilement = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);

  async function verifierPictogramme(cible: Locator, nom: string) {
    const m = await mesurer(cible);
    expect((await cible.innerText()).trim().length, `libellé de ${nom}`).toBeGreaterThan(0);
    expect(m.contenuAvant, `pictogramme de ${nom}`).not.toBe('none');
    expect(m.masque, `masque de ${nom}`).toBe(true);
    expect(m.animation, `animation de ${nom}`).toBe('none');
    expect(m.transition === '0s' || m.transition === '', `transition de ${nom}`).toBe(true);
    expect(m.debordeDroite, `débordement de ${nom}`).toBe(false);
    expect(m.debordeGauche, `débordement de ${nom}`).toBe(false);
    return m;
  }

  async function verifierBoutonAccessible(page: Page, bouton: Locator, nom: string) {
    const boite = await bouton.boundingBox();
    expect(boite!.height, `hauteur de ${nom}`).toBeGreaterThanOrEqual(44);
    expect(boite!.width, `largeur de ${nom}`).toBeGreaterThanOrEqual(44);
    await bouton.scrollIntoViewIfNeeded();
    const dansLeCadre = await bouton.evaluate((el) => {
      const r = el.getBoundingClientRect();
      return r.left >= -1 && r.right <= window.innerWidth + 1;
    });
    expect(dansLeCadre, `${nom} dans la largeur`).toBe(true);
    // Modalité clavier : un focus posé par script après un clic souris n'affiche pas :focus-visible.
    if (await bouton.evaluate((el) => el === document.activeElement)) {
      await page.keyboard.press('Tab');
      await page.keyboard.press('Shift+Tab');
    }
    for (let i = 0; i < 30 && !(await bouton.evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('Tab');
    await expect(bouton, `${nom} atteignable au clavier`).toBeFocused();
    const focus = await bouton.evaluate((el) => {
      const s = getComputedStyle(el);
      return (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || s.boxShadow !== 'none';
    });
    expect(focus, `focus visible de ${nom}`).toBe(true);
  }

  for (const configuration of configurations) {
    test(`CA8 - à ${configuration.nom} : aucun défilement horizontal, cibles de 44 px, pseudo de 30 caractères coupé, pictogramme + libellé, contraste, sans animation`, async ({ page, playwright }) => {
      const pseudoLong = `${pseudoUnique('c').replace(/-/g, '')}${'x'.repeat(30)}`.slice(0, 30);
      expect(pseudoLong).toHaveLength(30);
      const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, pseudos: [pseudoLong] });
      const [un] = course.coureurs;
      await page.setViewportSize({ width: configuration.largeur, height: 800 });
      await ouvrirEcranDeScan(page, course);
      if (configuration.zoom) await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
      await page.emulateMedia({ reducedMotion: 'reduce' });

      await scannerParEntree(page, un.jeton);
      await verifierSucces(page, un.dossard, un.pseudo, 1);
      await page.context().setOffline(true);
      await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
      await scannerEnFile(page, [un.jeton]);
      await page.context().setOffline(false);
      await expect(lignes(page)).toHaveCount(1);
      await attendreFileVide(page);
      // Un scan en attente (panne sans événement hors ligne) pour la confirmation et l'accueil.
      await page.route(MOTIF_PASSAGES, (route) => route.abort('failed'));
      await scannerParEntree(page, 'abc');
      await expect(compteur(page)).toHaveText('1 en attente');

      // Écran de scan : liste.
      expect(await pasDeDefilement(page)).toBe(true);
      await expect(page.getByTestId('scan-a-verifier-pseudo')).toHaveText(pseudoLong);
      const pseudo = await mesurer(page.getByTestId('scan-a-verifier-pseudo'));
      expect(pseudo.debordeDroite, 'pseudo de 30 caractères dans la largeur').toBe(false);
      const ligne = await mesurer(lignes(page).first());
      expect(ligne.fond, 'fond de la ligne issu de la charte').toBe(ligne.fondCharte);
      const rapport = (luminance(ligne.fondRgb) + 0.05) / (luminance(ligne.couleur) + 0.05);
      expect(Math.max(rapport, 1 / rapport), 'contraste de la ligne').toBeGreaterThanOrEqual(4.5);
      await verifierPictogramme(page.getByTestId('scan-a-verifier-motif'), 'motif');
      const c = await verifierPictogramme(compteurAVerifier(page), 'compteur « à vérifier »');
      expect(c.taille, 'compteur en --taille-moyenne').toBe(c.tailleMoyenne);
      await expect(compteurAVerifier(page)).toHaveAttribute('role', 'status');
      await expect(page.getByTestId('scan-a-verifier')).toHaveAttribute('aria-labelledby', /.+/);
      await verifierBoutonAccessible(page, comprisBoutons(page).first(), '« Compris »');
      expect(await pasDeDefilement(page)).toBe(true);

      // Confirmation de déconnexion (un scan en attente).
      await seDeconnecterParLeMenu(page);
      await expect(confirmation(page)).toBeVisible();
      await expect(confirmation(page)).toHaveAttribute('role', 'alert');
      expect(await pasDeDefilement(page)).toBe(true);
      const bloc = await mesurer(confirmation(page));
      expect(bloc.fond, 'fond de la confirmation issu de la charte').toBe(bloc.fondCharte);
      await verifierPictogramme(confirmation(page).locator('p').first(), 'texte de la confirmation');
      await expect(rester(page)).toBeFocused();
      await verifierBoutonAccessible(page, rester(page), '« Rester connecté »');
      await verifierBoutonAccessible(page, confirmer(page), '« Me déconnecter quand même »');
      expect(await pasDeDefilement(page)).toBe(true);
      await rester(page).click();

      // Accueil bénévole : les deux totaux.
      await page.goto('/benevole');
      await expect(enAttenteAccueil(page)).toHaveText('1 scan en attente');
      await expect(aVerifierAccueil(page)).toHaveText('1 à vérifier');
      expect(await pasDeDefilement(page)).toBe(true);
      for (const [cible, nom] of [
        [enAttenteAccueil(page), 'total en attente'],
        [aVerifierAccueil(page), 'total à vérifier'],
      ] as const) {
        const m = await verifierPictogramme(cible, nom);
        expect(m.fond, `fond de ${nom} issu de la charte`).toBe(m.fondCharte);
        const r = (luminance(m.fondRgb) + 0.05) / (luminance(m.couleur) + 0.05);
        expect(Math.max(r, 1 / r), `contraste de ${nom}`).toBeGreaterThanOrEqual(4.5);
        await expect(cible).toHaveAttribute('role', 'status');
      }
    });
  }
});
