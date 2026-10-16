import { expect, test, type Locator, type Page, type Route } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { MOT_DE_PASSE_BENEVOLE_CREE, exigerIdentifiantsAdminMaster } from './aide-admin';
import { placerStatutCourseEnBase, placerStatutInscriptionEnBase } from './aide-coureur';
import {
  CAMERA_ARRIERE,
  CAMERA_AVANT,
  basculerVisibilite,
  changerMode,
  debrancher,
  etatCamera,
  installerCameraSimulee,
  presenterQr,
  retirerQr,
  type ModeCamera,
  type OptionsCamera,
} from './aide-camera';
import {
  champ,
  lirePassagesEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  seConnecterVers,
  urlScan,
  verifierSucces,
  type CourseDeScan,
} from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const MOTIF_PASSAGES = '**/api/benevole/courses/*/passages';
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const camera = (page: Page) => page.getByTestId('scan-camera');
const etat = (page: Page) => page.getByTestId('scan-camera-etat');
const activer = (page: Page) => page.getByTestId('scan-camera-activer');
const arreter = (page: Page) => page.getByTestId('scan-camera-arreter');
const basculer = (page: Page) => page.getByTestId('scan-camera-basculer');
const reessayer = (page: Page) => page.getByTestId('scan-camera-reessayer');
const apercu = (page: Page) => page.getByTestId('scan-camera-video');
const messageCamera = (page: Page) => page.getByTestId('scan-camera-message');
const erreur = (page: Page) => page.getByTestId('scan-resultat-erreur');

interface EnvoiPassage {
  jetonQr: string;
  identifiantScan: string;
  [cle: string]: unknown;
}

/** Corps des requêtes de Passage émises par la page, dans l'ordre. */
function suivreEnvois(page: Page): EnvoiPassage[] {
  const envois: EnvoiPassage[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/passages')) envois.push(r.postDataJSON() as EnvoiPassage);
  });
  return envois;
}

const probleme = (status: number, code: string, detail = 'Détail simulé.') => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ title: 'Simulé', status, code, detail }),
});

function reponseRejeu(dossard: number, pseudo: string) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      passageId: '11111111-1111-4111-8111-111111111111',
      inscriptionId: '22222222-2222-4222-8222-222222222222',
      dossard,
      pseudo,
      numeroBoucle: 1,
      scanneLe: new Date().toISOString(),
      origine: 'SCAN',
    }),
  };
}

/** Course de référence (2 coureurs) et écran de scan ouvert avec une caméra simulée. */
async function ouvrirAvecCamera(
  page: Page,
  playwright: Parameters<typeof preparerCourseDeScan>[0],
  options: OptionsCamera = {},
  nombreCoureurs = 2,
): Promise<CourseDeScan> {
  await installerCameraSimulee(page, options);
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs });
  await ouvrirEcranDeScan(page, course);
  return course;
}

async function activerCamera(page: Page): Promise<void> {
  await activer(page).click();
  await expect(etat(page)).toHaveText('Caméra active');
  await expect(apercu(page)).toBeVisible();
}

/** Retire le QR puis attend la durée du critère (absence consécutive supérieure à 1,5 s). */
async function retirerPendant(page: Page, millisecondes: number): Promise<void> {
  await retirerQr(page);
  // Durée imposée par le critère (RG9) : l'absence consécutive du QR devant la caméra.
  await page.waitForTimeout(millisecondes);
}

