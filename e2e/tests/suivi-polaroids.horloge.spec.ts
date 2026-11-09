import { expect, test, avancerHorloge } from './aide-horloge';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { lire, urlSuivi } from './aide-suivi';
import { CINQ_SECONDES, scannerParApi } from './aide-suivi-coureurs';
import { angle, courseDeQuatre, polaroidDe } from './aide-polaroid';
import { pseudoUnique } from './aide-connexion';
import { preparerCourseDeScan } from './aide-scan';
import { poserPhotoCoureur } from './aide-polaroid';

/*
 * Incrément 10.2 avec horloge applicative pilotée : CA18 (vainqueur mis en valeur dans le bandeau).
 */

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA18 - vainqueur unique : polaroïd plus large, droit, avec photo, sans tableau ; vainqueurs partagés : pluriel et deux polaroïds par dossard', async ({ page, playwright }) => {
  const { course, alice, karim } = await courseDeQuatre(playwright);
  // Alice et Karim passent la Boucle 1 ; Tom et Sophie sont retirés de la comparaison par l'abandon automatique.
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, karim);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('4');
  const largeurTableau = await polaroidDe(page, 1).evaluate((el) => (el as HTMLElement).offsetWidth);

  await avancerHorloge(playwright, 'PT64M');
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2', CINQ_SECONDES);
  await scannerParApi(playwright, course, karim);
  await avancerHorloge(playwright, 'PT63M');

  const bandeau = lire(page, 'suivi-vainqueurs');
  await expect(bandeau).toContainText('Vainqueur', CINQ_SECONDES);
  await expect(bandeau.getByRole('heading', { level: 2 })).toHaveText('Vainqueur');
  const vainqueur = lire(page, 'suivi-vainqueur-2');
  await expect(vainqueur).toContainText('Dossard 2');
  await expect(vainqueur).toContainText(karim.pseudo);
  await expect(vainqueur.getByTestId('suivi-polaroid-photo')).toHaveAttribute('alt', `Photo de ${karim.pseudo}`);
  await expect(vainqueur.getByTestId('suivi-polaroid')).toHaveClass(/polaroid--vainqueur/);
  await expect(vainqueur.getByTestId('suivi-polaroid-epingle')).toHaveCount(0);
  const largeur = await vainqueur.getByTestId('suivi-polaroid').evaluate((el) => (el as HTMLElement).offsetWidth);
  expect(largeur, 'plus large qu\'un polaroïd de tableau').toBeGreaterThan(largeurTableau);
  expect(await angle(vainqueur.getByTestId('suivi-polaroid'))).toBe(0);
  await expect(vainqueur.getByTestId('suivi-polaroid')).not.toHaveAttribute('data-inclinaison', /.+/);
  await expect(lire(page, 'suivi-tableau')).toHaveCount(0);
  await expect(lire(page, 'suivi-abandon-1')).toContainText(alice.pseudo);
});

test('CA18 - vainqueurs partagés (aucun Passage sur la Boucle 1) : « Vainqueurs » au pluriel et deux polaroïds dans l\'ordre des dossards', async ({ page, playwright }) => {
  const pseudos = [pseudoUnique('alice'), pseudoUnique('karim')];
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, pseudos });
  await poserPhotoCoureur(playwright, pseudos[1], 'photo.jpg', 'image/jpeg');
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');

  await avancerHorloge(playwright, 'PT64M');
  const bandeau = lire(page, 'suivi-vainqueurs');
  await expect(bandeau.getByRole('heading', { level: 2 })).toHaveText('Vainqueurs', CINQ_SECONDES);
  await expect(bandeau.locator('li[data-testid^="suivi-vainqueur-"]')).toHaveCount(2);
  await expect(bandeau.locator('li[data-testid^="suivi-vainqueur-"]').nth(0)).toHaveAttribute('data-testid', 'suivi-vainqueur-1');
  await expect(bandeau.locator('li[data-testid^="suivi-vainqueur-"]').nth(1)).toHaveAttribute('data-testid', 'suivi-vainqueur-2');
  await expect(lire(page, 'suivi-vainqueur-1').getByTestId('suivi-polaroid-silhouette')).toBeVisible();
  await expect(lire(page, 'suivi-vainqueur-2').getByTestId('suivi-polaroid-photo')).toBeVisible();
  await expect(lire(page, 'suivi-tableau')).toHaveCount(0);
});
