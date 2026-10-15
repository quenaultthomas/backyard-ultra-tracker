import { type APIRequestContext, type Page } from '@playwright/test';
import { MOT_DE_PASSE_BENEVOLE_CREE, connecterAdminMaster, connecterParApi, exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, avecApi, expect, test } from './aide-horloge';
import {
  lireInscriptionEnBase,
  lirePassagesEnBase,
  lireStatutCourseEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  verifierSucces,
  type Coureur,
  type CourseDeScan,
} from './aide-scan';

/*
 * Incrément 4.9 : vainqueur unique (CA11 à CA14). Horloge applicative pilotée ; le planificateur serveur (tick d'1 s)
 * décide, le pilotage rafraîchit toutes les 3 s : 8 s de marge. Les instants d'avance sont calculés contre la durée de
 * Boucle (60 min ou 90 min) : PT10M puis PT50M placent l'horloge exactement au départ de la Boucle 2.
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

async function verifierVainqueurUnique(page: Page, v: Coureur, boucles: string): Promise<void> {
  await expect(lire(page, 'pilotage-vainqueurs')).toBeVisible(HUIT_SECONDES);
  await expect(lire(page, 'pilotage-vainqueurs-titre')).toHaveText('Vainqueur');
  await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(1);
  await expect(lire(page, 'pilotage-vainqueur-dossard')).toHaveText(String(v.dossard));
  await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText(v.pseudo);
  await expect(lire(page, 'pilotage-vainqueur-statut')).toHaveText('Vainqueur');
  await expect(lire(page, 'pilotage-vainqueur-boucles-terminees')).toHaveText(boucles);
  await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée');
  await expect(lire(page, 'pilotage-message-terminee')).toHaveText('La course est terminée.');
  await expect(lire(page, 'pilotage-inscrits-vide')).toHaveText('Aucun coureur en course.');
}

async function verifierAbandons(page: Page, abandonnes: Coureur[], boucle: number): Promise<void> {
  await expect(lire(page, 'pilotage-abandon')).toHaveCount(abandonnes.length);
  await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(abandonnes.map((c) => c.pseudo));
  await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText(abandonnes.map(() => `Boucle ${boucle}`));
  await expect(lire(page, 'pilotage-abandon-motif')).toHaveText(abandonnes.map(() => 'Hors délai'));
}

test('CA11 - Bruno seul a bouclé la Boucle 1 : au départ de la Boucle 2 il est vainqueur, Chloé et Tom en abandon, la Course terminée ; une Course de 90 min voisine reste en cours', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const longue = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 90, benevole: course.benevole });
  const [bruno, chloe, tom] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  await expect(lire(page, 'pilotage-compteur')).toHaveText('3 coureurs en course sur 3 inscrits');

  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 1);
    await scan.goto(`/benevole/courses/${longue.id}/scan`);
    await expect(lire(scan, 'scan-champ-jeton')).toBeVisible();
    await scanner(scan, longue.coureurs[0], 1);
  } finally {
    await contexteBenevole.close();
  }
  await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'En boucle', 'En boucle'], HUIT_SECONDES);
  await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);

  // 10 + 50 = 60 min après le départ : fin de la Boucle 1 (60 min), début de la Boucle 2
  await avancerHorloge(playwright, 'PT50M');

  await verifierVainqueurUnique(page, bruno, '1');
  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('2 coureurs en abandon');
  await verifierAbandons(page, [chloe, tom], 1);
  await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText(['0', '0']);
  await verifierSansRechargement(page);

  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(HORS_DELAI(1));
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(1));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);

  // la Boucle de 90 min n'est pas terminée à 60 min : rien n'est décidé, la Course reste en cours
  expect(lireStatutCourseEnBase(longue.id)).toBe('EN_COURS');
  for (const c of longue.coureurs) expect(lireInscriptionEnBase(longue.id, c.dossard)).toEqual(SANS_DECISION);
  const ongletLongue = await page.context().newPage();
  await ongletLongue.goto(urlPilotage(longue.id));
  await expect(lire(ongletLongue, 'pilotage-statut')).toHaveText('En cours');
  await expect(lire(ongletLongue, 'pilotage-vainqueurs')).toHaveCount(0);
  await expect(lire(ongletLongue, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 5');
});

test('CA12 - deux coureurs ont bouclé la Boucle 1 : seul Tom abandonne, pas de vainqueur ; Bruno seul sur la Boucle 2 est vainqueur, Chloé abandonne Boucle 2', async ({ browser, page, playwright }) => {
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

    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon', HUIT_SECONDES);
    await verifierAbandons(page, [tom], 1);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
    await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 5');
    expect(lireStatutCourseEnBase(course.id)).toBe('EN_COURS');

    // Boucle 2 : plus de 2 minutes après le Passage 1 de Bruno (anti-doublon 4.6)
    await scanner(scan, bruno, 2);
    await avancerHorloge(playwright, 'PT60M');
  } finally {
    await contexteBenevole.close();
  }

  await verifierVainqueurUnique(page, bruno, '2');
  await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('2 coureurs en abandon');
  await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText([chloe.pseudo, tom.pseudo]);
  await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText(['Boucle 2', 'Boucle 1']);
  await expect(lire(page, 'pilotage-abandon-motif')).toHaveText(['Hors délai', 'Hors délai']);
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(HORS_DELAI(2));
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(1));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA12 - rattrapage : Bruno scanné seul puis 3 Boucles d\'un coup, il est vainqueur, Chloé et Tom en abandon Boucle 1', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await scanner(scan, bruno, 1);
  } finally {
    await contexteBenevole.close();
  }

  await avancerHorloge(playwright, 'PT180M');

  await verifierVainqueurUnique(page, bruno, '1');
  await verifierAbandons(page, [chloe, tom], 1);
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(HORS_DELAI(1));
  expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(1));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
});

test('CA12 - coureur seul inscrit : scanné, il est vainqueur au départ de la Boucle suivante', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno] = course.coureurs;
  await ouvrirPilotage(page, course.id);
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await scanner(scan, bruno, 1);
  } finally {
    await contexteBenevole.close();
  }
  // pas encore vainqueur : la décision se prend au départ de la Boucle suivante
  await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText('A bouclé', HUIT_SECONDES);
  await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
  expect(lireStatutCourseEnBase(course.id)).toBe('EN_COURS');

  await avancerHorloge(playwright, 'PT60M');

  await verifierVainqueurUnique(page, bruno, '1');
  await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA12 - aucun scan : à la fin de la Boucle 1 la Course reste « En cours », ni vainqueur ni abandon', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  await ouvrirPilotage(page, course.id);

  await avancerHorloge(playwright, 'PT60M');

  await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 5', HUIT_SECONDES);
  await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  await expect(lire(page, 'pilotage-compteur')).toHaveText('3 coureurs en course sur 3 inscrits');
  await expect(lire(page, 'pilotage-aucun-abandon')).toBeVisible();
  await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
  expect(lireStatutCourseEnBase(course.id)).toBe('EN_COURS');
  for (const c of course.coureurs) expect(lireInscriptionEnBase(course.id, c.dossard)).toEqual(SANS_DECISION);
});

test('CA13 - dernière Boucle autorisée (M = 2) : Bruno seul sur la Boucle 2 est vainqueur, Chloé abandonne Boucle 2, la Course est terminée', async ({ browser, page, playwright }) => {
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
    // 10 + 50 + 60 = 120 min = M x D : fin de la dernière Boucle autorisée
    await avancerHorloge(playwright, 'PT60M');
  } finally {
    await contexteBenevole.close();
  }

  await verifierVainqueurUnique(page, bruno, '2');
  await verifierAbandons(page, [chloe], 2);
  await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText('1');
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(HORS_DELAI(2));
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA13 - dernière Boucle autorisée (M = 2) : Bruno et Chloé ont tous deux bouclé les deux Boucles, la Course reste « En cours » sans vainqueur', async ({ browser, page, playwright }) => {
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
    await scanner(scan, chloe, 2);
    await avancerHorloge(playwright, 'PT60M');
  } finally {
    await contexteBenevole.close();
  }

  await expect(lire(page, 'pilotage-message-boucle-max-terminee')).toBeVisible(HUIT_SECONDES);
  await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
  await expect(lire(page, 'pilotage-aucun-abandon')).toBeVisible();
  expect(lireStatutCourseEnBase(course.id)).toBe('EN_COURS');
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(SANS_DECISION);
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(SANS_DECISION);
});

async function rejouerParApi(playwright: import('@playwright/test').PlaywrightWorkerArgs['playwright'], course: CourseDeScan, identifiantScan: string, jetonQr: string): Promise<number> {
  return avecApi(playwright, async (ctx: APIRequestContext) => {
    await connecterParApi(ctx, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    expect((await ctx.get('/api/csrf')).status()).toBe(204);
    const jeton = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
    const reponse = await ctx.post(`/api/benevole/courses/${course.id}/passages`, { headers: { 'X-XSRF-TOKEN': jeton! }, data: { identifiantScan, jetonQr } });
    return reponse.status();
  });
}

test('CA14 - après la fin, le scan de Bruno (vainqueur) puis de Chloé (abandonnée) est refusé « Course terminée », sans Passage ; plus de bouton « Scanner » ; le rejeu d\'un scan antérieur reste 200', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe] = course.coureurs;
  await ouvrirPilotage(page, course.id);

  // l'écran de scan du bénévole reste ouvert pendant la fin de la Course (un rechargement afficherait « La course est terminée. »)
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    const identifiants: string[] = [];
    scan.on('request', (r) => {
      if (r.method() === 'POST' && r.url().endsWith(`/api/benevole/courses/${course.id}/passages`)) identifiants.push((r.postDataJSON() as { identifiantScan: string }).identifiantScan);
    });
    await ouvrirEcranDeScan(scan, course);
    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 1);
    expect(identifiants).toHaveLength(1);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'En boucle', 'En boucle'], HUIT_SECONDES);
    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-vainqueurs')).toBeVisible();

    for (const c of [bruno, chloe]) {
      await scannerParEntree(scan, c.jeton);
      await expect(lire(scan, 'scan-refus')).toBeVisible();
      await expect(lire(scan, 'scan-refus-motif')).toHaveText('Course terminée');
      await expect(lire(scan, 'scan-resultat-erreur')).toHaveText('La course est terminée.');
      // 409 COURSE_TERMINEE : pas de propriété d'extension, donc ni dossard ni pseudo dans le bloc de refus
      await expect(lire(scan, 'scan-refus-pseudo')).toHaveCount(0);
    }
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);

    await scan.goto('/benevole');
    const ligne = lire(scan, 'benevole-ligne-course').filter({ hasText: course.nom });
    await expect(ligne).toHaveCount(1);
    await expect(ligne.getByTestId('benevole-lien-scanner')).toHaveCount(0);

    expect(await rejouerParApi(playwright, course, identifiants[0], bruno.jeton)).toBe(200);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  } finally {
    await contexteBenevole.close();
  }
});