test.describe('Scan à la caméra, activation et lecture', () => {
  test('CA1 - la caméra n\'est jamais demandée à l\'ouverture, seulement au clic, sans audio et de préférence arrière', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright);
    await expect(camera(page)).toBeVisible();
    await expect(etat(page)).toHaveText('Caméra arrêtée');
    await expect(activer(page)).toBeVisible();
    await expect(activer(page)).toHaveText('Activer la caméra');
    await expect(champ(page)).toBeVisible();
    expect((await etatCamera(page)).demandes).toBe(0);

    await page.reload();
    await expect(etat(page)).toHaveText('Caméra arrêtée');
    expect((await etatCamera(page)).demandes).toBe(0);

    await activer(page).click();
    await expect(apercu(page)).toBeVisible();
    await expect(etat(page)).toHaveText('Caméra active');
    await expect(arreter(page)).toBeVisible();
    await expect(activer(page)).toHaveCount(0);
    const demandes = await etatCamera(page);
    expect(demandes.demandes).toBe(1);
    expect(demandes.contraintes[0].audio).toBe(false);
    expect(demandes.contraintes[0].video).toEqual({ facingMode: { ideal: 'environment' } });

    // Le champ de saisie reste utilisable caméra active.
    await scannerParEntree(page, course.coureurs[0].jeton);
    await verifierSucces(page, 1, course.coureurs[0].pseudo, 1);
  });

  test('CA1 - une course en préparation, une course terminée et une course non affectée n\'ont pas de bloc caméra ni de demande', async ({ page, playwright }) => {
    await installerCameraSimulee(page);
    const preparation = await preparerCourseDeScan(playwright, { nombreCoureurs: 0, demarree: false });
    await seConnecterVers(page, preparation.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(preparation.id));
    await expect(page.getByTestId('scan-message-etat')).toHaveText('La course n\'est pas démarrée.');
    await expect(camera(page)).toHaveCount(0);

    const terminee = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, benevole: preparation.benevole });
    placerStatutCourseEnBase(terminee.id, 'TERMINEE');
    await page.goto(urlScan(terminee.id));
    await expect(page.getByTestId('scan-message-etat')).toHaveText('La course est terminée.');
    await expect(camera(page)).toHaveCount(0);

    const nonAffectee = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await page.goto(urlScan(nonAffectee.id));
    await expect(page.getByTestId('scan-message-etat')).toHaveText('Vous n\'êtes pas affecté à cette course.');
    await expect(camera(page)).toHaveCount(0);

    expect((await etatCamera(page)).demandes).toBe(0);
  });

  test('CA2 - le QR présenté à la caméra enregistre le passage comme une saisie, sans toucher au champ ni conserver le jeton', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright);
    const [un, deux] = course.coureurs;
    const envois = suivreEnvois(page);
    await activerCamera(page);

    await presenterQr(page, un.jeton);
    await expect(page.getByTestId('scan-resultat-succes')).toBeVisible({ timeout: 5000 });
    await verifierSucces(page, 1, un.pseudo, 1);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
    expect(envois).toHaveLength(1);
    expect(envois[0].jetonQr).toBe(un.jeton);
    expect(envois[0].identifiantScan).toMatch(UUID_V4);
    expect(Object.keys(envois[0]).sort()).toEqual(['ageDuScanMillisecondes', 'identifiantScan', 'jetonQr']);
    // 5.1a (RG3, RG16) : le corps porte désormais l'âge du scan (entier de 0 à 5000 ms pour un scan en direct).
    expect(Number.isInteger(envois[0].ageDuScanMillisecondes)).toBe(true);
    expect(envois[0].ageDuScanMillisecondes as number).toBeGreaterThanOrEqual(0);
    expect(envois[0].ageDuScanMillisecondes as number).toBeLessThanOrEqual(5000);
    await expect(champ(page)).toHaveValue('');
    await expect(champ(page)).not.toBeFocused();
    expect(page.url()).not.toContain(un.jeton);
    expect(await page.content()).not.toContain(un.jeton);
    expect(await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]))).not.toContain(un.jeton);

    await presenterQr(page, deux.jeton);
    await expect(page.getByTestId('scan-resultat-dossard')).toHaveText('Dossard 2', { timeout: 5000 });
    await verifierSucces(page, 2, deux.pseudo, 1);
    await expect(page.getByTestId('scan-resultat-succes')).toHaveCount(1);
    await expect(erreur(page)).toHaveCount(0);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    for (const { jeton } of [un, deux]) {
      expect(page.url()).not.toContain(jeton);
      expect(await page.content()).not.toContain(jeton);
      expect(await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]))).not.toContain(jeton);
    }
    await expect(champ(page)).toHaveValue('');
    await expect(champ(page)).not.toBeFocused();
  });

  test('CA3 - un QR maintenu ne part qu\'une fois ; retiré 600 ms il ne repart pas ; retiré 2 s il repart et le serveur le refuse', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright);
    const [un] = course.coureurs;
    const envois = suivreEnvois(page);
    await activerCamera(page);

    await presenterQr(page, un.jeton);
    await verifierSucces(page, 1, un.pseudo, 1);
    // Durée du critère : le QR reste 5 s devant la caméra.
    await page.waitForTimeout(5000);
    expect(envois).toHaveLength(1);
    await expect(page.getByTestId('scan-resultat-succes')).toBeVisible();
    await expect(erreur(page)).toHaveCount(0);

    await retirerPendant(page, 600);
    await presenterQr(page, un.jeton);
    // Fenêtre d'observation d'une absence d'envoi : plusieurs analyses (8 par seconde au plus).
    await page.waitForTimeout(1000);
    expect(envois).toHaveLength(1);
    await expect(erreur(page)).toHaveCount(0);

    await retirerPendant(page, 2000);
    await presenterQr(page, un.jeton);
    await expect(erreur(page)).toHaveText('Ce coureur a déjà un passage sur la boucle 1.', { timeout: 5000 });
    await expect(erreur(page)).toHaveAttribute('role', 'alert');
    expect(envois).toHaveLength(2);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });

  test('CA4 - la saisie et la caméra n\'attendent plus l\'envoi : les scans sont mis en file au fil de l\'eau puis envoyés un à la fois, dans l\'ordre', async ({ page, playwright }) => {
    // 5.1a (RG1, RG4, RG17) : remplace « un seul envoi à la fois, caméra et saisie confondues » ; l'envoi reste unique.
    const course = await ouvrirAvecCamera(page, playwright);
    const [un, deux] = course.coureurs;
    const jetons: string[] = [];
    let enVol = 0;
    let maximumEnVol = 0;
    await page.route(MOTIF_PASSAGES, async (route: Route) => {
      jetons.push((route.request().postDataJSON() as EnvoiPassage).jetonQr);
      enVol++;
      maximumEnVol = Math.max(maximumEnVol, enVol);
      const reponse = await route.fetch();
      await new Promise((resolve) => setTimeout(resolve, 2000));
      await route.fulfill({ response: reponse });
      enVol--;
    });
    await activerCamera(page);

    await presenterQr(page, un.jeton);
    await expect.poll(() => jetons.length, { timeout: 5000 }).toBe(1);
    const bouton = page.getByTestId('scan-bouton-enregistrer');
    await expect(bouton).toBeEnabled();
    await expect(bouton).toHaveText('Enregistrer le passage');
    // La saisie n'est pas bloquée par l'envoi en cours : le champ est libéré tout de suite.
    await champ(page).fill(un.jeton);
    await champ(page).press('Enter');
    await expect(champ(page)).toHaveValue('');
    await presenterQr(page, deux.jeton);

    await expect(page.getByTestId('scan-resultat-dossard')).toHaveText('Dossard 2', { timeout: 15_000 });
    expect(jetons).toEqual([un.jeton, un.jeton, deux.jeton]);
    expect(maximumEnVol).toBe(1);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    await expect(bouton).toBeEnabled();
  });

  test('CA5 - un QR étranger n\'est jamais envoyé et son message disparaît ; un jeton inconnu de 43 caractères est envoyé', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright);
    const envois = suivreEnvois(page);
    await activerCamera(page);

    await presenterQr(page, 'https://exemple.org');
    await expect(messageCamera(page)).toHaveText('Ce QR code n\'est pas un dossard.', { timeout: 5000 });
    await expect(messageCamera(page)).toHaveAttribute('role', 'status');
    expect(envois).toHaveLength(0);
    // Le QR reste maintenu : le message s'efface au bout de 3 s et n'est pas répété.
    await expect(messageCamera(page)).toHaveCount(0, { timeout: 6000 });
    // Fenêtre d'observation d'une absence de réapparition et d'envoi.
    await page.waitForTimeout(1000);
    await expect(messageCamera(page)).toHaveCount(0);
    expect(envois).toHaveLength(0);

    await presenterQr(page, 'https://autre.example.org/x');
    await expect(messageCamera(page)).toBeVisible({ timeout: 5000 });
    const un = course.coureurs[0];
    await presenterQr(page, un.jeton);
    await verifierSucces(page, 1, un.pseudo, 1);
    await expect(messageCamera(page)).toHaveCount(0);
    expect(envois.map((e) => e.jetonQr)).toEqual([un.jeton]);

    const inconnu = randomBytes(32).toString('base64url');
    expect(inconnu).toHaveLength(43);
    await presenterQr(page, inconnu);
    await expect(erreur(page)).toHaveText('Jeton inconnu pour cette course.', { timeout: 5000 });
    expect(envois.map((e) => e.jetonQr)).toEqual([un.jeton, inconnu]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });
});

