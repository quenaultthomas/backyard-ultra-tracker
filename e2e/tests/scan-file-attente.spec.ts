import { expect, test, type Page } from '@playwright/test';
import { MOT_DE_PASSE_BENEVOLE_CREE, affecterBenevolesParApi, creerAdminParApi, exigerIdentifiantsAdminMaster, pseudoAdminUnique } from './aide-admin';
import { connecterAdmin, ouvrirPilotage } from './aide-abandon';
import { installerCameraSimulee, presenterQr, retirerQr } from './aide-camera';
import { placerStatutCourseEnBase, placerStatutInscriptionEnBase } from './aide-coureur';
import { avecApi } from './aide-horloge';
import { saisir } from './aide-connexion';
import { seDeconnecterMalgreLesScansEnAttente, seDeconnecterParLeMenu } from './aide-entete';
import {
  MESSAGE_ATTENTE,
  MESSAGE_HORS_LIGNE,
  MESSAGE_STOCKAGE,
  MOTIF_PASSAGES,
  UUID_V4,
  attendreFileVide,
  avancerJusquaRequete,
  ecrireEntrees,
  identifiantDe,
  identifiantDuCompte,
  installerHorloge,
  laisserPasserLeReseau,
  lireFile,
  probleme,
  resteStable,
  routePassagesChronometree,
  simulerRetourEnLigne,
  suivreExceptions,
  suivrePassages,
  type EntreeFile,
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

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const compteur = (page: Page) => page.getByTestId('scan-compteur-attente');
const attente = (page: Page) => page.getByTestId('scan-resultat-attente');
const horsLigne = (page: Page) => page.getByTestId('scan-hors-ligne');
const renvoyer = (page: Page) => page.getByTestId('scan-renvoyer');
const envoyes = (page: Page) => page.getByTestId('scan-message-envoyes');
const refus = (page: Page) => page.getByTestId('scan-refus');
const motifRefus = (page: Page) => page.getByTestId('scan-refus-motif');
const detailRefus = (page: Page) => page.getByTestId('scan-resultat-erreur');
const liste = (page: Page) => page.getByTestId('scan-a-verifier');
const lignes = (page: Page) => page.getByTestId('scan-a-verifier-ligne');
const AIDE_REINTEGRATION = 'Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur.';

const IDENTIFIANTS_DOSSARDS = ['scan-resultat-dossard', 'scan-resultat-pseudo', 'scan-refus-dossard', 'scan-refus-pseudo'];

/** Valide chaque jeton hors ligne et vérifie le retour « gardé sur l'appareil » et le compteur. */
async function scannerEnFile(page: Page, jetons: string[], dejaEnFile = 0): Promise<void> {
  for (const [i, jeton] of jetons.entries()) {
    await scannerParEntree(page, jeton);
    await expect(attente(page)).toHaveText(MESSAGE_ATTENTE);
    await expect(compteur(page)).toHaveText(`${dejaEnFile + i + 1} en attente`);
    await expect(champ(page)).toHaveValue('');
    await expect(champ(page)).toBeFocused();
  }
}

async function verifierAbsenceDeJetonsLocaux(page: Page, jetons: string[]): Promise<void> {
  const contenu = await page.content();
  const stockages = await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }, document.cookie]));
  for (const jeton of jetons) {
    expect(page.url()).not.toContain(jeton);
    expect(contenu).not.toContain(jeton);
    expect(stockages).not.toContain(jeton);
  }
}

async function ouvrirHorsLigne(page: Page, course: CourseDeScan): Promise<void> {
  await ouvrirEcranDeScan(page, course);
  await page.context().setOffline(true);
  await expect(horsLigne(page)).toHaveText(MESSAGE_HORS_LIGNE);
}

