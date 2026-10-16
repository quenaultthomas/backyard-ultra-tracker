import { type Page } from '@playwright/test';
import { MOT_DE_PASSE_ADMIN_MASTER, PSEUDO_ADMIN_MASTER, creerAdminParApi, exigerIdentifiantsAdminMaster, pseudoAdminUnique } from './aide-admin';
import { connecterAdmin, connecterPatron, lire, ouvrirPilotage, urlPilotage } from './aide-abandon';
import { avancerHorloge, avecApi, expect, test } from './aide-horloge';
import { boutonReintegrer, reintegrerParApi, reintegrerParEcran } from './aide-reintegration';
import {
  lireInscriptionEnBase,
  lirePassagesDetailEnBase,
  lireStatutCourseEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  verifierSucces,
  type Coureur,
  type CourseDeScan,
} from './aide-scan';

/*
 * Incrément 4.12 : réintégration avec horloge pilotée (CA13, CA15, fin de CA16). Le planificateur serveur (tick d'1 s)
 * décide, le pilotage rafraîchit toutes les 3 s : 8 s de marge. PT10M puis PT50M placent l'horloge exactement au
 * départ de la Boucle 2 (Boucles de 60 min) ; un Passage de la Boucle 2 est scanné 10 min après le départ pour
 * respecter le délai de 4.6.
 */
test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const HUIT_SECONDES = { timeout: 8000 };
const SANS_DECISION = { statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' };
const HORS_DELAI = (boucle: number) => ({ statut: 'ABANDON', motifAbandon: 'HORS_DELAI', boucleAbandon: String(boucle) });
const VAINQUEUR = { statut: 'VAINQUEUR', motifAbandon: '', boucleAbandon: '' };

async function scanner(page: Page, c: Coureur, boucle: number): Promise<void> {
  await scannerParEntree(page, c.jeton);
  await verifierSucces(page, c.dossard, c.pseudo, boucle);
}

/** Course Bruno / Chloé / Tom ; Bruno et Chloé scannés en Boucle 1 ; horloge au départ de la Boucle 2 : Tom est tombé. */
async function jusqueAuDepartBoucle2(page: Page, scan: Page, course: CourseDeScan, playwright: Parameters<typeof avancerHorloge>[0]): Promise<void> {
  const [bruno, chloe] = course.coureurs;
  await avancerHorloge(playwright, 'PT10M');
  await scanner(scan, bruno, 1);
  await scanner(scan, chloe, 1);
  await avancerHorloge(playwright, 'PT50M');
  await expect(lire(page, 'pilotage-boucle-courante')).toContainText('Boucle 2', HUIT_SECONDES);
}

test('CA13 - abandon automatique de Tom puis réintégration : panneau, annulation, confirmation, badge « corrigé », Passage CORRECTION en base', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  const nadia = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await connecterAdmin(page, nadia);
    await ouvrirPilotage(page, course.id, 3);

    await jusqueAuDepartBoucle2(page, scan, course, playwright);

    // Tom est tombé : « Boucle 1 », « Hors délai », 0 Boucle terminée, bouton « Réintégrer »
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1, HUIT_SECONDES);
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(tom.pseudo);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 1');
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('Hors délai');
    await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText('0');
    await expect(lire(page, 'pilotage-abandon-corrige')).toHaveCount(0);
    await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveCount(1);
    expect(lireInscriptionEnBase(course.id, 3)).toEqual(HORS_DELAI(1));

    const bouton = boutonReintegrer(page, 3, tom.pseudo);
    await bouton.click();
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-reintegrer-titre')).toHaveText(`Réintégrer le coureur n° 3 ${tom.pseudo} ?`);
    await expect(lire(page, 'pilotage-reintegrer-titre')).toBeFocused();

    // Annuler : fermé, focus rendu au bouton, Tom toujours en abandon
    await lire(page, 'pilotage-reintegrer-annuler').click();
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
    await expect(bouton).toBeFocused();
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    expect(lireInscriptionEnBase(course.id, 3)).toEqual(HORS_DELAI(1));

    // Le polling pendant que le panneau est ouvert ne le ferme pas
    await bouton.click();
    await expect(lire(page, 'pilotage-reintegrer-titre')).toBeFocused();
    await page.waitForResponse((r) => r.request().method() === 'GET' && /\/pilotage$/.test(new URL(r.url()).pathname), HUIT_SECONDES);
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toBeVisible();

    await lire(page, 'pilotage-reintegrer-confirmer').click();
    await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveText(`Coureur n° 3 ${tom.pseudo} réintégré : 1 boucle corrigée.`);
    await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveAttribute('role', 'status');
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);

    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);
    const ligneTom = lire(page, 'pilotage-inscrit').filter({ hasText: tom.pseudo });
    await expect(ligneTom.getByTestId('pilotage-inscrit-boucles-terminees')).toHaveText(/^\s*1\s/);
    await expect(ligneTom.getByTestId('pilotage-inscrit-corrige')).toHaveText('corrigé');
    await expect(ligneTom.getByTestId('pilotage-inscrit-boucles-terminees')).toContainText('dont 1 boucle corrigée');
    await expect(lire(page, 'pilotage-inscrit-corrige')).toHaveCount(1);
    await expect(lire(page, 'pilotage-compteur')).toHaveText('3 coureurs en course sur 3 inscrits');
    await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');

    expect(lireInscriptionEnBase(course.id, 3)).toEqual(SANS_DECISION);
    expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(SANS_DECISION);
    expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(SANS_DECISION);
    expect(lirePassagesDetailEnBase(course.id)).toEqual(['1:1:SCAN:avecDate', '2:1:SCAN:avecDate', '3:1:CORRECTION:sansDate']);
    expect(lireStatutCourseEnBase(course.id)).toBe('EN_COURS');

    // Le badge reste après un rechargement (dérivé des Passages)
    await page.reload();
    await expect(lire(page, 'pilotage-inscrit-corrige')).toHaveCount(1);
  } finally {
    await contexteBenevole.close();
  }
});

