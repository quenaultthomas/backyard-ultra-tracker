import { type Page } from '@playwright/test';
import { connecterAdminMaster, exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, expect, test } from './aide-horloge';
import { lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces, type CourseDeScan } from './aide-scan';

/*
 * Incrément 4.7 CA8 : bascule de Boucle, plafond et indépendance des Courses, horloge applicative pilotée.
 */

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const lire = (page: Page, testid: string) => page.getByTestId(testid);
const SIX_SECONDES = { timeout: 6000 };
const urlPilotage = (id: string) => `/administration/courses/${id}/pilotage`;

async function scanner(page: Page, c: CourseDeScan['coureurs'][number], boucle: number): Promise<void> {
  await scannerParEntree(page, c.jeton);
  await verifierSucces(page, c.dossard, c.pseudo, boucle);
}

test('CA8 - la bascule remet tout le monde « En boucle », le plafond fige la Boucle, une autre Course est indépendante', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  const autre = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, dureeBoucleMinutes: 1 });
  const [bruno, chloe] = course.coureurs;

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
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 2', SIX_SECONDES);
    await expect(lire(page, 'pilotage-en-boucle')).toHaveText('En boucle : 1');

    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-colonne-boucle')).toHaveText('Boucle 2', SIX_SECONDES);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['En boucle', 'En boucle', 'En boucle']);
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 0');
    await expect(lire(page, 'pilotage-en-boucle')).toHaveText('En boucle : 3');
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['1', '1', '0']);

    await scanner(scan, bruno, 2);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'En boucle', 'En boucle'], SIX_SECONDES);
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['2', '1', '0']);
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 1');

    await avancerHorloge(playwright, 'PT60M');
    await expect(lire(page, 'pilotage-message-boucle-max-terminee')).toBeVisible(SIX_SECONDES);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'En boucle', 'En boucle']);
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['2', '1', '0']);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  } finally {
    await contexteBenevole.close();
  }

  // l'autre Course (1 min) n'a reçu aucun Passage : ses compteurs n'ont pas bougé avec ceux de la première
  await expect(lire(ongletAutre, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 0');
  expect(lirePassagesEnBase(autre.id)).toEqual([]);
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN', '2:1:SCAN']);
});