test.describe('File de scan, enregistrement local et envoi', () => {
  test('CA2 - en ligne, le scan est mis en file puis envoyé : champ libre avant la réponse, une seule requête, aucun compteur', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    let liberer!: () => void;
    const reponseAttendue = new Promise<void>((resolve) => (liberer = resolve));
    await page.route(MOTIF_PASSAGES, async (route) => {
      await reponseAttendue;
      await route.continue();
    });

    await champ(page).fill(un.jeton);
    await champ(page).press('Enter');
    await expect(page.getByTestId('scan-resultat-envoi')).toHaveText('Envoi…');
    await expect(page.getByTestId('scan-resultat-envoi')).toHaveAttribute('role', 'status');
    await expect(champ(page)).toHaveValue('');
    await expect(champ(page)).toBeFocused();
    await expect(page.getByTestId('scan-bouton-enregistrer')).toBeEnabled();
    await expect(page.getByTestId('scan-bouton-enregistrer')).toHaveText('Enregistrer le passage');
    await expect(compteur(page)).toHaveCount(0);
    await expect(horsLigne(page)).toHaveCount(0);

    await champ(page).press('Enter');
    await expect(page.getByTestId('scan-erreur-saisie')).toHaveText('Saisissez le jeton du coureur.');
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(1);

    liberer();
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    expect(requetes).toHaveLength(1);
    expect(requetes[0].jetonQr).toBe(un.jeton);
    expect(requetes[0].identifiantScan).toMatch(UUID_V4);
    expect(Number.isInteger(requetes[0].ageDuScanMillisecondes)).toBe(true);
    expect(requetes[0].ageDuScanMillisecondes).toBeGreaterThanOrEqual(0);
    expect(requetes[0].ageDuScanMillisecondes).toBeLessThanOrEqual(5000);
    await expect(compteur(page)).toHaveCount(0);
    await expect(horsLigne(page)).toHaveCount(0);
    await attendreFileVide(page);

    await page.unroute(MOTIF_PASSAGES);
    await scannerParEntree(page, un.jeton);
    await expect(motifRefus(page)).toHaveText('Déjà passé');
    await attendreFileVide(page);
  });

  test('CA3 - hors ligne, trois scans sont gardés sur l\'appareil : compteur, message, aucune requête, entrées dans IndexedDB', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    const compteId = await identifiantDuCompte(page);
    await page.context().setOffline(true);
    await expect(horsLigne(page)).toHaveText(MESSAGE_HORS_LIGNE);

    await scannerEnFile(page, course.coureurs.map((c) => c.jeton));
    for (const testid of IDENTIFIANTS_DOSSARDS) await expect(page.getByTestId(testid)).toHaveCount(0);
    await expect(attente(page)).toHaveAttribute('role', 'status');
    await expect(compteur(page)).toHaveAttribute('role', 'status');
    await expect(renvoyer(page)).toHaveCount(0);

    expect(requetes).toHaveLength(0);
    expect(lirePassagesEnBase(course.id)).toEqual([]);
    const entrees = await lireFile(page);
    expect(entrees).toHaveLength(3);
    expect(new Set(entrees.map((e) => e.identifiantScan)).size).toBe(3);
    for (const entree of entrees) {
      expect(entree.identifiantScan).toMatch(UUID_V4);
      expect(entree.etat).toBe('EN_ATTENTE');
      expect(entree.compteId).toBe(compteId);
      expect(entree.courseId).toBe(course.id);
    }
    expect(entrees.map((e) => e.jetonQr)).toEqual(course.coureurs.map((c) => c.jeton));
    await verifierAbsenceDeJetonsLocaux(page, course.coureurs.map((c) => c.jeton));
  });

  test('CA4 - au retour du réseau, les trois scans partent dans l\'ordre, un à la fois, avec leur identifiant et un âge cohérent', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    await ouvrirEcranDeScan(page, course);
    const jetons = course.coureurs.map((c) => c.jeton);
    await page.context().setOffline(true);
    await expect(horsLigne(page)).toBeVisible();
    await scannerEnFile(page, jetons);
    const apresLeScan = Date.now();
    const identifiantsEnFile = (await lireFile(page)).map((e) => e.identifiantScan);

    const requetes = suivrePassages(page);
    let enVol = 0;
    let maximumEnVol = 0;
    await page.route(MOTIF_PASSAGES, async (route) => {
      enVol++;
      maximumEnVol = Math.max(maximumEnVol, enVol);
      const reponse = await route.fetch();
      await route.fulfill({ response: reponse });
      enVol--;
    });
    const avantLeRetour = Date.now();
    await page.context().setOffline(false);

    await expect(envoyes(page)).toHaveText('3 scans envoyés');
    await expect(envoyes(page)).toHaveAttribute('role', 'status');
    await expect(compteur(page)).toHaveCount(0);
    await expect(renvoyer(page)).toHaveCount(0);
    await expect(horsLigne(page)).toHaveCount(0);
    expect(requetes.map((r) => r.jetonQr)).toEqual(jetons);
    expect(requetes.map((r) => r.identifiantScan)).toEqual(identifiantsEnFile);
    expect(maximumEnVol).toBe(1);
    const dureeHorsLigne = Math.max(0, avantLeRetour - apresLeScan);
    for (const requete of requetes) {
      expect(Number.isInteger(requete.ageDuScanMillisecondes)).toBe(true);
      expect(requete.ageDuScanMillisecondes).toBeGreaterThanOrEqual(dureeHorsLigne);
      expect(requete.ageDuScanMillisecondes).toBeLessThanOrEqual(86_400_000);
    }
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN', '3:1:SCAN']);
    await attendreFileVide(page);

    await page.reload();
    await expect(champ(page)).toBeVisible();
    await expect(compteur(page)).toHaveCount(0);
    await resteStable(() => requetes.length, 3, 1500);
    expect(requetes).toHaveLength(3);
  });

  test('CA5 - réponse perdue : la tentative suivante porte le même identifiantScan, reçoit un 200 et ne crée qu\'un passage', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const identifiants: string[] = [];
    await page.route(MOTIF_PASSAGES, async (route) => {
      identifiants.push(identifiantDe(route));
      if (identifiants.length === 1) {
        await route.fetch();
        return route.abort('failed');
      }
      return route.continue();
    });

    await scannerParEntree(page, un.jeton);
    await expect(attente(page)).toHaveText(MESSAGE_ATTENTE);
    await expect(compteur(page)).toHaveText('1 en attente');
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);

    await renvoyer(page).click();
    await expect(compteur(page)).toHaveCount(0);
    await expect(refus(page)).toHaveCount(0);
    expect(identifiants).toHaveLength(2);
    expect(identifiants[1]).toBe(identifiants[0]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
    await attendreFileVide(page);
  });

  test('CA5 - réponse perdue : la nouvelle tentative automatique (horloge avancée) reçoit un 200, jamais un refus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, async (n, route) => {
      if (n === 1) {
        await route.fetch();
        return route.abort('failed');
      }
      return route.continue();
    });

    await scannerParEntree(page, un.jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await avancerJusquaRequete(page, releve, 2, 1.6, 2.4);
    await expect(compteur(page)).toHaveCount(0);
    await expect(refus(page)).toHaveCount(0);
    expect(releve.identifiants[1]).toBe(releve.identifiants[0]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });
});

