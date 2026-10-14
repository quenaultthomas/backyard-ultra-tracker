import { expect } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, test } from './aide-horloge';
import { champ, lireInscriptionEnBase, lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';

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

  // 4.8 (RG19) : Bruno, sans Passage 1 alors qu'Alice en a un, est mis en abandon par le planificateur (tick d'1 s) ;
  // il n'est donc plus « sans passage sur la boucle précédente » mais « en abandon ». La continuité est couverte plus bas.
  await expect.poll(() => lireInscriptionEnBase(course.id, bruno.dossard).statut, { timeout: 8000 }).toBe('ABANDON');
  await scannerParEntree(page, bruno.jeton);
  await expect(page.getByTestId('scan-refus-motif')).toHaveText('Coureur en abandon');
  await expect(erreur).toHaveText('Ce coureur n\'est plus en course.');

  await scannerParEntree(page, alice.jeton);
  await expect(erreur).toHaveText('Ce coureur a déjà un passage sur la boucle 2.');

  await avancerHorloge(playwright, 'PT60M');

  // 4.8 : Bruno étant en abandon, la fin du temps est constatée avec Alice (Passage 2, toujours en course)
  await scannerParEntree(page, alice.jeton);
  await expect(erreur).toHaveText('Le temps de la dernière boucle est écoulé.');
  await expect(champ(page)).toHaveValue(alice.jeton);

  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN']);
});

test('CA19 - personne n\'a bouclé la Boucle 1 (aucun abandon) : le scan de la Boucle 2 est refusé « pas de passage sur la boucle précédente »', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 3, dureeBoucleMinutes: 60 });
  const [alice, bruno] = course.coureurs;
  await ouvrirEcranDeScan(page, course);

  await avancerHorloge(playwright, 'PT60M');

  await scannerParEntree(page, alice.jeton);
  await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('Ce coureur n\'a pas de passage sur la boucle précédente.');
  await scannerParEntree(page, bruno.jeton);
  await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('Ce coureur n\'a pas de passage sur la boucle précédente.');
  expect(lirePassagesEnBase(course.id)).toEqual([]);
  expect(lireInscriptionEnBase(course.id, alice.dossard).statut).toBe('EN_COURSE');
  expect(lireInscriptionEnBase(course.id, bruno.dossard).statut).toBe('EN_COURSE');
});
