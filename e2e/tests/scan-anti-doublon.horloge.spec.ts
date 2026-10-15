import { expect } from '@playwright/test';
import { MOT_DE_PASSE_BENEVOLE_CREE, demarrerCourseParApi, exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, avecApi, test } from './aide-horloge';
import { lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, seConnecterVers, urlScan, verifierSucces } from './aide-scan';

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

/**
 * Le temps réel s'écoule entre deux avances d'horloge (connexion, appels API) : les secondes restantes
 * annoncées sont donc au plus celles de la spec, et au moins 1.
 */
function secondesAnnoncees(detail: string | null): number {
  const trouve = /attendez (\d+) secondes? avant un nouveau passage\.$/.exec(detail ?? '');
  expect(trouve, `détail : ${detail}`).not.toBeNull();
  return Number(trouve![1]);
}

test('CA9 - boucle de 60 min : un re-scan juste après le départ de la boucle suivante est refusé « Scan trop rapproché », accepté après le délai', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 3, dureeBoucleMinutes: 60 });
  // 4.9 (RG15) : Chloé est scannée aussi sur la Boucle 1, sinon Bruno seul passant serait VAINQUEUR au départ de la Boucle 2.
  const [bruno, chloe] = course.coureurs;
  await ouvrirEcranDeScan(page, course);

  await avancerHorloge(playwright, 'PT59M50S');
  await scannerParEntree(page, bruno.jeton);
  await verifierSucces(page, bruno.dossard, bruno.pseudo, 1);
  await scannerParEntree(page, chloe.jeton);
  await verifierSucces(page, chloe.dossard, chloe.pseudo, 1);

  await avancerHorloge(playwright, 'PT15S');
  await scannerParEntree(page, bruno.jeton);
  await expect(page.getByTestId('scan-refus-motif')).toHaveText('Scan trop rapproché');
  await expect(page.getByTestId('scan-refus-dossard')).toHaveText('Dossard 1');
  await expect(page.getByTestId('scan-refus-pseudo')).toHaveText(bruno.pseudo);
  await expect(page.getByTestId('scan-refus-boucle')).toHaveText('Boucle 2');
  const detail = await page.getByTestId('scan-resultat-erreur').textContent();
  const restantes = secondesAnnoncees(detail?.trim() ?? null);
  expect(restantes).toBeLessThanOrEqual(105);
  expect(restantes).toBeGreaterThanOrEqual(90);
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);

  await avancerHorloge(playwright, 'PT105S');
  await scannerParEntree(page, bruno.jeton);
  await verifierSucces(page, bruno.dossard, bruno.pseudo, 2);
  await expect(page.getByTestId('scan-refus')).toHaveCount(0);
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN', '2:1:SCAN']);
});

test('CA10 - boucle de 1 min : un enchaînement ordinaire n\'est pas gêné, un re-scan à 10 s est refusé, accepté après le délai de 15 s', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, dureeBoucleMinutes: 1, demarree: false });
  const [bruno, chloe] = course.coureurs;
  // L'écran est ouvert avant le démarrage (connexion), puis rechargé une fois la course démarrée : les avances d'horloge restent précises.
  await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
  await expect(page.getByTestId('scan-message-etat')).toHaveText('La course n\'est pas démarrée.');
  await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, course.id));
  await page.reload();
  await expect(page.getByTestId('scan-champ-jeton')).toBeVisible();

  await avancerHorloge(playwright, 'PT20S');
  await scannerParEntree(page, bruno.jeton);
  await verifierSucces(page, bruno.dossard, bruno.pseudo, 1);

  await avancerHorloge(playwright, 'PT35S');
  await scannerParEntree(page, chloe.jeton);
  await verifierSucces(page, chloe.dossard, chloe.pseudo, 1);

  await avancerHorloge(playwright, 'PT10S');
  await scannerParEntree(page, bruno.jeton);
  await verifierSucces(page, bruno.dossard, bruno.pseudo, 2);

  await scannerParEntree(page, chloe.jeton);
  await expect(page.getByTestId('scan-refus-motif')).toHaveText('Scan trop rapproché');
  await expect(page.getByTestId('scan-refus-dossard')).toHaveText('Dossard 2');
  const detail = await page.getByTestId('scan-resultat-erreur').textContent();
  const restantes = secondesAnnoncees(detail?.trim() ?? null);
  expect(restantes).toBeGreaterThanOrEqual(1);
  expect(restantes).toBeLessThanOrEqual(5);
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN', '2:1:SCAN']);

  await avancerHorloge(playwright, 'PT5S');
  await scannerParEntree(page, chloe.jeton);
  await verifierSucces(page, chloe.dossard, chloe.pseudo, 2);
  expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '1:2:SCAN', '2:1:SCAN', '2:2:SCAN']);
});
