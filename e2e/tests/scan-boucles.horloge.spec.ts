import { expect } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, test } from './aide-horloge';
import { champ, lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA19 - le scan suit les boucles : continuité, doublon et fin du temps, vus par l\'écran', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  const [alice, bruno] = course.coureurs;
  await ouvrirEcranDeScan(page, course);
  const erreur = page.getByTestId('scan-resultat-erreur');

  await scannerParEntree(page, alice.jeton);
  await verifierSucces(page, alice.dossard, alice.pseudo, 1);

  await avancerHorloge(playwright, 'PT60M');

  await scannerParEntree(page, alice.jeton);
  await verifierSucces(page, alice.dossard, alice.pseudo, 2);

  await scannerParEntree(page, bruno.jeton);
  await expect(erreur).toHaveText('Ce coureur n\'a pas de passage sur la boucle précédente.');

  await scannerParEntree(page, alice.jeton);
  await expect(erreur).toHaveText('Ce coureur a déjà un passage sur la boucle 2.');

  await avancerHorloge(playwright, 'PT60M');

  await scannerParEntree(page, bruno.jeton);
  await expect(erreur).toHaveText('Le temps de la dernière boucle est écoulé.');
  await expect(champ(page)).toHaveValue(bruno.jeton);

  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN']);
});
