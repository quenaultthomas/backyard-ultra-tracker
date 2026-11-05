import { expect, test, avancerHorloge } from './aide-horloge';
import { creerAdminParApi, exigerIdentifiantsAdminMaster, pseudoAdminUnique } from './aide-admin';
import { preparerCourseDeScan, lireStatutCourseEnBase } from './aide-scan';
import { scannerParApi } from './aide-suivi-coureurs';
import { lire, urlSuivi } from './aide-suivi';
import { avecApi, prefixeUnique } from './aide-recherche-coureurs';
import { chercher, confirmerPassage, ligneBenevole, ouvrirConfirmation, ouvrirEnAdmin, resultatDe, t } from './aide-passage-benevole';

/*
 * Incrément 9.6b (CA26, CA29) avec horloge pilotée : un coureur dont la seule Course est TERMINEE peut être
 * promu, puis supprimé ; ses résultats restent affichés au suivi public sous « Coureur anonyme ».
 */
test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA26 et CA29 - E4 (Course terminée seulement) est promu puis supprimé ; le suivi public affiche « Coureur anonyme » avec son dossard', async ({ page, playwright }) => {
  test.setTimeout(150_000);
  const e4 = `${prefixeUnique()}e4`;
  const nadia = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, pseudos: [e4] });
  const [coureur] = course.coureurs;
  await avancerHorloge(playwright, 'PT10M'); // pas de scan trop rapproché du départ
  await scannerParApi(playwright, course, coureur);
  await avancerHorloge(playwright, 'PT53M');
  await expect.poll(() => lireStatutCourseEnBase(course.id), { timeout: 15_000 }).toBe('TERMINEE');

  await ouvrirEnAdmin(page, nadia, '/administration/benevoles', 'titre-benevoles');
  await chercher(page, e4);
  await ouvrirConfirmation(page, e4);
  await confirmerPassage(page);
  await expect(t(page, 'benevole-message-action')).toHaveText(`Le coureur ${e4} est maintenant bénévole.`);
  await expect(resultatDe(page, e4)).toHaveCount(0);
  await expect(ligneBenevole(page, e4)).toHaveCount(1);


  await ligneBenevole(page, e4).getByTestId('benevole-bouton-supprimer').click();
  await ligneBenevole(page, e4).getByTestId('benevole-suppression-bouton-confirmer').click();
  await expect(ligneBenevole(page, e4)).toHaveCount(0);

  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée');
  const ligne = lire(page, 'suivi-vainqueur-' + coureur.dossard);
  await expect(ligne).toContainText('Coureur anonyme');
  await expect(ligne).toContainText(`Dossard ${coureur.dossard}`);
  await expect(ligne).not.toContainText(e4);
});