test.describe('Scan à la caméra, refus et pannes', () => {
  test('CA6 - un coureur en abandon est refusé avec le message du serveur', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright, {}, 1);
    placerStatutInscriptionEnBase(course.coureurs[0].inscriptionId, 'ABANDON');
    await activerCamera(page);
    await presenterQr(page, course.coureurs[0].jeton);
    await expect(erreur(page)).toHaveText('Ce coureur n\'est plus en course.', { timeout: 5000 });
    await expect(erreur(page)).toHaveAttribute('role', 'alert');
    expect(lirePassagesEnBase(course.id)).toEqual([]);
  });

  for (const panne of ['500', 'requête interrompue'] as const) {
    test(`CA6 - après ${panne === '500' ? 'une réponse 500' : 'une requête interrompue'}, le scan est gardé puis renvoyé automatiquement avec le même identifiantScan`, async ({ page, playwright }) => {
      // 5.1a (RG6, RG17) : remplace « aucun réessai automatique, le rejeu manuel garde le même identifiantScan ».
      const course = await ouvrirAvecCamera(page, playwright, {}, 1);
      const [un] = course.coureurs;
      const identifiants: string[] = [];
      await page.route(MOTIF_PASSAGES, async (route) => {
        identifiants.push((route.request().postDataJSON() as EnvoiPassage).identifiantScan);
        if (identifiants.length > 1) return route.fulfill(reponseRejeu(un.dossard, un.pseudo));
        return panne === '500' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.abort('failed');
      });
      await activerCamera(page);

      await presenterQr(page, un.jeton);
      await expect(page.getByTestId('scan-resultat-attente')).toHaveText('Scan gardé sur l\'appareil. Il sera envoyé dès que possible.', { timeout: 5000 });
      await expect(erreur(page)).toHaveCount(0);
      // Nouvelle tentative automatique après 2 s (±20 %), le QR restant maintenu devant la caméra.
      await expect(page.getByTestId('scan-resultat-succes')).toBeVisible({ timeout: 8000 });
      await verifierSucces(page, un.dossard, un.pseudo, 1);
      expect(identifiants).toHaveLength(2);
      expect(identifiants[1]).toBe(identifiants[0]);
      expect(identifiants[0]).toMatch(UUID_V4);
    });
  }

  test('CA6 - après une réponse 409, la présentation suivante du même jeton utilise un nouvel identifiantScan', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright, {}, 1);
    const [un] = course.coureurs;
    const identifiants: string[] = [];
    await page.route(MOTIF_PASSAGES, (route) => {
      identifiants.push((route.request().postDataJSON() as EnvoiPassage).identifiantScan);
      return route.fulfill(probleme(409, 'PASSAGE_DEJA_ENREGISTRE', 'Ce coureur a déjà un passage sur la boucle 1.'));
    });
    await activerCamera(page);
    await presenterQr(page, un.jeton);
    await expect(erreur(page)).toHaveText('Ce coureur a déjà un passage sur la boucle 1.', { timeout: 5000 });
    await retirerPendant(page, 2000);
    await presenterQr(page, un.jeton);
    await expect.poll(() => identifiants.length, { timeout: 5000 }).toBe(2);
    expect(identifiants[1]).not.toBe(identifiants[0]);
  });

  test('CA6 - une réponse 401 renvoie à la connexion avec le retour', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright, {}, 1);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await activerCamera(page);
    await presenterQr(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(`/connexion?retour=${encodeURIComponent(urlScan(course.id))}`, { timeout: 5000 });
  });

  test('CA6 - une réponse 403 ACCES_REFUSE renvoie à /acces-refuse', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright, {}, 1);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await activerCamera(page);
    await presenterQr(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(/\/acces-refuse$/, { timeout: 5000 });
  });
});

