import { type Page } from '@playwright/test';
import { connecterAdminMaster, exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, expect, test } from './aide-horloge';
import {
  lireInscriptionEnBase,
  lirePassagesEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  urlScan,
  verifierSucces,
  type Coureur,
} from './aide-scan';

/*
 * Incrément 4.8 : abandon automatique vu au pilotage (CA12 à CA15). Horloge applicative pilotée ;
 * le planificateur serveur (tick d'1 s) décide, le pilotage rafraîchit toutes les 3 s : 8 s de marge.
 */

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const lire = (page: Page, testid: string) => page.getByTestId(testid);
const HUIT_SECONDES = { timeout: 8000 };
const urlPilotage = (id: string) => `/administration/courses/${id}/pilotage`;

async function scanner(page: Page, c: Coureur, boucle: number): Promise<void> {
  await scannerParEntree(page, c.jeton);
  await verifierSucces(page, c.dossard, c.pseudo, boucle);
}

async function ouvrirPilotage(page: Page, id: string): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
  await page.goto(urlPilotage(id));
  await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));
}

async function verifierSansRechargement(page: Page): Promise<void> {
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);
}

test('CA12 - Tom, sans passage sur la Boucle 1, passe en abandon « Hors délai » au départ de la Boucle 2, sans recharger ; une Course de 90 min ne perd personne', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const longue = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, dureeBoucleMinutes: 90, benevole: course.benevole });
  const [bruno, chloe, tom] = course.coureurs;
  const [karim, sophie] = longue.coureurs;

  await ouvrirPilotage(page, course.id);
  await expect(lire(page, 'pilotage-compteur')).toHaveText('3 coureurs en course sur 3 inscrits');
  await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');
  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('0 coureur en abandon');

  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 1);
    await scanner(scan, chloe, 1);
    await scan.goto(urlScan(longue.id));
    await expect(lire(scan, 'scan-champ-jeton')).toBeVisible();
    await scanner(scan, karim, 1);
  } finally {
    await contexteBenevole.close();
  }
  await expect(lire(page, 'pilotage-aucun-abandon')).toBeVisible();
  await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'A bouclé', 'En boucle'], HUIT_SECONDES);

  await avancerHorloge(playwright, 'PT50M');

  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon', HUIT_SECONDES);
  await expect(lire(page, 'pilotage-compteur')).toHaveText('2 coureurs en course sur 3 inscrits');
  await expect(lire(page, 'pilotage-abandons')).toBeVisible();
  await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
  await expect(lire(page, 'pilotage-abandon-dossard')).toHaveText('3');
  await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(tom.pseudo);
  await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 1');
  await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('Hors délai');
  await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText('0');
  await expect(lire(page, 'pilotage-aucun-abandon')).toHaveCount(0);
  await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText([bruno.pseudo, chloe.pseudo]);
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
  await expect(lire(page, 'pilotage-colonne-boucle')).toHaveText('Boucle 2');
  await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['En boucle', 'En boucle']);
  await verifierSansRechargement(page);

  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual({ statut: 'ABANDON', motifAbandon: 'HORS_DELAI', boucleAbandon: '1' });
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual({ statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' });
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual({ statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' });
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);

  // la Course de 90 min n'a pas terminé sa Boucle 1 : Sophie n'est pas touchée
  expect(lireInscriptionEnBase(longue.id, karim.dossard).statut).toBe('EN_COURSE');
  expect(lireInscriptionEnBase(longue.id, sophie.dossard)).toEqual({ statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' });
});

test('CA13 - deux Boucles d\'un coup : Chloé et Tom en abandon Boucle 1 (rattrapage), Bruno seul en course, aucun abandon Boucle 2 ; sans aucun scan, personne n\'abandonne', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  await expect(lire(page, 'pilotage-aucun-abandon')).toBeVisible();

  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await scanner(scan, bruno, 1);
  } finally {
    await contexteBenevole.close();
  }

  await avancerHorloge(playwright, 'PT120M');

  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('2 coureurs en abandon', HUIT_SECONDES);
  await expect(lire(page, 'pilotage-abandon')).toHaveCount(2);
  await expect(lire(page, 'pilotage-abandon-dossard')).toHaveText([String(chloe.dossard), String(tom.dossard)]);
  await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText([chloe.pseudo, tom.pseudo]);
  await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText(['Boucle 1', 'Boucle 1']);
  await expect(lire(page, 'pilotage-abandon-motif')).toHaveText(['Hors délai', 'Hors délai']);
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(1);
  await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText(bruno.pseudo);
  await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText('1');
  await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 3 sur 5');
  await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  await verifierSansRechargement(page);
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual({ statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' });
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual({ statut: 'ABANDON', motifAbandon: 'HORS_DELAI', boucleAbandon: '1' });
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual({ statut: 'ABANDON', motifAbandon: 'HORS_DELAI', boucleAbandon: '1' });
});

test('CA13 - personne n\'a bouclé la Boucle 1 : aucun abandon, les trois restent en course', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  await ouvrirPilotage(page, course.id);
  await expect(lire(page, 'pilotage-aucun-abandon')).toBeVisible();

  await avancerHorloge(playwright, 'PT60M');

  await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 5', HUIT_SECONDES);
  // le tick (1 s) a eu le temps de passer : aucun abandon, aucune perte
  await expect(lire(page, 'pilotage-compteur')).toHaveText('3 coureurs en course sur 3 inscrits');
  await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');
  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('0 coureur en abandon');
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);
  for (const c of course.coureurs) {
    expect(lireInscriptionEnBase(course.id, c.dossard)).toEqual({ statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' });
  }
});

test('CA14 - le scan de Tom en abandon est refusé « Coureur en abandon », sans Passage ; Bruno et Chloé scannés en Boucle 2 ne sont pas abandonnés à la Boucle 3', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  await ouvrirPilotage(page, course.id);

  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 1);
    await scanner(scan, chloe, 1);
    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(tom.pseudo, HUIT_SECONDES);

    await scannerParEntree(scan, tom.jeton);
    await expect(lire(scan, 'scan-refus')).toBeVisible();
    await expect(lire(scan, 'scan-refus-motif')).toHaveText('Coureur en abandon');
    await expect(lire(scan, 'scan-refus-dossard')).toHaveText(`Dossard ${tom.dossard}`);
    await expect(lire(scan, 'scan-refus-pseudo')).toHaveText(tom.pseudo);
    await expect(lire(scan, 'scan-resultat-erreur')).toHaveText('Ce coureur n\'est plus en course.');

    await scanner(scan, bruno, 2);
    await scanner(scan, chloe, 2);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN', '2:1:SCAN', '2:2:SCAN']);

    await avancerHorloge(playwright, 'PT60M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 3 sur 5', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['2', '2']);
    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon');
    await expect(lire(page, 'pilotage-compteur')).toHaveText('2 coureurs en course sur 3 inscrits');
  } finally {
    await contexteBenevole.close();
  }
  expect(lireInscriptionEnBase(course.id, bruno.dossard).statut).toBe('EN_COURSE');
  expect(lireInscriptionEnBase(course.id, chloe.dossard).statut).toBe('EN_COURSE');
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual({ statut: 'ABANDON', motifAbandon: 'HORS_DELAI', boucleAbandon: '1' });
});

test('CA15 - dernière Boucle autorisée (M = 2) : Chloé, sans Passage sur la Boucle 2, abandonne Boucle 2 ; Bruno reste en course, la Course reste « En cours »', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  const [bruno, chloe] = course.coureurs;
  await ouvrirPilotage(page, course.id);

  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 1);
    await scanner(scan, chloe, 1);
    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 2', HUIT_SECONDES);
    await scanner(scan, bruno, 2);

    await avancerHorloge(playwright, 'PT60M');
  } finally {
    await contexteBenevole.close();
  }

  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon', HUIT_SECONDES);
  await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(chloe.pseudo);
  await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 2');
  await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('Hors délai');
  await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText('1');
  await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText(bruno.pseudo);
  await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText('2');
  await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  await expect(lire(page, 'pilotage-message-boucle-max-terminee')).toBeVisible();
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual({ statut: 'ABANDON', motifAbandon: 'HORS_DELAI', boucleAbandon: '2' });
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual({ statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' });
});
