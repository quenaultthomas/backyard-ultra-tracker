import { expect, test, avancerHorloge } from './aide-horloge';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { lireStatutCourseEnBase } from './aide-scan';
import { compterRequetesSuivi, lire, poserMarqueur, urlSuivi, verifierSansRechargement } from './aide-suivi';
import {
  CINQ_SECONDES,
  SECTION_EN_COURSE,
  abandonManuelParApi,
  courseDemarree,
  laisserPasser,
  scannerParApi,
  section,
} from './aide-suivi-coureurs';

/*
 * Incrément 6.2 avec horloge applicative pilotée (CA17, CA18, CA19, CA23, CA24). Les scans se font par l'API dès le
 * départ de chaque Boucle (aucun scan trop rapproché). Planificateur 1 s + polling 3 s : 5 s de marge.
 */

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA17 - abandon automatique : le coureur sans passage passe en « Hors délai », Boucle 1, sans rechargement', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright, 3);
  const [alice, bruno, karim] = course.coureurs;
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('3');
  await poserMarqueur(page);

  await avancerHorloge(playwright, 'PT64M');
  const ligne = lire(page, 'suivi-abandon-' + karim.dossard);
  await expect(ligne).toContainText(karim.pseudo, CINQ_SECONDES);
  await expect(ligne).toContainText('Boucle 1');
  await expect(ligne).toContainText('Hors délai');
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('1');
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toBeVisible();
  await expect(lire(page, 'suivi-coureur-' + bruno.dossard)).toBeVisible();
  await verifierSansRechargement(page);
});

test('CA18 - vainqueur unique : bandeau « Vainqueur », abandon Boucle 2 « Hors délai », plus de section en course, polling arrêté', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright, 3);
  const [alice, bruno] = course.coureurs;
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  const requetes = compterRequetesSuivi(page);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('3');
  await poserMarqueur(page);

  await avancerHorloge(playwright, 'PT64M');
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('1', CINQ_SECONDES);
  await scannerParApi(playwright, course, alice);
  await avancerHorloge(playwright, 'PT63M');

  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée', CINQ_SECONDES);
  const bandeau = lire(page, 'suivi-vainqueurs');
  await expect(bandeau).toContainText('Vainqueur');
  await expect(bandeau.getByRole('heading', { level: 2 })).toHaveText('Vainqueur');
  await expect(bandeau).toContainText(`Dossard ${alice.dossard}`);
  await expect(bandeau).toContainText(alice.pseudo);
  const ligne = lire(page, 'suivi-abandon-' + bruno.dossard);
  await expect(ligne).toContainText(bruno.pseudo);
  await expect(ligne).toContainText('Boucle 2');
  await expect(ligne).toContainText('Hors délai');
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('2');
  await expect(section(page, SECTION_EN_COURSE)).toHaveCount(0);
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveCount(0);
  await expect(lire(page, 'suivi-compte-a-rebours')).toHaveCount(0);
  await expect(lire(page, 'suivi-message-terminee')).toBeVisible();
  await expect(lire(page, 'suivi-annonce-fin')).toHaveText(`Course terminée. Vainqueur : ${alice.pseudo}.`);
  await verifierSansRechargement(page);

  const avant = requetes.nombre();
  await laisserPasser(page, 7000);
  expect(requetes.nombre()).toBe(avant);
});

test('CA19 - vainqueurs partagés : bandeau « Vainqueurs » par dossard, aucun abandon, polling arrêté', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright, 2);
  const [alice, bruno] = course.coureurs;
  const requetes = compterRequetesSuivi(page);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');

  await avancerHorloge(playwright, 'PT64M');
  const bandeau = lire(page, 'suivi-vainqueurs');
  await expect(bandeau.getByRole('heading', { level: 2 })).toHaveText('Vainqueurs', CINQ_SECONDES);
  await expect(lire(page, 'suivi-vainqueur-' + alice.dossard)).toContainText(alice.pseudo);
  await expect(lire(page, 'suivi-vainqueur-' + bruno.dossard)).toContainText(bruno.pseudo);
  const lignes = bandeau.getByRole('listitem');
  await expect(lignes).toHaveCount(2);
  await expect(lignes.nth(0)).toHaveAttribute('data-testid', 'suivi-vainqueur-' + alice.dossard);
  await expect(lignes.nth(1)).toHaveAttribute('data-testid', 'suivi-vainqueur-' + bruno.dossard);
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('0');
  await expect(lire(page, 'suivi-message-aucun-abandon')).toHaveText('Aucun abandon.');

  const avant = requetes.nombre();
  await laisserPasser(page, 7000);
  expect(requetes.nombre()).toBe(avant);
});

test('CA23 - ouverture d\'une course déjà terminée : listes complètes au premier affichage, une seule requête, aucune annonce', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright, 3);
  const [alice, bruno, karim] = course.coureurs;
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  await avancerHorloge(playwright, 'PT64M');
  // Bruno et Karim sont tombés ou vont tomber : on laisse Alice seule terminer la Boucle 2.
  await scannerParApi(playwright, course, alice);
  await avancerHorloge(playwright, 'PT63M');
  // La page ne doit s'ouvrir qu'une fois la fin de course constatée par le planificateur (sinon l'annonce est légitime).
  await expect.poll(() => lireStatutCourseEnBase(course.id), { timeout: 8000 }).toBe('TERMINEE');

  // Contexte neuf : aucune session, aucun état partagé avec les écrans précédents.
  const requetes = compterRequetesSuivi(page);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée', CINQ_SECONDES);
  await expect(lire(page, 'suivi-vainqueur-' + alice.dossard)).toContainText(alice.pseudo);
  await expect(lire(page, 'suivi-abandon-' + bruno.dossard)).toContainText('Boucle 2');
  await expect(lire(page, 'suivi-abandon-' + karim.dossard)).toContainText('Boucle 1');
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('2');
  await expect(lire(page, 'suivi-annonce-fin')).toHaveText('');
  await laisserPasser(page, 7000);
  expect(requetes.nombre()).toBe(1);
  await expect(lire(page, 'suivi-annonce-fin')).toHaveText('');
});

test('CA24 - annonce de fin dans une région status : « Vainqueurs : … » à la fin ; rien pour un abandon manuel ; aucune liste en aria-live', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright, 3);
  const [alice, bruno, karim] = course.coureurs;
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('3');
  const annonce = lire(page, 'suivi-annonce-fin');
  await expect(annonce).toHaveAttribute('role', 'status');
  await expect(annonce).toHaveText('');

  // Un abandon manuel ne produit aucune annonce (la réintégration, sans effet sur l'annonce, est couverte par CA16).
  await abandonManuelParApi(playwright, course, karim, 'MANUEL');
  await expect(lire(page, 'suivi-abandon-' + karim.dossard)).toBeVisible(CINQ_SECONDES);
  await expect(annonce).toHaveText('');
  await expect(page.locator('main [aria-live]')).toHaveCount(0);

  await avancerHorloge(playwright, 'PT64M');
  await expect(annonce).toHaveText(`Course terminée. Vainqueurs : ${alice.pseudo}, ${bruno.pseudo}.`, CINQ_SECONDES);
  await expect(lire(page, 'suivi-vainqueurs').getByRole('heading', { level: 2 })).toHaveText('Vainqueurs');
  await expect(page.locator('main [aria-live]')).toHaveCount(0);
});
