import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { MOTIF_API_SUIVI, compterRequetesSuivi, lire, poserMarqueur, urlSuivi, verifierSansRechargement } from './aide-suivi';
import { CINQ_SECONDES, abandonManuelParApi, laisserPasser, reintegrerCoureurParApi } from './aide-suivi-coureurs';
import { demarrer } from './aide-suivi';
import {
  ESTIME_PHOTO,
  angle,
  courseDeQuatre,
  dossardsDuTableau,
  espionnerImages,
  marquerNoeuds,
  marquesDe,
  poserPhotoCoureur,
  polaroidDe,
  retirerPhotoCoureur,
} from './aide-polaroid';

/*
 * Incrément 10.2, tableau de polaroïds du suivi public sur la stack réelle : CA13, CA14, CA15, CA19 (abandon et
 * rechargement), CA25 et CA26. L'ancien CA17 (404 et 429, aucune nouvelle tentative) est remplacé par les CA19 à CA21 du
 * correctif d'audit du jalon 10 : suivi-polaroids-nouvelles-tentatives.spec.ts. Les CA sur réponses simulées : suivi-polaroids-simules.spec.ts ; CA18 (horloge) :
 * suivi-polaroids.horloge.spec.ts. Chaque test ouvre la page dans un contexte neuf, sans session.
 */

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const naturalWidth = (img: import('@playwright/test').Locator) => img.evaluate((el) => (el as HTMLImageElement).naturalWidth);

test('CA13 - un visiteur sans session voit le tableau : 4 polaroïds par dossard, photos de Karim et Sophie, silhouettes d\'Alice et Tom, épingles, sans jeton QR', async ({ page, playwright }) => {
  const { course, alice, karim, sophie, tom } = await courseDeQuatre(playwright);
  await page.goto(urlSuivi(course.id));

  await expect(lire(page, 'suivi-tableau')).toBeVisible();
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('4');
  await expect.poll(() => dossardsDuTableau(page)).toEqual([alice.dossard, karim.dossard, sophie.dossard, tom.dossard]);
  for (const c of [alice, karim, sophie, tom]) {
    const li = lire(page, `suivi-coureur-${c.dossard}`);
    await expect(li).toContainText(`Dossard ${c.dossard}`);
    await expect(li).toContainText(c.pseudo);
    await expect(li.getByTestId('suivi-polaroid-epingle')).toHaveCount(1);
  }

  for (const c of [karim, sophie]) {
    const photo = lire(page, `suivi-coureur-${c.dossard}`).getByTestId('suivi-polaroid-photo');
    await expect(photo).toHaveAttribute('alt', `Photo de ${c.pseudo}`);
    await expect(photo).toHaveAttribute('src', ESTIME_PHOTO);
    await expect.poll(() => naturalWidth(photo)).toBeGreaterThan(0);
    await expect(lire(page, `suivi-coureur-${c.dossard}`).getByTestId('suivi-polaroid-silhouette')).toHaveCount(0);
  }
  for (const c of [alice, tom]) {
    const li = lire(page, `suivi-coureur-${c.dossard}`);
    await expect(li.getByTestId('suivi-polaroid-silhouette')).toHaveCount(1);
    await expect(li.getByTestId('suivi-polaroid-photo')).toHaveCount(0);
    await expect(li.locator('img[src*="/api/photos"]')).toHaveCount(0);
  }

  // Aucun jeton QR (lu en base) ni dans le texte ni dans les attributs.
  const dom = await page.content();
  for (const c of course.coureurs) expect(dom).not.toContain(c.jeton);
  expect(await page.locator('body').innerText()).not.toContain(karim.jeton);
});