test.describe('Scan à la caméra, échecs de démarrage', () => {
  const echecs: Array<{ mode: ModeCamera; message: string }> = [
    { mode: 'refuser', message: 'L\'accès à la caméra est refusé. Autorisez-la dans les réglages du navigateur, ou saisissez le jeton ci-dessous.' },
    { mode: 'absente', message: 'Aucune caméra détectée. Saisissez le jeton ci-dessous.' },
    { mode: 'occupee', message: 'La caméra est utilisée par une autre application. Fermez-la puis réessayez, ou saisissez le jeton ci-dessous.' },
    { mode: 'autre', message: 'Impossible de démarrer la caméra. Saisissez le jeton ci-dessous.' },
  ];
  for (const { mode, message } of echecs) {
    test(`CA7 - caméra en échec (${mode}) : message, « Réessayer », repli par la saisie, puis démarrage au nouvel essai`, async ({ page, playwright }) => {
      const course = await ouvrirAvecCamera(page, playwright, { mode });
      const [un] = course.coureurs;
      await activer(page).click();
      await expect(messageCamera(page)).toHaveText(message);
      await expect(messageCamera(page)).toHaveAttribute('role', 'alert');
      await expect(reessayer(page)).toBeVisible();
      await expect(reessayer(page)).toHaveText('Réessayer');
      await expect(apercu(page)).toHaveCount(0);

      await scannerParEntree(page, un.jeton);
      await verifierSucces(page, 1, un.pseudo, 1);

      await changerMode(page, 'ok');
      await reessayer(page).click();
      await expect(apercu(page)).toBeVisible();
      await expect(etat(page)).toHaveText('Caméra active');
      await expect(messageCamera(page)).toHaveCount(0);
    });
  }

  test('CA7 - un nouvel essai qui échoue de nouveau garde le même message', async ({ page, playwright }) => {
    await ouvrirAvecCamera(page, playwright, { mode: 'refuser' });
    await activer(page).click();
    await expect(reessayer(page)).toBeVisible();
    await reessayer(page).click();
    await expect(messageCamera(page)).toContainText('L\'accès à la caméra est refusé.');
    await expect(reessayer(page)).toBeVisible();
    expect((await etatCamera(page)).demandes).toBe(2);
  });

  test('CA8 - sans API de capture : message HTTPS, pas de bouton d\'activation, aucune erreur de page, repli par la saisie', async ({ page, playwright }) => {
    const erreursPage: string[] = [];
    page.on('pageerror', (e) => erreursPage.push(e.message));
    const course = await ouvrirAvecCamera(page, playwright, { sansApi: true });
    await expect(messageCamera(page)).toHaveText('La caméra n\'est disponible qu\'en HTTPS. Saisissez le jeton ci-dessous.');
    await expect(activer(page)).toHaveCount(0);
    await expect(reessayer(page)).toHaveCount(0);
    await scannerParEntree(page, course.coureurs[0].jeton);
    await verifierSucces(page, 1, course.coureurs[0].pseudo, 1);
    expect(erreursPage).toEqual([]);
  });
});

