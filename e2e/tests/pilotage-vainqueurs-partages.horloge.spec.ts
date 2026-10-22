import { type Page } from '@playwright/test';
import { connecterAdminMaster, exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, expect, test } from './aide-horloge';
import {
  lireInscriptionEnBase,
  lirePassagesEnBase,
  lireStatutCourseEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  verifierSucces,
  type Coureur,
} from './aide-scan';

/*
 * Incrément 4.10 : vainqueurs partagés et nombre max de boucles (CA9 à CA12). Horloge applicative pilotée ; le
 * planificateur serveur (tick d'1 s) décide, le pilotage rafraîchit toutes les 3 s : 8 s de marge. Les instants
 * d'avance sont calculés contre la durée de Boucle de 60 min (PT10M puis PT50M : départ exact de la Boucle 2).
 */

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const lire = (page: Page, testid: string) => page.getByTestId(testid);
const HUIT_SECONDES = { timeout: 8000 };
const urlPilotage = (id: string) => `/administration/courses/${id}/pilotage`;
const SANS_DECISION = { statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' };
const HORS_DELAI = (boucle: number) => ({ statut: 'ABANDON', motifAbandon: 'HORS_DELAI', boucleAbandon: String(boucle) });
const VAINQUEUR = { statut: 'VAINQUEUR', motifAbandon: '', boucleAbandon: '' };

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

async function verifierVainqueurs(page: Page, vainqueurs: Coureur[], boucles: string[]): Promise<void> {
  await expect(lire(page, 'pilotage-vainqueurs')).toBeVisible(HUIT_SECONDES);
  await expect(lire(page, 'pilotage-vainqueurs-titre')).toHaveText(vainqueurs.length > 1 ? 'Vainqueurs' : 'Vainqueur');
  await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(vainqueurs.length);
  await expect(lire(page, 'pilotage-vainqueur-dossard')).toHaveText(vainqueurs.map((c) => String(c.dossard)));
  await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText(vainqueurs.map((c) => c.pseudo));
  await expect(lire(page, 'pilotage-vainqueur-statut')).toHaveText(vainqueurs.map(() => 'Vainqueur'));
  await expect(lire(page, 'pilotage-vainqueur-boucles-terminees')).toHaveText(boucles);
  await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée');
  await expect(lire(page, 'pilotage-message-terminee')).toHaveText('La course est terminée.');
  await expect(lire(page, 'pilotage-inscrits-vide')).toHaveText('Aucun coureur en course.');
}

async function verifierAbandons(page: Page, abandonnes: Coureur[], boucle: number, bouclesTerminees: string): Promise<void> {
  await expect(lire(page, 'pilotage-abandon')).toHaveCount(abandonnes.length);
  await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(abandonnes.map((c) => c.pseudo));
  await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText(abandonnes.map(() => `Boucle ${boucle}`));
  await expect(lire(page, 'pilotage-abandon-motif')).toHaveText(abandonnes.map(() => 'Hors délai'));
  await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText(abandonnes.map(() => bouclesTerminees));
}

test('CA9 - personne n\'a été scanné : à la fin de la Boucle 1 les trois coureurs sont vainqueurs, la Course est terminée ; une Course de 90 min voisine reste en cours', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const longue = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 90, benevole: course.benevole });
  await ouvrirPilotage(page, course.id);
  await expect(lire(page, 'pilotage-compteur')).toHaveText('3 coureurs en course sur 3 inscrits');

  await avancerHorloge(playwright, 'PT30M');
  await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
  expect(lireStatutCourseEnBase(course.id)).toBe('EN_COURS');

  await avancerHorloge(playwright, 'PT33M');

  await verifierVainqueurs(page, course.coureurs, ['0', '0', '0']);
  await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');
  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('0 coureur en abandon');
  await verifierSansRechargement(page);
  for (const c of course.coureurs) expect(lireInscriptionEnBase(course.id, c.dossard)).toEqual(VAINQUEUR);
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
  expect(lirePassagesEnBase(course.id)).toEqual([]);

  // la Boucle de 90 min n'est pas terminée à 60 min : rien n'est décidé
  expect(lireStatutCourseEnBase(longue.id)).toBe('EN_COURS');
  for (const c of longue.coureurs) expect(lireInscriptionEnBase(longue.id, c.dossard)).toEqual(SANS_DECISION);
  const ongletLongue = await page.context().newPage();
  await ongletLongue.goto(urlPilotage(longue.id));
  await expect(lire(ongletLongue, 'pilotage-statut')).toHaveText('En cours');
  await expect(lire(ongletLongue, 'pilotage-vainqueurs')).toHaveCount(0);
});

