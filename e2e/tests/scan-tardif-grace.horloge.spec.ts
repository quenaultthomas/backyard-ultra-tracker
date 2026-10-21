import { expect } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { connecterPatron, ouvrirPilotage } from './aide-abandon';
import { avancerHorloge, test } from './aide-horloge';
import { positionnerHorlogeApresDepart } from './aide-grace';
import { MOTIF_PASSAGES } from './aide-file-scan';
import { lireInscriptionEnBase, lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';
import { scannerParApi } from './aide-suivi-coureurs';
import { lire, urlSuivi } from './aide-suivi';

/*
 * Incrément 7.1 (délai de grâce) avec horloge applicative pilotée. Boucle de 1 min : délai de grâce G = 15 s, la Boucle 1
 * est tranchée à 75 s après le départ. L'horloge est positionnée à une seconde précise après le départ.
 */
test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA17 - pendant le délai de grâce, le suivi et le pilotage annoncent le contrôle de la boucle 1 ; après la grâce, le message disparaît', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, dureeBoucleMinutes: 1 });
  const [karim, lea] = course.coureurs;
  await scannerParApi(playwright, course, karim);
  await scannerParApi(playwright, course, lea);

  await connecterPatron(page);
  await ouvrirPilotage(page, course.id, 2);
  const contexteSuivi = await browser.newContext();
  try {
    const suivi = await contexteSuivi.newPage();
    await suivi.goto(urlSuivi(course.id));
    await expect(lire(suivi, 'suivi-nombre-en-course')).toHaveText('2');
    await expect(lire(suivi, 'suivi-message-decision-en-attente')).toHaveCount(0);
    await expect(lire(page, 'pilotage-message-decision-en-attente')).toHaveCount(0);

    await positionnerHorlogeApresDepart(playwright, course.id, 65);
    await expect(lire(suivi, 'suivi-message-decision-en-attente')).toContainText('boucle 1', { timeout: 8000 });
    await expect(lire(page, 'pilotage-message-decision-en-attente')).toContainText('boucle 1', { timeout: 8000 });

    await avancerHorloge(playwright, 'PT20S');
    await expect(lire(suivi, 'suivi-message-decision-en-attente')).toHaveCount(0, { timeout: 8000 });
    await expect(lire(page, 'pilotage-message-decision-en-attente')).toHaveCount(0, { timeout: 8000 });
  } finally {
    await contexteSuivi.close();
  }
});

test('CA18 - un scan fait dans la boucle 1 et reçu pendant la grâce est accepté : après la grâce, les deux coureurs sont toujours en course, sans vainqueur', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, dureeBoucleMinutes: 1 });
  const [karim, lea] = course.coureurs;
  await scannerParApi(playwright, course, karim);

  // Le client simule un scan fait 15 s plus tôt (file hors ligne rejouée) : l'âge est réécrit dans la requête de scan.
  await page.route(MOTIF_PASSAGES, (route) => {
    const corps = route.request().postDataJSON() as Record<string, unknown>;
    return route.continue({ postData: JSON.stringify({ ...corps, ageDuScanMillisecondes: 15_000 }) });
  });
  await ouvrirEcranDeScan(page, course);

  await positionnerHorlogeApresDepart(playwright, course.id, 62);
  await scannerParEntree(page, lea.jeton);
  await verifierSucces(page, lea.dossard, lea.pseudo, 1);

  await avancerHorloge(playwright, 'PT20S');
  const contexteSuivi = await browser.newContext();
  try {
    const suivi = await contexteSuivi.newPage();
    await suivi.goto(urlSuivi(course.id));
    await expect(lire(suivi, 'suivi-nombre-en-course')).toHaveText('2');
    await expect(lire(suivi, 'suivi-nombre-abandons')).toHaveText('0');
    await expect(lire(suivi, 'suivi-vainqueurs')).toHaveCount(0);
    await expect(lire(suivi, 'suivi-message-decision-en-attente')).toHaveCount(0);
  } finally {
    await contexteSuivi.close();
  }
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
  expect(lireInscriptionEnBase(course.id, lea.dossard).statut).toBe('EN_COURSE');
  expect(lireInscriptionEnBase(course.id, karim.dossard).statut).toBe('EN_COURSE');
});
