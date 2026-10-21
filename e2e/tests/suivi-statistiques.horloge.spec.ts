import { expect, test, avancerHorloge } from './aide-horloge';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { compterRequetesSuivi, lire, poserMarqueur, urlSuivi, verifierSansRechargement } from './aide-suivi';
import { laisserPasser, reintegrerCoureurParApi, scannerParApi } from './aide-suivi-coureurs';
import { CINQ_SECONDES, FORMAT_ALLURE, allureDeLApi, courseY, formaterAllureAttendue } from './aide-suivi-statistiques';
import { lireStatutCourseEnBase } from './aide-scan';

/*
 * Incrément 6.3 avec horloge applicative pilotée (CA18 à CA22). Planificateur 1 s + polling 3 s : 5 s de marge.
 */

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA18 - la bascule de Boucle met à jour boucles, distance, dénivelé et allure sans rechargement, sans aria-live', async ({ page, playwright }) => {
  const course = await courseY(playwright);
  const [alice, bruno] = course.coureurs;
  await avancerHorloge(playwright, 'PT30M');
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('0');
  await expect(lire(page, 'suivi-allure')).toHaveText(FORMAT_ALLURE);
  await poserMarqueur(page);

  await avancerHorloge(playwright, 'PT31M');
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('1', CINQ_SECONDES);
  await expect(lire(page, 'suivi-distance-cumulee')).toHaveText('6,70 km');
  await expect(lire(page, 'suivi-denivele-cumule')).toHaveText('35 m D+');
  await expect(lire(page, 'suivi-allure')).toHaveText(FORMAT_ALLURE);
  const allureApi = await allureDeLApi(playwright, course.id);
  expect(allureApi).not.toBeNull();
  await expect(lire(page, 'suivi-allure')).toHaveText(formaterAllureAttendue(allureApi!));
  await expect(lire(page, 'suivi-annonce-fin')).toHaveText('');
  await expect(lire(page, 'suivi-statistiques').locator('[aria-live]')).toHaveCount(0);
  await expect(lire(page, 'suivi-statistiques')).not.toHaveAttribute('aria-live', /.*/);

  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  await avancerHorloge(playwright, 'PT60M');
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('2', CINQ_SECONDES);
  await expect(lire(page, 'suivi-distance-cumulee')).toHaveText('13,40 km');
  await expect(lire(page, 'suivi-denivele-cumule')).toHaveText('70 m D+');
  await expect(lire(page, 'suivi-annonce-fin')).toHaveText('');
  await verifierSansRechargement(page);
});

test('CA19 - course terminée : statistiques finales, « Durée de la course » 02:00:00, ni compte à rebours ni durée écoulée, polling arrêté', async ({ page, playwright }) => {
  const course = await courseY(playwright);
  const [alice, bruno] = course.coureurs;
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');
  await poserMarqueur(page);

  await avancerHorloge(playwright, 'PT61M');
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('1', CINQ_SECONDES);
  await scannerParApi(playwright, course, alice);
  await avancerHorloge(playwright, 'PT60M');

  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée', CINQ_SECONDES);
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('2');
  await expect(lire(page, 'suivi-distance-cumulee')).toHaveText('13,40 km');
  await expect(lire(page, 'suivi-denivele-cumule')).toHaveText('70 m D+');
  await expect(page.getByText('Durée de la course', { exact: true })).toBeVisible();
  await expect(lire(page, 'suivi-duree-finale')).toHaveText('02:00:00');
  await expect(lire(page, 'suivi-compte-a-rebours')).toHaveCount(0);
  await expect(lire(page, 'suivi-duree-ecoulee')).toHaveCount(0);
  await verifierSansRechargement(page);

  const requetes = compterRequetesSuivi(page);
  await laisserPasser(page, 7000);
  expect(requetes.nombre()).toBe(0);

  // Ouverture directe dans un contexte neuf : mêmes valeurs dès le premier affichage, une seule requête.
  const neuf = await page.context().browser()!.newContext();
  try {
    const p = await neuf.newPage();
    const requetesNeuf = compterRequetesSuivi(p);
    await p.goto(urlSuivi(course.id));
    await expect(lire(p, 'suivi-duree-finale')).toHaveText('02:00:00', CINQ_SECONDES);
    await expect(lire(p, 'suivi-boucles-terminees')).toHaveText('2');
    await expect(lire(p, 'suivi-distance-cumulee')).toHaveText('13,40 km');
    await expect(lire(p, 'suivi-denivele-cumule')).toHaveText('70 m D+');
    await laisserPasser(p, 7000);
    expect(requetesNeuf.nombre()).toBe(1);
  } finally {
    await neuf.close();
  }
});