test('CA15 - M = 2 : Tom réintégré et scanné en Boucle 2, les trois sont vainqueurs ; Tom affiche 2 Boucles terminées avec le badge « corrigé »', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  const nadia = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await connecterAdmin(page, nadia);
    await ouvrirPilotage(page, course.id, 3);

    await jusqueAuDepartBoucle2(page, scan, course, playwright);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1, HUIT_SECONDES);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 1');

    await reintegrerParEcran(page, 3, tom.pseudo, 1);

    // Boucle 2 : les trois scannent (Bruno et Chloé 10 min après leur Passage de la Boucle 1 : délai de 4.6 respecté)
    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 2);
    await scanner(scan, chloe, 2);
    await scanner(scan, tom, 2);

    await avancerHorloge(playwright, 'PT50M');

    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-vainqueurs-titre')).toHaveText('Vainqueurs');
    await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(3);
    await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');
    await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveCount(0);
    const ligneTom = lire(page, 'pilotage-vainqueur').filter({ hasText: tom.pseudo });
    await expect(ligneTom.getByTestId('pilotage-vainqueur-boucles-terminees')).toHaveText(/^\s*2\s/);
    await expect(ligneTom.getByTestId('pilotage-vainqueur-corrige')).toHaveText('corrigé');
    await expect(ligneTom.getByTestId('pilotage-vainqueur-boucles-terminees')).toContainText('dont 1 boucle corrigée');
    for (const c of [bruno, chloe]) {
      const ligne = lire(page, 'pilotage-vainqueur').filter({ hasText: c.pseudo });
      await expect(ligne.getByTestId('pilotage-vainqueur-boucles-terminees')).toHaveText('2');
      await expect(ligne.getByTestId('pilotage-vainqueur-corrige')).toHaveCount(0);
    }

    for (const c of [bruno, chloe, tom]) expect(lireInscriptionEnBase(course.id, c.dossard)).toEqual(VAINQUEUR);
    expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
    expect(lirePassagesDetailEnBase(course.id)).toEqual([
      '1:1:SCAN:avecDate',
      '1:2:SCAN:avecDate',
      '2:1:SCAN:avecDate',
      '2:2:SCAN:avecDate',
      '3:1:CORRECTION:sansDate',
      '3:2:SCAN:avecDate',
    ]);
  } finally {
    await contexteBenevole.close();
  }
});

test('CA15 - variante : Tom réintégré mais non scanné en Boucle 2 : Bruno et Chloé vainqueurs, Tom « Hors délai » Boucle 2 avec le badge conservé et plus de bouton', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await connecterPatron(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);

    await jusqueAuDepartBoucle2(page, scan, course, playwright);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1, HUIT_SECONDES);
    await reintegrerParEcran(page, 3, tom.pseudo, 1);

    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 2);
    await scanner(scan, chloe, 2);
    await avancerHorloge(playwright, 'PT50M');

    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-vainqueurs-titre')).toHaveText('Vainqueurs');
    await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(2);
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText([bruno.pseudo, chloe.pseudo]);
    await expect(lire(page, 'pilotage-vainqueur-corrige')).toHaveCount(0);

    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(tom.pseudo);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 2');
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('Hors délai');
    await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText(/^\s*1\s/);
    await expect(lire(page, 'pilotage-abandon-corrige')).toHaveText('corrigé');
    await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toContainText('dont 1 boucle corrigée');
    await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Réintégrer/ })).toHaveCount(0);

    expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(2));
    expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
    expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(VAINQUEUR);
    expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
    expect(lirePassagesDetailEnBase(course.id).filter((l) => l.startsWith('3:'))).toEqual(['3:1:CORRECTION:sansDate']);
  } finally {
    await contexteBenevole.close();
  }
});

test('CA16 - course terminée (Bruno seul scanné) : aucun bouton « Réintégrer » sur Chloé ni Tom en abandon, la base ne change pas', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await connecterPatron(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);

    await scanner(scan, bruno, 1);
    await avancerHorloge(playwright, 'PT60M');

    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(1);
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText(bruno.pseudo);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(2);
    await expect(lire(page, 'pilotage-abandon-reintegrer')).toHaveCount(0);
    await expect(lire(page, 'pilotage-abandons-colonne-action')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Réintégrer/ })).toHaveCount(0);

    expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(HORS_DELAI(1));
    expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual(HORS_DELAI(1));
    expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual(VAINQUEUR);
    expect(lirePassagesDetailEnBase(course.id)).toEqual(['1:1:SCAN:avecDate']);
  } finally {
    await contexteBenevole.close();
  }
});

test('CA16 - le POST de réintégration sur la course terminée par l\'horloge est refusé en 409 COURSE_TERMINEE', async ({ browser, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe] = course.coureurs;
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);
    await scanner(scan, bruno, 1);
  } finally {
    await contexteBenevole.close();
  }
  await avancerHorloge(playwright, 'PT60M');
  await expect.poll(() => lireStatutCourseEnBase(course.id), HUIT_SECONDES).toBe('TERMINEE');
  const reponse = await avecApi(playwright, (ctx) =>
    reintegrerParApi(ctx, course.id, chloe.inscriptionId, { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER }),
  );
  expect(reponse.statut).toBe(409);
  expect(reponse.corps['code']).toBe('COURSE_TERMINEE');
  expect(lireInscriptionEnBase(course.id, chloe.dossard)).toEqual(HORS_DELAI(1));
});