test.describe('File de scan, nouvelles tentatives', () => {
  test('CA6 - trois pannes 500 : attentes de 2, 4 puis 8 s (±20 %), même identifiantScan, puis succès et un seul passage', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, (n, route) => (n <= 3 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue()));

    await scannerParEntree(page, un.jeton);
    await expect(attente(page)).toHaveText(MESSAGE_ATTENTE);
    await avancerJusquaRequete(page, releve, 2, 1.6, 2.4);
    await avancerJusquaRequete(page, releve, 3, 3.2, 4.8);
    await avancerJusquaRequete(page, releve, 4, 6.4, 9.6);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    expect(new Set(releve.identifiants).size).toBe(1);
    expect(releve.identifiants).toHaveLength(4);
    expect(releve.identifiants[0]).toMatch(UUID_V4);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });

  for (const cas of [
    { nom: '503 SERVICE_SATURE avec Retry-After: 20 : attente de 20 s', entete: '20', minimum: 20, maximum: 20, statut: 503, code: 'SERVICE_SATURE' },
    { nom: '500 avec Retry-After: 500 : attente plafonnée à 120 s', entete: '500', minimum: 120, maximum: 120, statut: 500, code: 'ERREUR_INTERNE' },
    { nom: '503 avec Retry-After: abc : valeur ignorée, attente de 2 s (±20 %)', entete: 'abc', minimum: 1.6, maximum: 2.4, statut: 503, code: 'SERVICE_SATURE' },
  ]) {
    test(`CA6 - ${cas.nom}`, async ({ page, playwright }) => {
      const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
      const [un] = course.coureurs;
      await installerHorloge(page, course);
      await ouvrirEcranDeScan(page, course);
      const releve = await routePassagesChronometree(page, (n, route) =>
        n === 1 ? route.fulfill(probleme(cas.statut, cas.code, 'Simulé.', { 'Retry-After': cas.entete })) : route.continue(),
      );

      await scannerParEntree(page, un.jeton);
      await expect(attente(page)).toHaveText(MESSAGE_ATTENTE);
      await avancerJusquaRequete(page, releve, 2, cas.minimum, cas.maximum);
      await verifierSucces(page, un.dossard, un.pseudo, 1);
      expect(releve.identifiants[1]).toBe(releve.identifiants[0]);
    });
  }

  test('CA6 - après cinq pannes consécutives, l\'attente reste de 24 à 36 s', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, (n, route) => (n <= 5 ? route.fulfill(probleme(502, 'ERREUR_PROXY')) : route.continue()));

    await scannerParEntree(page, un.jeton);
    await expect(attente(page)).toHaveText(MESSAGE_ATTENTE);
    await avancerJusquaRequete(page, releve, 2, 1.6, 2.4);
    await avancerJusquaRequete(page, releve, 3, 3.2, 4.8);
    await avancerJusquaRequete(page, releve, 4, 6.4, 9.6);
    await avancerJusquaRequete(page, releve, 5, 12.8, 19.2);
    await avancerJusquaRequete(page, releve, 6, 24, 36);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    expect(new Set(releve.identifiants).size).toBe(1);
  });

  test('CA6 - « Renvoyer maintenant » envoie immédiatement et remet l\'attente à 2 s', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, (n, route) => (n <= 2 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue()));

    await scannerParEntree(page, un.jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await expect.poll(async () => Math.max(0, ...(await lireFile(page)).map((e) => e.tentatives ?? 0))).toBe(1);
    await renvoyer(page).click();
    await expect.poll(() => releve.instants.length).toBe(2);
    // La deuxième panne arrive après un envoi manuel : l'attente repart de 2 s, pas de 4 s.
    await avancerJusquaRequete(page, releve, 3, 1.6, 2.4);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
  });

  test('CA6 - le retour du réseau (événement online) envoie immédiatement et remet l\'attente à 2 s', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, (n, route) => (n <= 2 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue()));

    await scannerParEntree(page, un.jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await expect.poll(async () => Math.max(0, ...(await lireFile(page)).map((e) => e.tentatives ?? 0))).toBe(1);
    await simulerRetourEnLigne(page.context());
    await expect.poll(() => releve.instants.length).toBe(2);
    await avancerJusquaRequete(page, releve, 3, 1.6, 2.4);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
  });

  test('CA6 - une nouvelle tentative est aussi lancée au retour de l\'onglet au premier plan et à la création d\'une entrée dans une file vide', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, (n, route) => (n === 1 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue()));

    await scannerParEntree(page, un.jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
    await expect(compteur(page)).toHaveCount(0);
    expect(releve.instants).toHaveLength(2);
    await scannerParEntree(page, deux.jeton);
    await verifierSucces(page, deux.dossard, deux.pseudo, 1);
  });
});