test('CA20 - vainqueurs partagés sans passage sur la Boucle 2 : 1 boucle, 01:00:00 ; sans aucun scan : 0 boucle, 00:00:00, allure indisponible', async ({ page, playwright }) => {
  const course = await courseY(playwright);
  for (const c of course.coureurs) await scannerParApi(playwright, course, c);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');

  await avancerHorloge(playwright, 'PT121M');
  await expect(lire(page, 'suivi-vainqueurs').getByRole('heading', { level: 2 })).toHaveText('Vainqueurs', CINQ_SECONDES);
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('1');
  await expect(lire(page, 'suivi-distance-cumulee')).toHaveText('6,70 km');
  await expect(lire(page, 'suivi-duree-finale')).toHaveText('01:00:00');

  // Y' : aucun scan, la même horloge a déjà avancé : repartir d'une horloge connue.
  const sansScan = await courseY(playwright);
  await page.goto(urlSuivi(sansScan.id));
  await expect(lire(page, 'suivi-statut')).toBeVisible();
  await avancerHorloge(playwright, 'PT61M');
  await expect(lire(page, 'suivi-vainqueurs').getByRole('heading', { level: 2 })).toHaveText('Vainqueurs', CINQ_SECONDES);
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('0');
  await expect(lire(page, 'suivi-distance-cumulee')).toHaveText('0,00 km');
  await expect(lire(page, 'suivi-duree-finale')).toHaveText('00:00:00');
  await expect(lire(page, 'suivi-allure')).toHaveText('Non disponible');
});

test('CA21 - course limitée à 2 boucles : « Terminée », deux vainqueurs, 2 boucles, 02:00:00', async ({ page, playwright }) => {
  const course = await courseY(playwright, { nombreMaxBoucles: 2 });
  const [alice, bruno] = course.coureurs;
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');

  await avancerHorloge(playwright, 'PT61M');
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('1', CINQ_SECONDES);
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  await avancerHorloge(playwright, 'PT60M');

  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée', CINQ_SECONDES);
  await expect(lire(page, 'suivi-vainqueurs').getByRole('heading', { level: 2 })).toHaveText('Vainqueurs');
  await expect(lire(page, 'suivi-vainqueurs').getByRole('listitem')).toHaveCount(2);
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('2');
  await expect(lire(page, 'suivi-duree-finale')).toHaveText('02:00:00');
  await expect.poll(() => lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
});

test('CA22 - la réintégration d\'un coureur ne change pas l\'allure (passages corrigés exclus) et aucun « corrigé » n\'est affiché', async ({ page, playwright }) => {
  const course = await courseY(playwright, { nombreCoureurs: 3 });
  const [alice, bruno, karim] = course.coureurs;
  await avancerHorloge(playwright, 'PT30M');
  await scannerParApi(playwright, course, alice);
  await scannerParApi(playwright, course, bruno);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('3');

  await avancerHorloge(playwright, 'PT31M');
  await expect(lire(page, 'suivi-abandon-' + karim.dossard)).toBeVisible(CINQ_SECONDES);
  await expect(lire(page, 'suivi-allure')).toHaveText(FORMAT_ALLURE);
  const avant = (await lire(page, 'suivi-allure').innerText()).trim();
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('1');

  await reintegrerCoureurParApi(playwright, course, karim);
  await expect(lire(page, 'suivi-coureur-' + karim.dossard)).toBeVisible(CINQ_SECONDES);
  await expect(lire(page, 'suivi-abandon-' + karim.dossard)).toHaveCount(0);
  await laisserPasser(page, 4000);
  await expect(lire(page, 'suivi-allure')).toHaveText(avant);
  await expect(lire(page, 'suivi-boucles-terminees')).toHaveText('1');
  await expect(page.locator('main')).not.toContainText(/corrig/i);
});