test.describe('Scan à la caméra, choix de la caméra', () => {
  test('CA9 - avec deux caméras, la bascule arrête l\'ancien flux et ouvre l\'autre caméra', async ({ page, playwright }) => {
    await ouvrirAvecCamera(page, playwright, { cameras: 2 });
    await activerCamera(page);
    const avant = await etatCamera(page);
    expect(avant.contraintes[0].video).toEqual({ facingMode: { ideal: 'environment' } });
    expect(avant.camerasOuvertes).toEqual([CAMERA_ARRIERE]);
    await expect(basculer(page)).toBeVisible();
    await expect(basculer(page)).toHaveText('Changer de caméra');

    await basculer(page).click();
    await expect.poll(async () => (await etatCamera(page)).demandes).toBe(2);
    const apres = await etatCamera(page);
    expect(apres.contraintes[1].video).toEqual({ deviceId: { exact: CAMERA_AVANT } });
    expect(apres.camerasOuvertes).toEqual([CAMERA_ARRIERE, CAMERA_AVANT]);
    expect(apres.pistesActivesParFlux).toEqual([0, 1]);
    expect(apres.fluxActifs).toBe(1);
    await expect(etat(page)).toHaveText('Caméra active');

    // La rotation revient à la première caméra.
    await basculer(page).click();
    await expect.poll(async () => (await etatCamera(page)).demandes).toBe(3);
    const retour = await etatCamera(page);
    expect(retour.contraintes[2].video).toEqual({ deviceId: { exact: CAMERA_ARRIERE } });
    expect(retour.fluxActifs).toBe(1);
  });

  test('CA9 - avec une seule caméra, il n\'y a pas de bouton « Changer de caméra »', async ({ page, playwright }) => {
    await ouvrirAvecCamera(page, playwright, { cameras: 1 });
    await activerCamera(page);
    await expect(basculer(page)).toHaveCount(0);
  });

  test('CA9 - un navigateur qui rejette la préférence arrière : nouvelle demande sans cette contrainte, sans message', async ({ page, playwright }) => {
    await ouvrirAvecCamera(page, playwright, { mode: 'contrainteRejetee' });
    await activerCamera(page);
    await expect(messageCamera(page)).toHaveCount(0);
    const demandes = await etatCamera(page);
    expect(demandes.demandes).toBe(2);
    expect(demandes.contraintes[0].video).toEqual({ facingMode: { ideal: 'environment' } });
    expect(demandes.contraintes[1].video).toBe(true);
    expect(demandes.contraintes[1].audio).toBe(false);
    expect(demandes.fluxActifs).toBe(1);
  });
});