test.describe('File de scan, refus reçus en différé', () => {
  test('CA7 (5.1b CA1) - un doublon hors ligne est refusé sans être rejoué, ne bloque pas la file et s\'affiche dans « Scans à vérifier », pas en zone rouge', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirHorsLigne(page, course);
    const requetes = suivrePassages(page);

    await scannerEnFile(page, [un.jeton, un.jeton, deux.jeton]);
    await page.context().setOffline(false);

    await expect(envoyes(page)).toHaveText('2 scans envoyés, dont 1 à vérifier');
    await expect(compteur(page)).toHaveCount(0);
    await expect(refus(page)).toHaveCount(0);
    await expect(lignes(page)).toHaveCount(1);
    await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText('Déjà passé');
    await expect(page.getByTestId('scan-a-verifier-dossard')).toHaveText('Dossard 1');
    await expect(page.getByTestId('scan-a-verifier-pseudo')).toHaveText(un.pseudo);
    await expect(page.getByTestId('scan-a-verifier-boucle')).toHaveText('Boucle 1');
    await expect(page.getByTestId('scan-a-verifier-detail')).toHaveText('Ce coureur a déjà un passage sur la boucle 1.');
    await page.clock.runFor(10_000);
    await laisserPasserLeReseau(page);
    expect(requetes.map((r) => r.jetonQr)).toEqual([un.jeton, un.jeton, deux.jeton]);
    expect(new Set(requetes.map((r) => r.identifiantScan)).size).toBe(3);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    await attendreFileVide(page);
    await expect(refus(page)).toHaveCount(0);
  });

  test('CA7 (5.1b CA1) - le refus différé d\'un doublon placé en dernier laisse la zone rouge vide et crée une ligne « à vérifier »', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirHorsLigne(page, course);
    await scannerEnFile(page, [un.jeton, un.jeton]);
    await page.context().setOffline(false);

    await expect(envoyes(page)).toHaveText('1 scan envoyé, dont 1 à vérifier');
    await expect(refus(page)).toHaveCount(0);
    await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText('Déjà passé');
    await expect(page.getByTestId('scan-a-verifier-dossard')).toHaveText('Dossard 1');
    await expect(page.getByTestId('scan-a-verifier-pseudo')).toHaveText(un.pseudo);
    await expect(page.getByTestId('scan-a-verifier-boucle')).toHaveText('Boucle 1');
    await expect(page.getByTestId('scan-a-verifier-detail')).toHaveText('Ce coureur a déjà un passage sur la boucle 1.');
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
    await attendreFileVide(page);
  });

  const refusesParLeServeur: Array<{
    nom: string;
    preparer: (c: CourseDeScan) => void;
    jeton: (c: CourseDeScan) => string;
    motif: string;
    detail: string;
    coureurIdentifie: boolean;
    aide: boolean;
  }> = [
    {
      nom: 'coureur passé en abandon',
      preparer: (c) => placerStatutInscriptionEnBase(c.coureurs[0].inscriptionId, 'ABANDON'),
      jeton: (c) => c.coureurs[0].jeton,
      motif: 'Coureur en abandon',
      detail: 'Ce coureur n\'est plus en course.',
      coureurIdentifie: true,
      aide: true,
    },
    {
      nom: 'course terminée',
      preparer: (c) => placerStatutCourseEnBase(c.id, 'TERMINEE'),
      jeton: (c) => c.coureurs[0].jeton,
      motif: 'Course terminée',
      detail: 'La course est terminée.',
      coureurIdentifie: false,
      aide: true,
    },
    {
      nom: 'jeton inconnu',
      preparer: () => undefined,
      jeton: () => 'abc',
      motif: 'Jeton inconnu',
      detail: 'Jeton inconnu pour cette course.',
      coureurIdentifie: false,
      aide: false,
    },
    {
      nom: 'course repassée en préparation',
      preparer: (c) => placerStatutCourseEnBase(c.id, 'EN_PREPARATION'),
      jeton: (c) => c.coureurs[0].jeton,
      motif: 'Course non démarrée',
      detail: 'La course n\'est pas démarrée.',
      coureurIdentifie: false,
      aide: false,
    },
  ];
  for (const cas of refusesParLeServeur) {
    test(`CA8 (5.1b CA2) - ${cas.nom} : refus définitif en ligne « à vérifier », aucun passage, aucune relance, jeton effacé`, async ({ page, playwright }) => {
      const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
      await installerHorloge(page, course);
      await ouvrirHorsLigne(page, course);
      const requetes = suivrePassages(page);
      await scannerEnFile(page, [cas.jeton(course)]);
      cas.preparer(course);
      await page.context().setOffline(false);

      await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText(cas.motif);
      await expect(page.getByTestId('scan-a-verifier-detail')).toHaveText(cas.detail);
      await expect(refus(page)).toHaveCount(0);
      if (cas.nom === 'jeton inconnu') {
        for (const testid of ['dossard', 'pseudo', 'boucle']) await expect(page.getByTestId(`scan-a-verifier-${testid}`)).toHaveCount(0);
      }
      if (cas.nom === 'coureur passé en abandon') {
        await expect(page.getByTestId('scan-a-verifier-dossard')).toHaveText('Dossard 1');
      }
      if (cas.aide) await expect(page.getByTestId('scan-a-verifier-aide')).toHaveText(AIDE_REINTEGRATION);
      else await expect(page.getByTestId('scan-a-verifier-aide')).toHaveCount(0);
      await page.clock.runFor(30_000);
      await laisserPasserLeReseau(page);
      expect(requetes).toHaveLength(1);
      expect(lirePassagesEnBase(course.id)).toEqual([]);
      await attendreFileVide(page);
      await expect(compteur(page)).toHaveCount(0);
      const entrees = await lireFile(page);
      expect(entrees).toHaveLength(1);
      expect(entrees[0].etat).toBe('A_VERIFIER');
      expect('jetonQr' in entrees[0]).toBe(false);
    });
  }

  test('CA8 - un autre 4xx (415, sans détail) est un refus définitif générique « Scan refusé », jamais retenté', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill({ status: 415, contentType: 'text/plain', body: 'Type non géré' }));

    await scannerParEntree(page, un.jeton);
    await expect(motifRefus(page)).toHaveText('Scan refusé');
    await expect(detailRefus(page)).toHaveText('Le serveur a refusé ce scan.');
    await expect(page.getByTestId('scan-resultat-attente')).toHaveCount(0);
    await expect(liste(page)).toHaveCount(0);
    await page.clock.runFor(60_000);
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(1);
    await attendreFileVide(page);
  });

  test('CA8 (5.1b CA5) - un refus d\'une entrée d\'une autre Course que celle de l\'écran ouvert n\'y est pas affiché mais est gardé pour sa Course', async ({ page, playwright }) => {
    const x = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const w = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, benevole: x.benevole });
    await ouvrirEcranDeScan(page, w);
    await page.route(MOTIF_PASSAGES, (route) => route.abort('failed'));
    await scannerParEntree(page, 'abc');
    await expect(compteur(page)).toHaveText('1 en attente');
    await expect.poll(async () => (await lireFile(page)).length).toBe(1);

    await page.goto(urlScan(x.id));
    await expect(champ(page)).toBeVisible();
    await expect(compteur(page)).toHaveCount(0);
    const envoyesApresLeRetour: string[] = [];
    await page.route(MOTIF_PASSAGES, (route) => {
      envoyesApresLeRetour.push((route.request().postDataJSON() as { jetonQr: string }).jetonQr);
      return route.continue();
    });
    await simulerRetourEnLigne(page.context());
    await attendreFileVide(page);
    expect(envoyesApresLeRetour).toEqual(['abc']);
    await expect(refus(page)).toHaveCount(0);
    await expect(liste(page)).toHaveCount(0);
    expect(lirePassagesEnBase(w.id)).toEqual([]);
    await page.goto(urlScan(w.id));
    await expect(lignes(page)).toHaveCount(1);
    await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText('Jeton inconnu');
  });
});