test('CA14 - abandon manuel puis réintégration : le polaroïd disparaît et revient à sa place sans rechargement, les autres ne sont pas recréés', async ({ page, playwright }) => {
  const { course, alice, karim, sophie, tom } = await courseDeQuatre(playwright);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('4');
  await expect(lire(page, 'suivi-coureur-3').getByTestId('suivi-polaroid-photo')).toBeVisible();
  await poserMarqueur(page);
  await marquerNoeuds(page);
  const avant = await Promise.all([alice, sophie, tom].map((c) => marquesDe(page, c.dossard)));

  await abandonManuelParApi(playwright, course, karim, 'MANUEL');
  await expect(lire(page, 'suivi-coureur-2')).toHaveCount(0, CINQ_SECONDES);
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('3');
  const abandon = lire(page, 'suivi-abandon-2');
  await expect(abandon).toBeVisible(CINQ_SECONDES);
  await expect(abandon).toContainText(karim.pseudo);
  await expect(abandon.locator('img')).toHaveCount(0);
  expect(await dossardsDuTableau(page)).toEqual([1, 3, 4]);
  expect(await Promise.all([alice, sophie, tom].map((c) => marquesDe(page, c.dossard)))).toEqual(avant);

  await reintegrerCoureurParApi(playwright, course, karim);
  await expect(lire(page, 'suivi-coureur-2')).toBeVisible(CINQ_SECONDES);
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('4');
  await expect(lire(page, 'suivi-coureur-2').getByTestId('suivi-polaroid-photo')).toHaveAttribute('alt', `Photo de ${karim.pseudo}`);
  await expect(lire(page, 'suivi-abandon-2')).toHaveCount(0);
  expect(await dossardsDuTableau(page)).toEqual([1, 2, 3, 4]);
  expect(await Promise.all([alice, sophie, tom].map((c) => marquesDe(page, c.dossard)))).toEqual(avant);
  await verifierSansRechargement(page);
});

test('CA15 - 2 requêtes d\'images au chargement, 0 pendant le polling ; une photo remplacée provoque une seule requête et ne change que son img', async ({ page, playwright }) => {
  const { course, alice, karim, sophie, tom } = await courseDeQuatre(playwright);
  const images = espionnerImages(page);
  const suivi = compterRequetesSuivi(page);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('4');
  await expect.poll(() => images.urls().length).toBe(2);
  await expect(lire(page, 'suivi-coureur-2').getByTestId('suivi-polaroid-photo')).toBeVisible();

  await marquerNoeuds(page);
  const noeuds = Object.fromEntries(await Promise.all([alice, karim, sophie, tom].map(async (c) => [c.dossard, await marquesDe(page, c.dossard)])));
  images.vider();
  const avant = suivi.nombre();
  await laisserPasser(page, 10_000);
  await expect.poll(() => suivi.nombre() - avant, { message: 'au moins 3 rafraîchissements' }).toBeGreaterThanOrEqual(3);
  expect(images.urls(), 'aucune requête d\'image pendant le polling').toEqual([]);

  // Karim remplace sa photo : une seule requête, vers la nouvelle URL, seul son img change de src.
  const ancienSrc = await lire(page, 'suivi-coureur-2').getByTestId('suivi-polaroid-photo').getAttribute('src');
  const nouveauJeton = await poserPhotoCoureur(playwright, karim.pseudo, 'photo-2.png', 'image/png');
  const nouvelleUrl = `/api/photos/${nouveauJeton}`;
  expect(nouvelleUrl).not.toBe(ancienSrc);
  await expect(lire(page, 'suivi-coureur-2').getByTestId('suivi-polaroid-photo')).toHaveAttribute('src', nouvelleUrl, CINQ_SECONDES);
  await laisserPasser(page, 7000);
  expect(images.urls()).toEqual([nouvelleUrl]);
  for (const c of [alice, sophie, tom]) expect(await marquesDe(page, c.dossard)).toEqual(noeuds[c.dossard]);
  const marquesKarim = await marquesDe(page, karim.dossard);
  expect(marquesKarim.li).toEqual(noeuds[karim.dossard].li);
  expect(marquesKarim.figure).toEqual(noeuds[karim.dossard].figure);
  expect(marquesKarim.img).toEqual(noeuds[karim.dossard].img);
});

test('CA19 - les inclinaisons de Dossards 1 à 4 valent 3, 1, -1, -3 et ne changent ni après un abandon ni après rechargement', async ({ page, playwright }) => {
  const { course, tom } = await courseDeQuatre(playwright);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('4');
  const attendus = { 1: 3, 2: 1, 3: -1, 4: -3 };
  for (const [d, a] of Object.entries(attendus)) {
    expect(await angle(polaroidDe(page, Number(d))), `dossard ${d}`).toBe(a);
    await expect(polaroidDe(page, Number(d))).toHaveAttribute('data-inclinaison', String(a));
  }

  await abandonManuelParApi(playwright, course, tom, 'MANUEL');
  await expect(lire(page, 'suivi-coureur-4')).toHaveCount(0, CINQ_SECONDES);
  for (const d of [1, 2, 3]) expect(await angle(polaroidDe(page, d))).toBe(attendus[d as 1 | 2 | 3]);
  await page.reload();
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('3');
  for (const d of [1, 2, 3]) expect(await angle(polaroidDe(page, d))).toBe(attendus[d as 1 | 2 | 3]);
});

