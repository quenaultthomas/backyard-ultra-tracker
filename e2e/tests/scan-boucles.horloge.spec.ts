import { expect } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, test } from './aide-horloge';
import { champ, lireInscriptionEnBase, lirePassagesEnBase, lireStatutCourseEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA19 - le scan suit les boucles : continuité, doublon et fin du temps, vus par l\'écran', async ({ page, playwright }) => {
  // 4.9 (RG15) : un troisième coureur (Carole) est scanné comme Alice aux Boucles 1 et 2, sinon Alice seule passante
  // serait VAINQUEUR au départ de la Boucle 2 (scan refusé « Course terminée ») au lieu des refus testés ici.
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 2, dureeBoucleMinutes: 60 });
  const [alice, bruno, carole] = course.coureurs;
  await ouvrirEcranDeScan(page, course);
  const erreur = page.getByTestId('scan-resultat-erreur');

  await scannerParEntree(page, alice.jeton);
  await verifierSucces(page, alice.dossard, alice.pseudo, 1);
  await scannerParEntree(page, carole.jeton);
  await verifierSucces(page, carole.dossard, carole.pseudo, 1);

  await avancerHorloge(playwright, 'PT60M');

  await scannerParEntree(page, alice.jeton);
  await verifierSucces(page, alice.dossard, alice.pseudo, 2);
  await scannerParEntree(page, carole.jeton);
  await verifierSucces(page, carole.dossard, carole.pseudo, 2);

  // 4.8 (RG19) : Bruno, sans Passage 1 alors qu'Alice en a un, est mis en abandon par le planificateur (tick d'1 s) ;
  // il n'est donc plus « sans passage sur la boucle précédente » mais « en abandon ». La continuité est couverte plus bas.
  await avancerHorloge(playwright, 'PT3M');
  await expect.poll(() => lireInscriptionEnBase(course.id, bruno.dossard).statut, { timeout: 8000 }).toBe('ABANDON');
  await scannerParEntree(page, bruno.jeton);
  await expect(page.getByTestId('scan-refus-motif')).toHaveText('Coureur en abandon');
  await expect(erreur).toHaveText('Ce coureur n\'est plus en course.');

  await scannerParEntree(page, alice.jeton);
  await expect(erreur).toHaveText('Ce coureur a déjà un passage sur la boucle 2.');

  await avancerHorloge(playwright, 'PT63M');

  // 4.8 : Bruno étant en abandon, la fin du temps est constatée avec Alice et Carole (Passage 2, toujours en course).
  // 4.10 : adapté (RG14). Deux passants à la dernière Boucle sont vainqueurs partagés et la Course est terminée dans la
  // seconde ; le scan est refusé « Course terminée » (et non plus « Le temps de la dernière boucle est écoulé. »,
  // qui n'est plus observable de façon déterministe). On attend la décision pour un refus stable.
  await expect.poll(() => lireStatutCourseEnBase(course.id), { timeout: 8000 }).toBe('TERMINEE');
  await scannerParEntree(page, alice.jeton);
  await expect(page.getByTestId('scan-refus-motif')).toHaveText('Course terminée');
  await expect(erreur).toHaveText('La course est terminée.');
  await expect(champ(page)).toHaveValue(alice.jeton);
  expect(lireInscriptionEnBase(course.id, alice.dossard).statut).toBe('VAINQUEUR');
  expect(lireInscriptionEnBase(course.id, carole.dossard).statut).toBe('VAINQUEUR');

  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN', '3:1:SCAN', '3:2:SCAN']);
});

test('CA19 - personne n\'a bouclé la Boucle 1 : la Course se termine avec les deux coureurs vainqueurs, le scan de la Boucle 2 est refusé « Course terminée »', async ({ page, playwright }) => {
  // 4.10 : adapté (RG14). 4.8 laissait les deux coureurs en course (scan refusé « pas de passage sur la boucle
  // précédente ») ; désormais, personne n'ayant bouclé la Boucle 1, ils sont vainqueurs partagés et la Course est terminée.
  // Le test attend la décision du planificateur avant de scanner, pour un refus déterministe.
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 3, dureeBoucleMinutes: 60 });
  const [alice, bruno] = course.coureurs;
  await ouvrirEcranDeScan(page, course);

  await avancerHorloge(playwright, 'PT63M');
  await expect.poll(() => lireStatutCourseEnBase(course.id), { timeout: 8000 }).toBe('TERMINEE');

  await scannerParEntree(page, alice.jeton);
  await expect(page.getByTestId('scan-refus-motif')).toHaveText('Course terminée');
  await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('La course est terminée.');
  await scannerParEntree(page, bruno.jeton);
  await expect(page.getByTestId('scan-refus-motif')).toHaveText('Course terminée');
  await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('La course est terminée.');
  expect(lirePassagesEnBase(course.id)).toEqual([]);
  expect(lireInscriptionEnBase(course.id, alice.dossard)).toEqual({ statut: 'VAINQUEUR', motifAbandon: '', boucleAbandon: '' });
  expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual({ statut: 'VAINQUEUR', motifAbandon: '', boucleAbandon: '' });
});
