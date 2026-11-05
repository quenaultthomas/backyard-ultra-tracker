import { expect, test, avancerHorloge, avecApi } from './aide-horloge';
import { creerAdminParApi, exigerIdentifiantsAdminMaster, pseudoAdminUnique } from './aide-admin';
import { preparerCourseDeScan, lireStatutCourseEnBase } from './aide-scan';
import { scannerParApi } from './aide-suivi-coureurs';
import { lire, urlSuivi } from './aide-suivi';
import { prefixeUnique } from './aide-recherche-coureurs';
import { chercherAdmin, confirmerPassageAdmin, ligneAdmin, ouvrirConfirmationAdmin, ouvrirEnMaster, resultatAdminDe, t } from './aide-passage-admin';

/*
 * Incrément 9.7 (CA17, CA21) avec horloge pilotée : un coureur dont la seule Course est TERMINEE est promu en admin,
 * reste affiché au suivi public avec son pseudo, puis supprimé : « Coureur anonyme » avec son dossard.
 */
test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA17 et CA21 - E4 (Course terminée seulement) est promu, reste au suivi public, puis supprimé : « Coureur anonyme » avec son dossard', async ({ page, playwright }) => {
  test.setTimeout(150_000);
  // sur une base neuve, E4 serait le seul admin actif : sa suppression serait refusée (dernier administrateur)
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, pseudoAdminUnique()));
  const e4 = `${prefixeUnique()}e4`;
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, nombreMaxBoucles: 5, pseudos: [e4] });
  const [coureur] = course.coureurs;
  await avancerHorloge(playwright, 'PT10M');
  await scannerParApi(playwright, course, coureur);
  await avancerHorloge(playwright, 'PT53M');
  await expect.poll(() => lireStatutCourseEnBase(course.id), { timeout: 15_000 }).toBe('TERMINEE');

  await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
  await chercherAdmin(page, e4);
  await ouvrirConfirmationAdmin(page, e4);
  await confirmerPassageAdmin(page);
  await expect(t(page, 'admin-message-action')).toHaveText(`Le coureur ${e4} est maintenant administrateur.`);
  await expect(resultatAdminDe(page, e4)).toHaveCount(0);
  await expect(ligneAdmin(page, e4)).toHaveCount(1);

  // le suivi public affiche toujours son pseudo et son dossard
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée');
  const ligne = lire(page, 'suivi-vainqueur-' + coureur.dossard);
  await expect(ligne).toContainText(e4);
  await expect(ligne).toContainText(`Dossard ${coureur.dossard}`);

  // suppression par l'admin master : anonymisation
  await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
  await ligneAdmin(page, e4).getByTestId('admin-bouton-supprimer').click();
  await ligneAdmin(page, e4).getByTestId('admin-suppression-bouton-confirmer').click();
  await expect(ligneAdmin(page, e4)).toHaveCount(0);

  // le suivi public a un micro-cache de lecture (7.2) : on attend que l'API reflète l'anonymisation avant de recharger la page
  await expect
    .poll(async () => ((await (await page.request.get(`/api/courses/${course.id}/suivi`)).json()) as { vainqueurs: Array<{ pseudo: string }> }).vainqueurs[0]?.pseudo)
    .toBe('Coureur anonyme');
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée');
  const apres = lire(page, 'suivi-vainqueur-' + coureur.dossard);
  await expect(apres).toContainText('Coureur anonyme');
  await expect(apres).toContainText(`Dossard ${coureur.dossard}`);
  await expect(apres).not.toContainText(e4);
});