test('CA25 - Sophie remplace puis retire sa photo, Alice en ajoute une : le suivi suit sans rechargement', async ({ page, playwright }) => {
  const { course, alice, sophie } = await courseDeQuatre(playwright);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-coureur-3').getByTestId('suivi-polaroid-photo')).toBeVisible();
  await expect(lire(page, 'suivi-coureur-1').getByTestId('suivi-polaroid-silhouette')).toBeVisible();
  await poserMarqueur(page);
  const imgSophie = lire(page, 'suivi-coureur-3').getByTestId('suivi-polaroid-photo');
  const ancienSrc = await imgSophie.getAttribute('src');

  const jeton = await poserPhotoCoureur(playwright, sophie.pseudo, 'photo-2.png', 'image/png');
  await expect(imgSophie).toHaveAttribute('src', `/api/photos/${jeton}`, CINQ_SECONDES);
  expect(`/api/photos/${jeton}`).not.toBe(ancienSrc);

  await retirerPhotoCoureur(playwright, sophie.pseudo);
  await expect(lire(page, 'suivi-coureur-3').getByTestId('suivi-polaroid-silhouette')).toBeVisible(CINQ_SECONDES);
  await expect(lire(page, 'suivi-coureur-3').getByTestId('suivi-polaroid-photo')).toHaveCount(0);

  const jetonAlice = await poserPhotoCoureur(playwright, alice.pseudo, 'photo.jpg', 'image/jpeg');
  const imgAlice = lire(page, 'suivi-coureur-1').getByTestId('suivi-polaroid-photo');
  await expect(imgAlice).toHaveAttribute('src', `/api/photos/${jetonAlice}`, CINQ_SECONDES);
  await expect(imgAlice).toHaveAttribute('alt', `Photo de ${alice.pseudo}`);
  await expect(lire(page, 'suivi-coureur-1').getByTestId('suivi-polaroid-silhouette')).toHaveCount(0);
  await verifierSansRechargement(page);
});

test('CA26 - avant le démarrage ni tableau ni pseudo ; au démarrage les 4 polaroïds apparaissent ; une coupure réseau fige le tableau puis un abandon survenu pendant la coupure le retire', async ({ page, playwright }) => {
  const { course, alice, karim, sophie, tom } = await courseDeQuatre(playwright, { demarree: false });
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-message-non-demarree')).toBeVisible();
  await expect(lire(page, 'suivi-tableau')).toHaveCount(0);
  await expect(lire(page, 'suivi-polaroid').first()).toHaveCount(0);
  const texte = await page.locator('body').innerText();
  for (const c of course.coureurs) expect(texte).not.toContain(c.pseudo);
  await poserMarqueur(page);

  await demarrer(playwright, course.id);
  await expect(lire(page, 'suivi-tableau')).toBeVisible(CINQ_SECONDES);
  await expect(lire(page, 'suivi-polaroid')).toHaveCount(4);
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('4');
  for (const c of [alice, karim, sophie, tom]) await expect(lire(page, `suivi-coureur-${c.dossard}`)).toContainText(c.pseudo);

  // Coupure de 8 s : tableau inchangé et avertissement ; abandon pendant la coupure.
  await page.route(MOTIF_API_SUIVI, (route) => route.abort());
  await expect(lire(page, 'suivi-avertissement-actualisation')).toHaveText('Actualisation impossible, nouvelle tentative…', { timeout: 8000 });
  await abandonManuelParApi(playwright, course, tom, 'MANUEL');
  await laisserPasser(page, 8000);
  await expect(lire(page, 'suivi-polaroid')).toHaveCount(4);
  await expect(lire(page, 'suivi-coureur-4')).toBeVisible();

  await page.unroute(MOTIF_API_SUIVI);
  await expect(lire(page, 'suivi-coureur-4')).toHaveCount(0, { timeout: 4000 });
  await expect(lire(page, 'suivi-polaroid')).toHaveCount(3);
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('3');
  await expect(lire(page, 'suivi-abandon-4')).toBeVisible();
  await expect(lire(page, 'suivi-avertissement-actualisation')).toHaveCount(0, { timeout: 4500 });
  await verifierSansRechargement(page);
});
