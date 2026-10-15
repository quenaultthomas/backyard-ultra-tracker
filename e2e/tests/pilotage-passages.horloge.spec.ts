import { type Page } from '@playwright/test';
import { connecterAdminMaster, exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, expect, test } from './aide-horloge';
import { lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces, type CourseDeScan } from './aide-scan';

/*
 * Incrément 4.7 CA8 : bascule de Boucle, plafond et indépendance des Courses, horloge applicative pilotée.
 * Adapté en 4.8 (RG19) : un coureur sans Passage sur la Boucle terminée est en abandon, non plus « En boucle ».
 * Adapté en 4.9 (RG15) : un 4e coureur (Zoé) est scanné avec Bruno aux Boucles 1 et 2, sinon Bruno seul passant serait VAINQUEUR.
 */

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const lire = (page: Page, testid: string) => page.getByTestId(testid);
const SIX_SECONDES = { timeout: 6000 };
const HUIT_SECONDES = { timeout: 8000 };
const urlPilotage = (id: string) => `/administration/courses/${id}/pilotage`;

async function scanner(page: Page, c: CourseDeScan['coureurs'][number], boucle: number): Promise<void> {
  await scannerParEntree(page, c.jeton);
  await verifierSucces(page, c.dossard, c.pseudo, boucle);
}

test('CA8 - la bascule remet « En boucle » les coureurs ayant bouclé et met les autres en abandon, le plafond fige la Boucle, une autre Course est indépendante', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 4, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  const autre = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, dureeBoucleMinutes: 1 });
  const [bruno, chloe, tom, zoe] = course.coureurs;

  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
  const ongletAutre = await page.context().newPage();
  await page.goto(urlPilotage(course.id));
  await ongletAutre.goto(urlPilotage(autre.id));
  await expect(lire(page, 'pilotage-colonne-boucle')).toHaveText('Boucle 1');
  await expect(lire(ongletAutre, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 0');
  await expect(lire(ongletAutre, 'pilotage-en-boucle')).toHaveText('En boucle : 2');

  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    await ouvrirEcranDeScan(scan, course);

    await avancerHorloge(playwright, 'PT10M');
    await scanner(scan, bruno, 1);
    await scanner(scan, chloe, 1);
    await scanner(scan, zoe, 1);
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 3', SIX_SECONDES);
    await expect(lire(page, 'pilotage-en-boucle')).toHaveText('En boucle : 1');

    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-colonne-boucle')).toHaveText('Boucle 2', SIX_SECONDES);
    // 4.8 : Tom, sans Passage sur la Boucle 1, est en abandon (Hors délai, Boucle 1) ; Bruno et Chloé repartent « En boucle »
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(tom.pseudo, HUIT_SECONDES);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 1');
    await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText('0');
    await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText([bruno.pseudo, chloe.pseudo, zoe.pseudo]);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['En boucle', 'En boucle', 'En boucle']);
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 0');
    await expect(lire(page, 'pilotage-en-boucle')).toHaveText('En boucle : 3');
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['1', '1', '1']);

    await scanner(scan, bruno, 2);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'En boucle', 'En boucle'], SIX_SECONDES);
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['2', '1', '1']);
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 1');
    await scanner(scan, zoe, 2);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'En boucle', 'A bouclé'], SIX_SECONDES);
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 2');

    await avancerHorloge(playwright, 'PT60M');
    // 4.10 : adapté (RG14). Chloé, sans Passage sur la Boucle 2 (dernière autorisée), passe en abandon ; Bruno et Zoé,
    // seuls passants à la dernière Boucle, sont vainqueurs partagés (4.8/4.9 les laissaient « En cours »), la Course est terminée.
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText([chloe.pseudo, tom.pseudo], HUIT_SECONDES);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText(['Boucle 2', 'Boucle 1']);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(0);
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText([bruno.pseudo, zoe.pseudo]);
    await expect(lire(page, 'pilotage-vainqueur-boucles-terminees')).toHaveText(['2', '2']);
  } finally {
    await contexteBenevole.close();
  }

  // l'autre Course (1 min) n'a reçu aucun Passage : elle est indépendante de la première (4.10, RG13/RG14 : sans scan,
  // ses deux coureurs sont vainqueurs partagés et sa Course terminée, alors que la première avait ses propres issues)
  await expect(lire(ongletAutre, 'pilotage-statut')).toHaveText('Terminée', HUIT_SECONDES);
  await expect(lire(ongletAutre, 'pilotage-vainqueur')).toHaveCount(2);
  await expect(lire(ongletAutre, 'pilotage-vainqueur-boucles-terminees')).toHaveText(['0', '0']);
  expect(lirePassagesEnBase(autre.id)).toEqual([]);
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN', '2:1:SCAN', '4:1:SCAN', '4:2:SCAN']);
});