test.describe('Scan à la caméra, arrêt et cycle de vie', () => {
  test('CA10 - « Arrêter la caméra » libère le flux, masque l\'aperçu et plus aucun QR n\'est lu', async ({ page, playwright }) => {
    const course = await ouvrirAvecCamera(page, playwright);
    const envois = suivreEnvois(page);
    await activerCamera(page);
    expect((await etatCamera(page)).fluxActifs).toBe(1);

    await arreter(page).click();
    await expect(apercu(page)).toHaveCount(0);
    await expect(etat(page)).toHaveText('Caméra arrêtée');
    await expect(activer(page)).toBeVisible();
    await expect.poll(async () => (await etatCamera(page)).fluxActifs).toBe(0);

    await presenterQr(page, course.coureurs[0].jeton);
    // Fenêtre d'observation d'une absence de lecture après l'arrêt.
    await page.waitForTimeout(1500);
    expect(envois).toHaveLength(0);
    expect(lirePassagesEnBase(course.id)).toEqual([]);

    // Un arrêt manuel n'affiche aucun message ; la reprise est un clic.
    await expect(messageCamera(page)).toHaveCount(0);
    await activerCamera(page);
    expect((await etatCamera(page)).demandes).toBe(2);
  });

  test('CA10 - « Changer de course » arrête la caméra', async ({ page, playwright }) => {
    await ouvrirAvecCamera(page, playwright);
    await activerCamera(page);
    expect((await etatCamera(page)).fluxActifs).toBe(1);
    await page.getByTestId('scan-lien-changer').click();
    await expect(page).toHaveURL(/\/benevole$/);
    await expect.poll(async () => (await etatCamera(page)).fluxActifs).toBe(0);
  });

  test('CA10 - onglet masqué ou caméra débranchée : arrêt automatique, message, « Réessayer » ; rechargement : aucune demande', async ({ page, playwright }) => {
    await ouvrirAvecCamera(page, playwright);
    await activerCamera(page);

    await basculerVisibilite(page, false);
    await expect(messageCamera(page)).toHaveText('La caméra s\'est arrêtée.');
    await expect(messageCamera(page)).toHaveAttribute('role', 'alert');
    await expect(reessayer(page)).toBeVisible();
    await expect(apercu(page)).toHaveCount(0);
    await expect(etat(page)).toHaveText('Caméra arrêtée');
    expect((await etatCamera(page)).fluxActifs).toBe(0);

    // Aucun redémarrage automatique au retour de l'onglet : la reprise est un clic.
    await basculerVisibilite(page, true);
    await expect(reessayer(page)).toBeVisible();
    expect((await etatCamera(page)).demandes).toBe(1);
    await reessayer(page).click();
    await expect(etat(page)).toHaveText('Caméra active');
    await expect(messageCamera(page)).toHaveCount(0);
    expect((await etatCamera(page)).fluxActifs).toBe(1);

    await debrancher(page);
    await expect(messageCamera(page)).toHaveText('La caméra s\'est arrêtée.');
    await expect(reessayer(page)).toBeVisible();
    await expect(apercu(page)).toHaveCount(0);
    expect((await etatCamera(page)).fluxActifs).toBe(0);

    await page.reload();
    await expect(etat(page)).toHaveText('Caméra arrêtée');
    expect((await etatCamera(page)).demandes).toBe(0);
  });

  test('CA11 - une session complète n\'émet que des appels d\'API JSON ; aperçu muet et en ligne ; ni micro ni géolocalisation', async ({ page, playwright }) => {
    await page.addInitScript(() => {
      const w = window as unknown as Record<string, number>;
      w['__geolocalisation'] = 0;
      for (const nom of ['getCurrentPosition', 'watchPosition'] as const) {
        const origine = navigator.geolocation?.[nom]?.bind(navigator.geolocation);
        if (origine) navigator.geolocation[nom] = ((...args: never[]) => (w['__geolocalisation']++, (origine as (...a: never[]) => unknown)(...args))) as never;
      }
    });
    const course = await ouvrirAvecCamera(page, playwright);
    const requetes: Array<{ url: string; methode: string; type: string; contentType: string | undefined; corps: string | null }> = [];
    page.on('request', (r) =>
      requetes.push({ url: r.url(), methode: r.method(), type: r.resourceType(), contentType: r.headers()['content-type'], corps: r.postData() }),
    );
    await activerCamera(page);
    const [un, deux] = course.coureurs;
    await presenterQr(page, un.jeton);
    await verifierSucces(page, 1, un.pseudo, 1);
    await presenterQr(page, deux.jeton);
    await expect(page.getByTestId('scan-resultat-dossard')).toHaveText('Dossard 2', { timeout: 5000 });

    const propriete = await apercu(page).evaluate((v: HTMLVideoElement) => ({ muet: v.muted, enLigne: v.playsInline, attribut: v.hasAttribute('playsinline') }));
    expect(propriete).toEqual({ muet: true, enLigne: true, attribut: true });
    await arreter(page).click();
    await expect(etat(page)).toHaveText('Caméra arrêtée');

    const origine = new URL(page.url()).origin;
    for (const r of requetes) {
      expect(r.url.startsWith(origine), `origine de ${r.url}`).toBe(true);
      expect(['image', 'media', 'websocket', 'eventsource'], `type de ${r.url}`).not.toContain(r.type);
      if (r.type === 'fetch' || r.type === 'xhr' || r.methode !== 'GET') {
        expect(new URL(r.url).pathname.startsWith('/api/'), `appel hors /api/ : ${r.url}`).toBe(true);
      }
      if (r.corps !== null) {
        expect(r.contentType ?? '').toContain('json');
        expect(() => JSON.parse(r.corps as string)).not.toThrow();
      }
    }
    const passages = requetes.filter((r) => r.url.includes('/passages'));
    expect(passages).toHaveLength(2);
    for (const p of passages) expect(Object.keys(JSON.parse(p.corps as string)).sort()).toEqual(['ageDuScanMillisecondes', 'identifiantScan', 'jetonQr']);
    const demandes = await etatCamera(page);
    expect(demandes.demandesMicro).toBe(0);
    expect(demandes.contraintes.every((c) => c.audio === false)).toBe(true);
    expect(await page.evaluate(() => (window as unknown as Record<string, number>)['__geolocalisation'])).toBe(0);
  });
});

