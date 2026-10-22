import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { MESSAGE_ATTENTE, MOTIF_PASSAGES, attendreFileVide, probleme } from './aide-file-scan';
import { ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree } from './aide-scan';

/*
 * Incrément 7.1 (CA19, RG9) : l'aide « demandez à un administrateur de réintégrer le coureur » n'accompagne que les scans
 * refusés INSCRIPTION_NON_EN_COURSE ; pour COURSE_TERMINEE et TEMPS_DE_COURSE_ECOULE, aucun recours dans l'application.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const AIDE = 'Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur.';

test('CA19 - scans à vérifier : l\'aide de réintégration est absente pour « Course terminée » et « Temps de course écoulé », présente pour « Coureur en abandon », le détail reste affiché', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3 });
  const [termine, ecoule, abandon] = course.coureurs;
  await ouvrirEcranDeScan(page, course);
  await page.context().setOffline(true);
  await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
  for (const [i, c] of [termine, ecoule, abandon].entries()) {
    await scannerParEntree(page, c.jeton);
    await expect(page.getByTestId('scan-resultat-attente')).toHaveText(MESSAGE_ATTENTE);
    await expect(page.getByTestId('scan-compteur-attente')).toHaveText(`${i + 1} en attente`);
  }

  await page.route(MOTIF_PASSAGES, (route) => {
    const { jetonQr } = route.request().postDataJSON() as { jetonQr: string };
    if (jetonQr === termine.jeton) return route.fulfill(probleme(409, 'COURSE_TERMINEE', 'La course est terminée.'));
    if (jetonQr === ecoule.jeton) return route.fulfill(probleme(409, 'TEMPS_DE_COURSE_ECOULE', 'Le temps de la dernière boucle est écoulé.'));
    const refus = probleme(409, 'INSCRIPTION_NON_EN_COURSE', 'Ce coureur n\'est plus en course.');
    return route.fulfill({ ...refus, body: JSON.stringify({ ...JSON.parse(refus.body), statut: 'ABANDON', dossard: abandon.dossard, pseudo: abandon.pseudo }) });
  });
  await page.context().setOffline(false);

  const lignes = page.getByTestId('scan-a-verifier-ligne');
  await expect(lignes).toHaveCount(3);
  const ligne = (motif: string) => lignes.filter({ hasText: motif });
  await expect(ligne('Course terminée').getByTestId('scan-a-verifier-detail')).toHaveText('La course est terminée.');
  await expect(ligne('Course terminée').getByTestId('scan-a-verifier-aide')).toHaveCount(0);
  await expect(ligne('Temps de course écoulé').getByTestId('scan-a-verifier-detail')).toHaveText('Le temps de la dernière boucle est écoulé.');
  await expect(ligne('Temps de course écoulé').getByTestId('scan-a-verifier-aide')).toHaveCount(0);
  await expect(ligne('Coureur en abandon').getByTestId('scan-a-verifier-detail')).toHaveText('Ce coureur n\'est plus en course.');
  await expect(ligne('Coureur en abandon').getByTestId('scan-a-verifier-aide')).toHaveText(AIDE);
  await expect(page.getByTestId('scan-a-verifier-aide')).toHaveCount(1);
  await attendreFileVide(page);
});