test.describe('File de scan, session, comptes et stockage', () => {
  async function metteEnFileDeuxScansPuisSessionExpiree(page: Page, course: CourseDeScan): Promise<void> {
    await page.context().setOffline(true);
    await expect(horsLigne(page)).toHaveText(MESSAGE_HORS_LIGNE);
    await scannerEnFile(page, course.coureurs.map((c) => c.jeton));
    // Session perdue pour de vrai (cookies supprimés) : le serveur répond lui-même 401 au premier envoi.
    await page.context().clearCookies();
    await page.context().setOffline(false);
    const alerte = page.getByTestId('scan-session-expiree');
    await expect(alerte).toHaveText('Votre session a expiré. Reconnectez-vous pour envoyer les 2 scans en attente.');
    await expect(alerte).toHaveAttribute('role', 'alert');
    await expect(page).toHaveURL(`/connexion?retour=${encodeURIComponent(urlScan(course.id))}`);
  }

  test('CA11 - une réponse 401 met la file en pause, garde les entrées, renvoie à la connexion ; la reconnexion de Léo reprend l\'envoi dans l\'ordre', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    await metteEnFileDeuxScansPuisSessionExpiree(page, course);
    await page.clock.runFor(60_000);
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(1);
    expect(await lireFile(page)).toHaveLength(2);

    await saisir(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await attendreFileVide(page);
    expect(requetes.slice(1).map((r) => r.jetonQr)).toEqual(course.coureurs.map((c) => c.jeton));
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
  });

  test('CA11 - la connexion d\'un autre Compte (Marc) ne montre aucun compteur et n\'envoie rien', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const marc = await creerBenevole(playwright);
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [course.benevole, marc]));
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    await metteEnFileDeuxScansPuisSessionExpiree(page, course);

    await saisir(page, marc, MOT_DE_PASSE_BENEVOLE_CREE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await expect(champ(page)).toBeVisible();
    await expect(compteur(page)).toHaveCount(0);
    await page.clock.runFor(60_000);
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(1);
    expect(await lireFile(page)).toHaveLength(2);
    expect(lirePassagesEnBase(course.id)).toEqual([]);
  });

  test('CA11 - une réponse 403 ACCES_REFUSE met la file en pause, renvoie à /acces-refuse et garde l\'entrée sans relance', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await page.clock.runFor(60_000);
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(1);
    const entrees = await lireFile(page);
    expect(entrees).toHaveLength(1);
    expect(entrees[0].etat).toBe('EN_ATTENTE');
  });

  test('CA11 - une réponse 403 CSRF_INVALIDE renouvelle le jeton CSRF et relance une fois, sans refus affiché', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const sequence: string[] = [];
    const identifiants: string[] = [];
    page.on('request', (r) => {
      const chemin = new URL(r.url()).pathname;
      if (chemin === '/api/csrf') sequence.push('csrf');
      else if (r.method() === 'POST' && chemin.endsWith('/passages')) sequence.push('passages');
    });
    await page.route(MOTIF_PASSAGES, (route) => {
      identifiants.push(identifiantDe(route));
      return identifiants.length === 1 ? route.fulfill(probleme(403, 'CSRF_INVALIDE', 'Jeton CSRF invalide.')) : route.continue();
    });

    await scannerParEntree(page, un.jeton);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    await expect(refus(page)).toHaveCount(0);
    expect(sequence).toEqual(['csrf', 'passages', 'csrf', 'passages']);
    expect(identifiants).toHaveLength(2);
    expect(identifiants[1]).toBe(identifiants[0]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });

  test('CA12 - une panne sans événement hors ligne : les entrées sont relues après rechargement et partent au premier cycle', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.abort('failed'));

    await scannerParEntree(page, un.jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await scannerParEntree(page, deux.jeton);
    await expect(compteur(page)).toHaveText('2 en attente');
    await expect.poll(async () => (await lireFile(page)).length).toBe(2);

    await page.reload();
    await expect(champ(page)).toBeVisible();
    await expect(compteur(page)).toHaveText('2 en attente');
    await page.unroute(MOTIF_PASSAGES);
    await renvoyer(page).click();
    await expect(compteur(page)).toHaveCount(0);
    await expect.poll(() => lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    await attendreFileVide(page);
  });

  test('CA12 - deux onglets du même Compte envoient chacun les mêmes entrées : exactement deux passages', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    await page.context().route(MOTIF_PASSAGES, (route) => route.abort('failed'));
    await scannerParEntree(page, un.jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await scannerParEntree(page, deux.jeton);
    await expect(compteur(page)).toHaveText('2 en attente');

    const second = await page.context().newPage();
    await second.goto(urlScan(course.id));
    await expect(champ(second)).toBeVisible();
    await expect(compteur(second)).toHaveText('2 en attente');
    await page.context().unroute(MOTIF_PASSAGES);
    await Promise.all([renvoyer(page).click(), renvoyer(second).click()]);

    await expect(compteur(page)).toHaveCount(0);
    await expect(compteur(second)).toHaveCount(0);
    await expect.poll(() => lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    await attendreFileVide(page);
  });

  test('CA13 (5.1b CA6) - « Se déconnecter » demande confirmation et garde la file ; Marc ne voit rien ; Léo retrouve ses deux scans et ils partent', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const marc = await creerBenevole(playwright);
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [course.benevole, marc]));
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    await page.route(MOTIF_PASSAGES, (route) => route.abort('failed'));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await scannerParEntree(page, course.coureurs[1].jeton);
    await expect(compteur(page)).toHaveText('2 en attente');

    await seDeconnecterMalgreLesScansEnAttente(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await expect(page.getByTestId('bouton-menu')).toBeVisible();
    expect(await lireFile(page)).toHaveLength(2);

    const avant = requetes.length;
    await seConnecterVers(page, marc, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
    await expect(champ(page)).toBeVisible();
    await expect(compteur(page)).toHaveCount(0);
    await laisserPasserLeReseau(page);
    expect(requetes.length).toBe(avant);

    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
    await expect(champ(page)).toBeVisible();
    await expect(compteur(page)).toHaveText('2 en attente');
    await page.unroute(MOTIF_PASSAGES);
    await renvoyer(page).click();
    await expect(compteur(page)).toHaveCount(0);
    await expect.poll(() => lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
  });

  test('CA10 (5.1b CA4) - un scan resté 25 h hors ligne n\'est pas envoyé : ligne « Scan trop ancien », sans dossard ni aide, jeton supprimé', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirHorsLigne(page, course);
    const requetes = suivrePassages(page);
    await scannerEnFile(page, [un.jeton]);

    await page.clock.fastForward('25:00:00');
    await page.context().setOffline(false);
    await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText('Scan trop ancien');
    await expect(page.getByTestId('scan-a-verifier-detail')).toHaveText('Ce scan date de plus de 24 heures et n\'a pas été envoyé.');
    await expect(refus(page)).toHaveCount(0);
    for (const testid of ['dossard', 'pseudo', 'boucle', 'aide']) await expect(page.getByTestId(`scan-a-verifier-${testid}`)).toHaveCount(0);
    await attendreFileVide(page);
    await expect(compteur(page)).toHaveCount(0);
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(0);
    expect(lirePassagesEnBase(course.id)).toEqual([]);
    const entrees = await lireFile(page);
    expect(entrees.map((e) => e.etat)).toEqual(['A_VERIFIER']);
    expect('jetonQr' in entrees[0]).toBe(false);
  });

  test('CA10 (5.1b CA4) - un scan de 23 h 59 min est encore envoyé, avec un âge d\'au plus 86 400 000 ms', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirHorsLigne(page, course);
    const requetes = suivrePassages(page);
    await scannerEnFile(page, [un.jeton]);

    await page.clock.fastForward('23:59:00');
    await page.context().setOffline(false);
    await expect.poll(() => requetes.length).toBe(1);
    expect(requetes[0].ageDuScanMillisecondes).toBeGreaterThan(86_300_000);
    expect(requetes[0].ageDuScanMillisecondes).toBeLessThanOrEqual(86_400_000);
    // Placé avant le départ de la Course : le serveur le refuse, la file n'a pas décidé à sa place.
    await expect(page.getByTestId('scan-a-verifier-motif')).toBeVisible();
    await expect(page.getByTestId('scan-a-verifier-motif')).not.toHaveText('Scan trop ancien');
    await expect(refus(page)).toHaveCount(0);
    await attendreFileVide(page);
  });

  test('CA10 (5.1b CA4) - au rechargement, une entrée de plus de 24 h de Léo devient une ligne « Scan trop ancien », celle d\'un autre Compte est supprimée, sans envoi', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    const compteId = await identifiantDuCompte(page);
    const maintenant = Date.now();
    const entree = (identifiantScan: string, compte: string, ageHeures: number, sequence: number): EntreeFile => ({
      identifiantScan,
      compteId: compte,
      courseId: course.id,
      jetonQr: `jeton-${identifiantScan}`,
      sequence,
      creeLeMural: maintenant - ageHeures * 3_600_000,
      creeLeMonotone: 0,
      sessionPage: 'session-precedente',
      etat: 'EN_ATTENTE',
      tentatives: 1,
    });
    const ancienneDeLeo = '11111111-1111-4111-8111-111111111111';
    const ancienneAutre = '22222222-2222-4222-8222-222222222222';
    const recenteAutre = '33333333-3333-4333-8333-333333333333';
    await ecrireEntrees(page, [
      entree(ancienneDeLeo, compteId, 25, maintenant - 3),
      entree(ancienneAutre, '99999999-9999-4999-8999-999999999999', 30, maintenant - 2),
      entree(recenteAutre, '99999999-9999-4999-8999-999999999999', 1, maintenant - 1),
    ]);

    await page.reload();
    await expect(champ(page)).toBeVisible();
    await expect.poll(async () => (await lireFile(page)).map((e) => e.identifiantScan).sort()).toEqual([ancienneDeLeo, recenteAutre].sort());
    await expect(refus(page)).toHaveCount(0);
    await expect(lignes(page)).toHaveCount(1);
    await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText('Scan trop ancien');
    const aVerifier = (await lireFile(page)).filter((e) => e.etat === 'A_VERIFIER');
    expect(aVerifier.map((e) => e.identifiantScan)).toEqual([ancienneDeLeo]);
    expect('jetonQr' in aVerifier[0]).toBe(false);
    await expect(compteur(page)).toHaveCount(0);
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(0);
  });

  test('CA15 - IndexedDB indisponible : avertissement, le scan en ligne fonctionne, la file continue en mémoire, aucune exception', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const [un, deux, trois] = course.coureurs;
    await page.addInitScript(() => {
      IDBFactory.prototype.open = function () {
        throw new DOMException('IndexedDB indisponible (simulé)', 'SecurityError');
      };
    });
    const exceptions = suivreExceptions(page);
    await ouvrirEcranDeScan(page, course);
    const avertissement = page.getByTestId('scan-avertissement-stockage');
    await expect(avertissement).toHaveText(MESSAGE_STOCKAGE);
    await expect(avertissement).toHaveAttribute('role', 'alert');

    await scannerParEntree(page, un.jeton);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    await expect(avertissement).toBeVisible();

    await page.context().setOffline(true);
    await expect(horsLigne(page)).toBeVisible();
    await scannerEnFile(page, [deux.jeton, trois.jeton]);
    await page.context().setOffline(false);
    await expect(envoyes(page)).toHaveText('2 scans envoyés');
    await expect(compteur(page)).toHaveCount(0);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN', '3:1:SCAN']);
    expect(exceptions.map((e) => e.message)).toEqual([]);
  });

  test('CA16 - caméra active hors ligne : un QR maintenu fait une seule entrée, le focus reste, le QR suivant est mis en file sans attendre', async ({ page, playwright }) => {
    await installerCameraSimulee(page);
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    await page.getByTestId('scan-camera-activer').click();
    await expect(page.getByTestId('scan-camera-etat')).toHaveText('Caméra active');
    await page.context().setOffline(true);
    await expect(horsLigne(page)).toBeVisible();

    await presenterQr(page, un.jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await expect(attente(page)).toHaveText(MESSAGE_ATTENTE);
    await expect(champ(page)).not.toBeFocused();
    await resteStable(async () => (await lireFile(page)).length, 1, 5000);
    await expect(compteur(page)).toHaveText('1 en attente');

    await retirerQr(page);
    await presenterQr(page, deux.jeton);
    await expect(compteur(page)).toHaveText('2 en attente');
    await expect(champ(page)).not.toBeFocused();

    await page.context().setOffline(false);
    await expect(envoyes(page)).toHaveText('2 scans envoyés');
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
  });
});