test.describe('Scan à la caméra, en-têtes du site', () => {
  test('CA12 - Permissions-Policy autorise la caméra pour le site seul, la CSP est inchangée', async ({ request, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    for (const chemin of ['/', urlScan(course.id)]) {
      const reponse = await request.get(chemin);
      expect(reponse.status()).toBe(200);
      expect(reponse.headers()['permissions-policy'], chemin).toBe('camera=(self), microphone=(), geolocation=()');
      expect(reponse.headers()['content-security-policy'], chemin).toBe(
        "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
      );
    }
  });
});

// --- CA14 : mise en page, accessibilité et mouvement ---

/** Rapport de contraste WCAG 2.x entre deux couleurs `rgb()`/`rgba()` calculées. */
function contraste(a: string, b: string): number {
  const luminance = (css: string) => {
    const [r, g, bl] = (css.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number).map((v) => {
      const c = v / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [clair, sombre] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (clair + 0.05) / (sombre + 0.05);
}

/** Couleur du texte et fond opaque effectif (premier ancêtre non transparent). */
async function couleurs(l: Locator): Promise<{ texte: string; fond: string }> {
  return l.evaluate((el) => {
    let fond = 'rgb(255, 255, 255)';
    for (let n: Element | null = el; n; n = n.parentElement) {
      const c = getComputedStyle(n).backgroundColor;
      const alpha = c.match(/rgba\(.*,\s*([\d.]+)\)$/);
      if (c !== 'rgba(0, 0, 0, 0)' && (!alpha || parseFloat(alpha[1]) === 1)) {
        fond = c;
        break;
      }
    }
    return { texte: getComputedStyle(el).color, fond };
  });
}

async function pasDeDefilementHorizontal(page: Page): Promise<void> {
  const debordement = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(debordement, 'défilement horizontal').toBeLessThanOrEqual(0);
}

async function atteindreAuClavier(page: Page, cible: Locator): Promise<void> {
  for (let i = 0; i < 40; i++) {
    if (await cible.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  expect(await cible.evaluate((el) => el === document.activeElement), 'élément atteint par Tab').toBe(true);
}

async function focusVisible(cible: Locator): Promise<void> {
  const s = await cible.evaluate((el) => {
    const c = getComputedStyle(el);
    return { style: c.outlineStyle, largeur: parseFloat(c.outlineWidth), ombre: c.boxShadow };
  });
  expect((s.style !== 'none' && s.largeur > 0) || s.ombre !== 'none', 'contour de focus visible').toBe(true);
}

async function hauteurMinimale(l: Locator, minimum = 44): Promise<void> {
  expect((await l.boundingBox())!.height).toBeGreaterThanOrEqual(minimum);
}

for (const { largeur, zoom, libelle } of [
  { largeur: 375, zoom: false, libelle: '375 px' },
  { largeur: 375, zoom: true, libelle: '375 px, texte à 200 %' },
  { largeur: 320, zoom: false, libelle: '320 px' },
  { largeur: 320, zoom: true, libelle: '320 px, texte à 200 %' },
]) {
  test(`CA14 - le bloc caméra à ${libelle} : pas de débordement, aperçu stable, cibles de 44 px, résultat visible`, async ({ page, playwright }) => {
    await page.setViewportSize({ width: largeur, height: 740 });
    const course = await ouvrirAvecCamera(page, playwright, { cameras: 2 });
    if (zoom) await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
    await pasDeDefilementHorizontal(page);
    await hauteurMinimale(activer(page));

    await activer(page).click();
    await expect(apercu(page)).toBeVisible();
    const premiere = (await apercu(page).boundingBox())!;
    await expect.poll(() => apercu(page).evaluate((v: HTMLVideoElement) => v.videoWidth)).toBeGreaterThan(0);
    const seconde = (await apercu(page).boundingBox())!;
    expect(Math.abs(seconde.width - premiere.width), 'largeur de l\'aperçu stable').toBeLessThanOrEqual(1);
    expect(Math.abs(seconde.height - premiere.height), 'hauteur de l\'aperçu stable').toBeLessThanOrEqual(1);
    expect(premiere.height, 'rapport d\'aspect réservé dès l\'apparition').toBeGreaterThan(0);
    const bloc = (await camera(page).boundingBox())!;
    expect(seconde.width).toBeLessThanOrEqual(bloc.width + 1);
    expect(seconde.x + seconde.width).toBeLessThanOrEqual(largeur);
    await expect(page.getByText('Placez le QR code dans le cadre')).toBeVisible();
    await pasDeDefilementHorizontal(page);
    await hauteurMinimale(arreter(page));
    await expect(basculer(page)).toBeVisible();
    await hauteurMinimale(basculer(page));

    await presenterQr(page, course.coureurs[0].jeton);
    await verifierSucces(page, 1, course.coureurs[0].pseudo, 1);
    const succes = (await page.getByTestId('scan-resultat-succes').boundingBox())!;
    const diagnostic = `résultat vert (y=${succes.y}, bas=${succes.y + succes.height}) dans la fenêtre de 740 px`;
    expect(succes.y, diagnostic).toBeGreaterThanOrEqual(0);
    // Texte normal : le résultat entier est visible sans défiler. Texte à 200 % : le bloc de résultat
    // lui-même (dossard agrandi, hérité de 4.4b) dépasse la fenêtre ; son début doit au moins être visible.
    expect(zoom ? succes.y + 44 : succes.y + succes.height, diagnostic).toBeLessThanOrEqual(740);
    await pasDeDefilementHorizontal(page);

    await debrancher(page);
    await expect(reessayer(page)).toBeVisible();
    await hauteurMinimale(reessayer(page));
    await pasDeDefilementHorizontal(page);
  });
}

test('CA14 - focus visible au clavier sur chaque bouton, états lisibles sans la couleur, rôles et nom accessible', async ({ page, playwright }) => {
  await ouvrirAvecCamera(page, playwright, { cameras: 2 });
  await page.keyboard.press('Tab');
  await atteindreAuClavier(page, activer(page));
  await focusVisible(activer(page));

  const pictogramme = (cible: Locator) =>
    cible.evaluate((el) => {
      const c = getComputedStyle(el, '::before');
      return { contenu: c.content, largeur: parseFloat(c.width), hauteur: parseFloat(c.height) };
    });
  expect((await etat(page).innerText()).trim()).toBe('Caméra arrêtée');
  const arretee = await pictogramme(etat(page));
  expect(arretee.contenu).not.toBe('none');
  expect(arretee.largeur).toBeGreaterThan(0);

  await activerCamera(page);
  const active = await pictogramme(etat(page));
  expect(active.contenu).not.toBe('none');
  expect(active.largeur).toBeGreaterThan(0);
  const fond = (cible: Locator) => cible.evaluate((el) => getComputedStyle(el, '::before').backgroundColor);
  expect(await fond(etat(page)), 'pictogramme actif distinct du pictogramme arrêté').not.toBe('rgba(0, 0, 0, 0)');

  await expect(apercu(page)).toHaveAccessibleName('Aperçu de la caméra');
  for (const bouton of [arreter(page), basculer(page)]) {
    await atteindreAuClavier(page, bouton);
    await focusVisible(bouton);
  }

  await presenterQr(page, 'https://exemple.org');
  await expect(messageCamera(page)).toHaveAttribute('role', 'status', { timeout: 5000 });
  const info = await couleurs(messageCamera(page));
  expect(contraste(info.texte, info.fond), 'contraste du message d\'information').toBeGreaterThanOrEqual(4.5);

  await debrancher(page);
  await expect(messageCamera(page)).toHaveAttribute('role', 'alert');
  const alerte = await couleurs(messageCamera(page));
  expect(contraste(alerte.texte, alerte.fond), 'contraste du message d\'échec').toBeGreaterThanOrEqual(4.5);
  await atteindreAuClavier(page, reessayer(page));
  await focusVisible(reessayer(page));
  const legendeActive = await couleurs(etat(page));
  expect(contraste(legendeActive.texte, legendeActive.fond), 'contraste de l\'état').toBeGreaterThanOrEqual(4.5);
});

test('CA14 - prefers-reduced-motion : aucune animation ni transition dans le bloc caméra', async ({ page, playwright }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await ouvrirAvecCamera(page, playwright);
  await activerCamera(page);
  const animations = await camera(page).evaluate((bloc) =>
    [bloc, ...bloc.querySelectorAll('*')].flatMap((el) => {
      const s = getComputedStyle(el);
      const durees = [...s.animationDuration.split(','), ...s.transitionDuration.split(',')];
      return s.animationName !== 'none' || durees.some((d) => parseFloat(d) > 0.001) ? [el.tagName + '.' + el.className] : [];
    }),
  );
  expect(animations).toEqual([]);
});

test('CA15 - le champ de saisie garde son comportement de 4.4b caméra active : Entrée, focus remis, valeur sélectionnée après erreur', async ({ page, playwright }) => {
  const course = await ouvrirAvecCamera(page, playwright);
  const [un] = course.coureurs;
  await activerCamera(page);
  await scannerParEntree(page, un.jeton);
  await verifierSucces(page, 1, un.pseudo, 1);
  await expect(champ(page)).toHaveValue('');
  await expect(champ(page)).toBeFocused();

  await scannerParEntree(page, un.jeton);
  await expect(erreur(page)).toHaveText('Ce coureur a déjà un passage sur la boucle 1.');
  await expect(champ(page)).toHaveValue(un.jeton);
  await expect(champ(page)).toBeFocused();
  const selection = await champ(page).evaluate((el: HTMLInputElement) => [el.selectionStart, el.selectionEnd, el.value.length]);
  expect(selection).toEqual([0, un.jeton.length, un.jeton.length]);
  expect((await etatCamera(page)).fluxActifs).toBe(1);
});