test('CA10 - dernière Boucle autorisée (M = 2) : Bruno et Chloé ont bouclé les deux Boucles, ils sont vainqueurs ; Tom, resté à la Boucle 1, abandonne Boucle 2', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    for (const c of [bruno, chloe, tom]) await scanner(scan, c, 1);
    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 2', HUIT_SECONDES);
    // Boucle k = 1 < M avec trois passants : aucune décision
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
    await scanner(scan, bruno, 2);
    await scanner(scan, chloe, 2);
    await avancerHorloge(playwright, 'PT63M');
  } finally {
    await contexteBenevole.close();
  }

  await verifierVainqueurs(page, [bruno, chloe], ['2', '2']);
  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon');
  await verifierAbandons(page, [tom], 2, '1');
  await verifierSansRechargement(page);
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(2));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA10 - variante : les trois coureurs ont bouclé les deux Boucles (M = 2), les trois sont vainqueurs, aucun abandon', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  await ouvrirPilotage(page, course.id);
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    for (const c of course.coureurs) await scanner(scan, c, 1);
    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 2', HUIT_SECONDES);
    for (const c of course.coureurs) await scanner(scan, c, 2);
    await avancerHorloge(playwright, 'PT63M');
  } finally {
    await contexteBenevole.close();
  }

  await verifierVainqueurs(page, course.coureurs, ['2', '2', '2']);
  await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');
  for (const c of course.coureurs) expect(lireInscriptionEnBase(course.id, c.dossard)).toEqual(VAINQUEUR);
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA10 - variante M = 1 : Bruno et Chloé scannés pendant l\'unique Boucle sont vainqueurs, Tom abandonne Boucle 1', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 1, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 1);
    await scanner(scan, chloe, 1);
    await avancerHorloge(playwright, 'PT63M');
  } finally {
    await contexteBenevole.close();
  }

  await verifierVainqueurs(page, [bruno, chloe], ['1', '1']);
  await verifierAbandons(page, [tom], 1, '0');
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(1));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA11 - cohabitation : Tom abandonne Boucle 2, puis personne sur la Boucle 3 : Bruno et Chloé sont vainqueurs (2 Boucles terminées)', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    for (const c of [bruno, chloe, tom]) await scanner(scan, c, 1);
    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 5', HUIT_SECONDES);
    await scanner(scan, bruno, 2);
    await scanner(scan, chloe, 2);
    // 60 + 120 = 180 min après le départ : fin de la Boucle 3, personne n'a bouclé la Boucle 3
    await avancerHorloge(playwright, 'PT123M');
  } finally {
    await contexteBenevole.close();
  }

  await verifierVainqueurs(page, [bruno, chloe], ['2', '2']);
  await verifierAbandons(page, [tom], 2, '1');
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(2));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN', '2:1:SCAN', '2:2:SCAN', '3:1:SCAN']);
});

test('CA11 - rattrapage : trois Boucles d\'un coup, Tom abandonne Boucle 1, Bruno et Chloé sont vainqueurs (1 Boucle terminée), les Boucles 2 et 3 ne sont pas examinées', async ({ browser, page, playwright }) => {
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
  } finally {
    await contexteBenevole.close();
  }

  // 10 + 170 = 180 min après le départ, d'un seul coup
  await avancerHorloge(playwright, 'PT173M');

  await verifierVainqueurs(page, [bruno, chloe], ['1', '1']);
  await verifierAbandons(page, [tom], 1, '0');
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(1));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA11 - non-régression : Bruno seul scanné sur la Boucle 1 est vainqueur unique (« Vainqueur » au singulier), Chloé et Tom abandonnent Boucle 1', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 1);
    await avancerHorloge(playwright, 'PT53M');
  } finally {
    await contexteBenevole.close();
  }

  await verifierVainqueurs(page, [bruno], ['1']);
  await verifierAbandons(page, [chloe, tom], 1, '0');
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(HORS_DELAI(1));
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(1));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA12 - après la fin par vainqueurs partagés, le scan d\'un vainqueur puis d\'un autre est refusé « Course terminée », sans Passage ; plus de bouton « Scanner »', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  const contexteBenevole = await browser.newContext();
  try {
    // l'écran de scan reste ouvert pendant la fin de la Course
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT63M');
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(3);

    for (const c of [bruno, chloe]) {
      await scannerParEntree(scan, c.jeton);
      await expect(lire(scan, 'scan-refus')).toBeVisible();
      await expect(lire(scan, 'scan-refus-motif')).toHaveText('Course terminée');
      await expect(lire(scan, 'scan-resultat-erreur')).toHaveText('La course est terminée.');
    }
    expect(lirePassagesEnBase(course.id)).toEqual([]);

    await scan.goto('/benevole');
    const ligne = lire(scan, 'benevole-ligne-course').filter({ hasText: course.nom });
    await expect(ligne).toHaveCount(1);
    await expect(ligne.getByTestId('benevole-lien-scanner')).toHaveCount(0);
  } finally {
    await contexteBenevole.close();
  }
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});