test.describe('File de scan, plusieurs Courses', () => {
  test('CA14 (5.1b CA5) - compteurs par Course, totaux à l\'accueil, envoi de toutes les Courses depuis l\'accueil, refus gardé dans la liste de sa Course', async ({ page, playwright }) => {
    const x = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const w = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, benevole: x.benevole });
    await ouvrirEcranDeScan(page, x);
    await page.route(MOTIF_PASSAGES, (route) => route.abort('failed'));

    await scannerParEntree(page, x.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await expect.poll(async () => (await lireFile(page)).length).toBe(1);
    await page.goto(urlScan(w.id));
    await expect(champ(page)).toBeVisible();
    await expect(compteur(page)).toHaveCount(0);
    await scannerParEntree(page, w.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await scannerParEntree(page, w.coureurs[1].jeton);
    await expect(compteur(page)).toHaveText('2 en attente');
    await expect.poll(async () => (await lireFile(page)).length).toBe(3);

    await page.goto(urlScan(x.id));
    await expect(compteur(page)).toHaveText('1 en attente');
    await page.goto(urlScan(w.id));
    await expect(compteur(page)).toHaveText('2 en attente');

    await page.goto('/benevole');
    await expect(page).toHaveURL(/\/benevole$/);
    await expect(champ(page)).toHaveCount(0);
    await expect(compteur(page)).toHaveCount(0);
    await expect(page.getByTestId('benevole-en-attente')).toHaveText('3 scans en attente');
    await expect(page.getByTestId('benevole-a-verifier')).toHaveCount(0);

    const envoyesApresLeRetour: string[] = [];
    await page.route(MOTIF_PASSAGES, (route) => {
      envoyesApresLeRetour.push((route.request().postDataJSON() as { jetonQr: string }).jetonQr);
      return route.continue();
    });
    await simulerRetourEnLigne(page.context());
    await expect.poll(() => lirePassagesEnBase(x.id)).toEqual(['1:1:SCAN']);
    await expect.poll(() => lirePassagesEnBase(w.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    expect(envoyesApresLeRetour).toEqual([x.coureurs[0].jeton, w.coureurs[0].jeton, w.coureurs[1].jeton]);
    await attendreFileVide(page);
    await expect(page.getByTestId('benevole-en-attente')).toHaveCount(0);

    // Un jeton de W scanné dans X est refusé pour X : la ligne « à vérifier » s'affiche sur l'écran de X.
    await page.goto(urlScan(x.id));
    await expect(champ(page)).toBeVisible();
    await page.context().setOffline(true);
    await expect(horsLigne(page)).toBeVisible();
    await scannerEnFile(page, [w.coureurs[0].jeton]);
    await page.context().setOffline(false);
    await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText('Jeton inconnu');
    await expect(refus(page)).toHaveCount(0);
    await attendreFileVide(page);
  });
});

test.describe('File de scan, parcours de la roadmap', () => {
  test('CA19 - hors ligne, trois coureurs scannés ("3 en attente"), puis envoyés et visibles au pilotage sans doublon', async ({ browser, page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
    const nadia = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    const contexteAdmin = await browser.newContext();
    try {
      const pilotage = await contexteAdmin.newPage();
      await connecterAdmin(pilotage, nadia);
      await ouvrirPilotage(pilotage, course.id, 3);
      await expect(pilotage.getByTestId('pilotage-ayant-boucle')).toHaveText('A bouclé : 0');

      await ouvrirHorsLigne(page, course);
      await scannerEnFile(page, course.coureurs.map((c) => c.jeton));
      await expect(compteur(page)).toHaveText('3 en attente');
      await page.context().setOffline(false);
      await expect(envoyes(page)).toHaveText('3 scans envoyés');
      await expect(compteur(page)).toHaveCount(0);

      await expect(pilotage.getByTestId('pilotage-ayant-boucle')).toHaveText('A bouclé : 3', { timeout: 8000 });
      await expect(pilotage.getByTestId('pilotage-inscrit-boucles-terminees')).toHaveText(['1', '1', '1']);
      expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN', '3:1:SCAN']);
    } finally {
      await contexteAdmin.close();
    }
  });
});

test.describe('File de scan, accessibilité et mise en page', () => {
  const configurations = [
    { nom: '375 px', largeur: 375, zoom: false },
    { nom: '320 px', largeur: 320, zoom: false },
    { nom: '320 px avec texte à 200 %', largeur: 320, zoom: true },
  ];

  function luminance([r, g, b]: number[]): number {
    const lineaire = (v: number) => (v / 255 <= 0.03928 ? v / 255 / 12.92 : ((v / 255 + 0.055) / 1.055) ** 2.4);
    return 0.2126 * lineaire(r) + 0.7152 * lineaire(g) + 0.0722 * lineaire(b);
  }

  async function mesures(page: Page, testid: string) {
    return page.getByTestId(testid).evaluate((el) => {
      const style = getComputedStyle(el);
      const avant = getComputedStyle(el, '::before');
      const rect = el.getBoundingClientRect();
      const sonde = document.createElement('p');
      sonde.style.backgroundColor = 'var(--couleur-orange-pale)';
      document.body.appendChild(sonde);
      const fondCharte = getComputedStyle(sonde).backgroundColor;
      sonde.remove();
      const nombres = (couleur: string) => (couleur.match(/[\d.]+/g) ?? []).map(Number);
      return {
        texte: (el.textContent ?? '').trim(),
        fond: style.backgroundColor,
        fondCharte,
        couleur: nombres(style.color),
        fondRgb: nombres(style.backgroundColor),
        contenuAvant: avant.content,
        masque: avant.maskImage !== 'none' || avant.getPropertyValue('-webkit-mask-image') !== 'none',
        animation: style.animationName,
        transition: style.transitionDuration,
        largeur: rect.width,
        debordeDroite: rect.right > window.innerWidth + 1,
      };
    });
  }

  for (const configuration of configurations) {
    test(`CA17 - à ${configuration.nom} : aucun défilement horizontal, cibles de 44 px, pictogramme + libellé, contraste, rôles, sans animation`, async ({ page, playwright }) => {
      const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
      await page.setViewportSize({ width: configuration.largeur, height: 800 });
      await ouvrirEcranDeScan(page, course);
      if (configuration.zoom) await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
      await page.emulateMedia({ reducedMotion: 'reduce' });
      const pasDeDefilement = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);

      // En ligne avec une panne simulée : « Renvoyer maintenant » est visible.
      await page.route(MOTIF_PASSAGES, (route) => route.abort('failed'));
      await scannerParEntree(page, course.coureurs[0].jeton);
      await scannerParEntree(page, course.coureurs[1].jeton);
      await expect(compteur(page)).toHaveText('2 en attente');
      await expect(renvoyer(page)).toBeVisible();
      expect(await pasDeDefilement()).toBe(true);
      const boite = await renvoyer(page).boundingBox();
      expect(boite!.height).toBeGreaterThanOrEqual(44);
      expect(boite!.width).toBeGreaterThanOrEqual(44);
      const boiteEnregistrer = await page.getByTestId('scan-bouton-enregistrer').boundingBox();
      expect(boiteEnregistrer!.height).toBeGreaterThanOrEqual(44);
      for (let i = 0; i < 15 && !(await renvoyer(page).evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press('Tab');
      await expect(renvoyer(page)).toBeFocused();
      const focus = await renvoyer(page).evaluate((el) => {
        const s = getComputedStyle(el);
        return (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || s.boxShadow !== 'none';
      });
      expect(focus, 'focus visible').toBe(true);

      // Hors ligne : message d'état et compteur.
      await page.context().setOffline(true);
      await expect(horsLigne(page)).toBeVisible();
      expect(await pasDeDefilement()).toBe(true);

      for (const testid of ['scan-resultat-attente', 'scan-hors-ligne']) {
        const m = await mesures(page, testid);
        expect(m.texte.length, `libellé de ${testid}`).toBeGreaterThan(0);
        expect(m.contenuAvant, `pictogramme de ${testid}`).not.toBe('none');
        expect(m.masque, `masque de ${testid}`).toBe(true);
        expect(m.fond, `fond de ${testid} issu de la charte`).toBe(m.fondCharte);
        const rapport = (luminance(m.fondRgb) + 0.05) / (luminance(m.couleur) + 0.05);
        expect(Math.max(rapport, 1 / rapport), `contraste de ${testid}`).toBeGreaterThanOrEqual(4.5);
        expect(m.animation).toBe('none');
        expect(m.transition === '0s' || m.transition === '').toBe(true);
        expect(m.debordeDroite).toBe(false);
      }
      const classeAttente = await attente(page).getAttribute('class');
      expect(classeAttente).toContain('message--avertissement');
      expect(await horsLigne(page).getAttribute('class')).toContain('message--avertissement');
      await expect(attente(page)).toHaveAttribute('role', 'status');
      await expect(compteur(page)).toHaveAttribute('role', 'status');
      const compteurPolice = await compteur(page).evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
      expect(compteurPolice).toBeGreaterThan(
        await page.getByTestId('scan-champ-jeton').evaluate((el) => parseFloat(getComputedStyle(el).fontSize)) - 0.5,
      );
    });
  }
});

test.describe('File de scan, comportements en direct inchangés', () => {
  test('CA18 - un refus en direct n\'est jamais retenté, la saisie revient sélectionnée dans le champ', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    await page.route(MOTIF_PASSAGES, (route) =>
      route.fulfill(probleme(409, 'PASSAGE_DEJA_ENREGISTRE', 'Ce coureur a déjà un passage sur la boucle 1.')),
    );

    await scannerParEntree(page, un.jeton);
    await expect(motifRefus(page)).toHaveText('Déjà passé');
    await expect(detailRefus(page)).toHaveText('Ce coureur a déjà un passage sur la boucle 1.');
    await expect(champ(page)).toHaveValue(un.jeton);
    await expect(champ(page)).toBeFocused();
    expect(await champ(page).evaluate((el: HTMLInputElement) => [el.selectionStart, el.selectionEnd])).toEqual([0, un.jeton.length]);
    await expect(compteur(page)).toHaveCount(0);
    await page.clock.runFor(60_000);
    await laisserPasserLeReseau(page);
    expect(requetes).toHaveLength(1);
    await attendreFileVide(page);
  });

  test('CA18 - une panne en direct produit « gardé sur l\'appareil » puis une nouvelle tentative automatique avec le même identifiantScan', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await installerHorloge(page, course);
    await ouvrirEcranDeScan(page, course);
    const releve = await routePassagesChronometree(page, (n, route) => (n === 1 ? route.abort('failed') : route.continue()));

    await scannerParEntree(page, un.jeton);
    await expect(attente(page)).toHaveText(MESSAGE_ATTENTE);
    await expect(detailRefus(page)).toHaveCount(0);
    await expect(champ(page)).toHaveValue('');
    await avancerJusquaRequete(page, releve, 2, 1.6, 2.4);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    expect(releve.identifiants[1]).toBe(releve.identifiants[0]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });
});
