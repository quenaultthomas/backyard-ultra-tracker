import { expect, test } from '@playwright/test';
import { compterRequetesSuivi, lire, urlSuivi } from './aide-suivi';
import { laisserPasser } from './aide-suivi-coureurs';
import { defilementHorizontal, elementPleinEcran, entrerEnProjection, tailleJeton, taillePolice, CINQ_SECONDES } from './aide-suivi-statistiques';
import {
  ID_SIMULE,
  angle,
  contrastesLegendes,
  coureursSimules,
  dossardsDuTableau,
  espionnerImages,
  jetonSimule,
  marquerNoeuds,
  marquesDe,
  polaroidDe,
  servirImages,
  servirSuivi,
  suiviSimule,
  urlSimulee,
} from './aide-polaroid';

/*
 * Incrément 10.2, tableau de polaroïds sur réponses de suivi simulées (page.route) : la création de 100 comptes est
 * plafonnée (SEC-15). CA16, CA19 (angles de 7 dossards), CA20 à CA24. Aucune donnée : pas besoin de la base.
 */

const racine = urlSuivi(ID_SIMULE);
const PSEUDO_30 = 'pseudo-de-trente-caracteres-xx';
const PSEUDO_HTML = '<b>x</b>';

test('CA16 - 100 coureurs dont 60 avec photo : 100 polaroïds, au plus 60 images, 0 en polling, palier compact, tous visibles à 1920 x 1080', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  const coureurs = coureursSimules(100, (d) => d <= 60);
  await servirSuivi(page, () => suiviSimule(coureurs));
  await servirImages(page, 40 * 1024);
  const images = espionnerImages(page);
  const suivi = compterRequetesSuivi(page);

  await page.goto(racine);
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('100');
  await expect(lire(page, 'suivi-polaroid')).toHaveCount(100);
  await expect(lire(page, 'suivi-tableau')).toHaveAttribute('data-palier', 'compact');
  await expect(lire(page, 'suivi-coureur-100').getByTestId('suivi-polaroid-dossard')).toHaveText('n° 100');
  await laisserPasser(page, 10_000);
  const chargement = images.urls().length;
  expect(chargement).toBeLessThanOrEqual(60);
  expect(chargement).toBeGreaterThan(0);

  // 0 requête pendant le polling, qui continue.
  images.vider();
  const avant = suivi.nombre();
  await laisserPasser(page, 10_000);
  expect(suivi.nombre() - avant, 'le polling continue pendant la seconde fenêtre').toBeGreaterThanOrEqual(2);
  expect(suivi.nombre(), 'au moins 3 appels du suivi').toBeGreaterThanOrEqual(3);
  expect(images.urls()).toEqual([]);

  // Le tableau tient dans la fenêtre : une fois amené en haut de page, les 100 polaroïds y sont, sans défilement du tableau.
  await lire(page, 'suivi-tableau').evaluate((el) => el.scrollIntoView({ block: 'start' }));
  const mesure = await page.evaluate(() => {
    const tableau = document.querySelector('[data-testid="suivi-tableau"]') as HTMLElement;
    const rects = [...tableau.querySelectorAll('figure')].map((f) => f.getBoundingClientRect());
    return {
      basMax: Math.max(...rects.map((r) => r.bottom)),
      droiteMax: Math.max(...rects.map((r) => r.right)),
      hauteurFenetre: window.innerHeight,
      largeurFenetre: window.innerWidth,
      defilementDuTableau: tableau.scrollHeight - tableau.clientHeight,
      hauteurTableau: tableau.getBoundingClientRect().height,
    };
  });
  expect(mesure.basMax, 'bas du dernier polaroïd').toBeLessThanOrEqual(mesure.hauteurFenetre);
  expect(mesure.droiteMax).toBeLessThanOrEqual(mesure.largeurFenetre);
  expect(mesure.defilementDuTableau).toBeLessThanOrEqual(1);
  expect(mesure.hauteurTableau).toBeLessThanOrEqual(mesure.hauteurFenetre);
});

test('CA19 - les polaroïds des dossards 1, 2, 3, 4, 9, 10 et 17 sont inclinés de 3, 1, -1, -3, -4, 3 et -2 degrés', async ({ page }) => {
  const dossards = [1, 2, 3, 4, 9, 10, 17];
  const attendus = [3, 1, -1, -3, -4, 3, -2];
  await servirSuivi(page, () => suiviSimule(dossards.map((d) => ({ dossard: d, pseudo: `c-${d}`, photoUrl: null }))));
  await page.goto(racine);
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('7');
  for (const [i, d] of dossards.entries()) {
    const a = await angle(polaroidDe(page, d));
    expect(a, `dossard ${d}`).toBe(attendus[i]);
    expect(a).toBeGreaterThanOrEqual(-4);
    expect(a).toBeLessThanOrEqual(4);
  }
});

const profils = [
  { nom: '1280 px', largeur: 1280, police: null },
  { nom: '375 px', largeur: 375, police: null },
  { nom: '320 px', largeur: 320, police: null },
  { nom: '320 px texte 200 %', largeur: 320, police: '32px' },
];

test('CA20 - pseudos hostiles et 40 inscrits à 1280, 375, 320 px et 320 px à 200 % : aucun défilement horizontal, texte littéral, pseudo dans son polaroïd, alt, focus, aria-live, contraste', async ({ page }) => {
  const coureurs = coureursSimules(40, (d) => d % 3 === 0);
  coureurs[0].pseudo = PSEUDO_30;
  coureurs[1].pseudo = PSEUDO_HTML;
  await servirSuivi(page, () => suiviSimule(coureurs));
  await servirImages(page, 0);

  for (const p of profils) {
    await page.setViewportSize({ width: p.largeur, height: 800 });
    await page.goto(racine);
    if (p.police) await page.addStyleTag({ content: `html{font-size:${p.police}}` });
    await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('40');
    await expect(lire(page, 'suivi-polaroid')).toHaveCount(40);
    expect(await defilementHorizontal(page), `défilement horizontal à ${p.nom}`).toBeLessThanOrEqual(0);

    await expect(lire(page, 'suivi-coureur-1')).toContainText(PSEUDO_30);
    await expect(lire(page, 'suivi-coureur-2').getByTestId('suivi-polaroid-pseudo')).toHaveText(PSEUDO_HTML);
    await expect(page.locator('main b')).toHaveCount(0);

    // Le pseudo (coupé par son propre cadre s'il est trop long, RG9) reste dans la légende et dans le polaroïd.
    const debords = await page.evaluate(() => {
      const mesures: number[] = [];
      for (const figure of document.querySelectorAll<HTMLElement>('[data-testid="suivi-tableau"] figure')) {
        mesures.push(figure.scrollWidth - figure.clientWidth);
        const pseudo = figure.querySelector<HTMLElement>('[data-testid="suivi-polaroid-pseudo"]')!;
        mesures.push(pseudo.offsetLeft + pseudo.offsetWidth - figure.clientWidth);
        mesures.push(-pseudo.offsetLeft);
      }
      return mesures;
    });
    for (const d of debords) expect(d, `débordement à ${p.nom}`).toBeLessThanOrEqual(1);
  }

  // Textes alternatifs, silhouettes décoratives.
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto(racine);
  await expect(lire(page, 'suivi-polaroid')).toHaveCount(40);
  for (const c of coureurs) {
    const li = lire(page, `suivi-coureur-${c.dossard}`);
    if (c.photoUrl) {
      await expect(li.getByTestId('suivi-polaroid-photo')).toHaveAttribute('alt', `Photo de ${c.pseudo}`);
    } else {
      const silhouette = li.getByTestId('suivi-polaroid-silhouette');
      await expect(silhouette).toHaveAttribute('alt', '');
      await expect(silhouette).toHaveAttribute('aria-hidden', 'true');
    }
    await expect(li.getByTestId('suivi-polaroid-epingle')).toHaveAttribute('aria-hidden', 'true');
  }

  // Aucun polaroïd focusable, aucun aria-live sur le tableau.
  await expect(page.locator('[data-testid="suivi-tableau"] :is([tabindex], a, button, input)')).toHaveCount(0);
  for (let i = 0; i < 12; i++) {
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => !!document.activeElement?.closest('[data-testid="suivi-tableau"]')), `Tab n°${i + 1}`).toBe(false);
  }
  expect(await page.locator('[data-testid="suivi-tableau"]').evaluate((el) => !!el.closest('[aria-live]') || el.querySelector('[aria-live]') !== null)).toBe(false);

  // Contraste AA de la légende sur le cadre.
  const ratios = await contrastesLegendes(page);
  expect(ratios.length).toBe(80);
  for (const r of ratios) expect(r).toBeGreaterThanOrEqual(4.5);
});

test('CA21 - prefers-reduced-motion : ni transition ni animation sur le tableau et les polaroïds, l\'inclinaison reste appliquée (et sans l\'émulation non plus)', async ({ page }) => {
  await servirSuivi(page, () => suiviSimule(coureursSimules(6, (d) => d % 2 === 0)));
  await servirImages(page, 0);
  const mesurer = () =>
    page.evaluate(() => {
      const nulle = (valeur: string) => valeur.split(',').every((v) => parseFloat(v) === 0);
      const cibles = [
        document.querySelector('[data-testid="suivi-tableau"]')!,
        ...document.querySelectorAll('[data-testid="suivi-tableau"] li, [data-testid="suivi-tableau"] figure, [data-testid="suivi-tableau"] img, [data-testid="suivi-polaroid-epingle"]'),
      ];
      return {
        transitions: cibles.filter((e) => !nulle(getComputedStyle(e).transitionDuration)).length,
        animations: cibles.filter((e) => getComputedStyle(e).animationName !== 'none' && !nulle(getComputedStyle(e).animationDuration)).length,
        animationsActives: document.getAnimations().length,
      };
    });
  for (const mouvement of ['reduce', 'no-preference'] as const) {
    await page.emulateMedia({ reducedMotion: mouvement });
    await page.goto(racine);
    await expect(lire(page, 'suivi-polaroid')).toHaveCount(6);
    expect(await mesurer(), mouvement).toEqual({ transitions: 0, animations: 0, animationsActives: 0 });
    expect(await angle(polaroidDe(page, 1)), mouvement).toBe(3);
  }
});

test('CA22 - le plein écran agrandit les polaroïds (légende >= taille de projection), sans les recréer, et le polling continue', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await servirSuivi(page, () => suiviSimule(coureursSimules(12)));
  const suivi = compterRequetesSuivi(page);
  await page.goto(racine);
  await expect(lire(page, 'suivi-polaroid')).toHaveCount(12);
  await marquerNoeuds(page);
  const marques = await marquesDe(page, 5);
  const largeur = () => polaroidDe(page, 5).evaluate((el) => (el as HTMLElement).offsetWidth);
  const horsProjection = await largeur();

  await entrerEnProjection(page);
  await expect(lire(page, 'suivi-racine')).toHaveClass(/suivi--projection/);
  const enProjection = await largeur();
  expect(enProjection).toBeGreaterThan(horsProjection);
  const projection = await tailleJeton(page, '--taille-projection-texte');
  expect(await taillePolice(lire(page, 'suivi-coureur-5').getByTestId('suivi-polaroid-pseudo'))).toBeGreaterThanOrEqual(projection);
  expect(await taillePolice(lire(page, 'suivi-coureur-5').getByTestId('suivi-polaroid-dossard'))).toBeGreaterThanOrEqual(projection);
  expect(await marquesDe(page, 5)).toEqual(marques);

  const avant = suivi.nombre();
  await laisserPasser(page, 7000);
  expect(suivi.nombre() - avant, 'le polling continue en projection').toBeGreaterThanOrEqual(2);

  await page.evaluate(() => document.exitFullscreen());
  await expect.poll(() => elementPleinEcran(page)).toBeNull();
  await expect(lire(page, 'suivi-racine')).not.toHaveClass(/suivi--projection/);
  expect(await largeur()).toBe(horsProjection);
  expect(await marquesDe(page, 5)).toEqual(marques);
});

test('CA23 - paliers de taille à 12, 13, 30, 31, 60 et 61 coureurs ; passer de 13 à 12 en polling ne recrée aucun polaroïd', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  let effectif = 12;
  await servirSuivi(page, () => suiviSimule(coureursSimules(effectif)));
  const cas: [number, string][] = [[12, 'grand'], [13, 'moyen'], [30, 'moyen'], [31, 'petit'], [60, 'petit'], [61, 'compact']];
  for (const [n, palier] of cas) {
    effectif = n;
    await page.goto(racine);
    await expect(lire(page, 'suivi-nombre-en-course')).toHaveText(String(n));
    await expect(lire(page, 'suivi-polaroid')).toHaveCount(n);
    await expect(lire(page, 'suivi-tableau'), `${n} coureurs`).toHaveAttribute('data-palier', palier);
    const attendue = await tailleJeton(page, `--polaroid-largeur-${palier}`);
    expect(await polaroidDe(page, 1).evaluate((el) => (el as HTMLElement).offsetWidth), `largeur à ${n}`).toBeCloseTo(attendue, 0);
  }

  effectif = 13;
  await page.goto(racine);
  await expect(lire(page, 'suivi-tableau')).toHaveAttribute('data-palier', 'moyen');
  await marquerNoeuds(page);
  const avant = await Promise.all(Array.from({ length: 12 }, (_, i) => marquesDe(page, i + 1)));
  effectif = 12;
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('12', CINQ_SECONDES);
  await expect(lire(page, 'suivi-tableau')).toHaveAttribute('data-palier', 'grand');
  await expect(lire(page, 'suivi-coureur-13')).toHaveCount(0);
  expect(await dossardsDuTableau(page)).toEqual(Array.from({ length: 12 }, (_, i) => i + 1));
  expect(await Promise.all(Array.from({ length: 12 }, (_, i) => marquesDe(page, i + 1)))).toEqual(avant);
});

test('CA24 - un photoUrl absolu ou javascript: n\'émet aucune requête et affiche la silhouette', async ({ page }) => {
  const coureurs = coureursSimules(3);
  coureurs[0].photoUrl = 'https://exemple.test/x.jpg';
  coureurs[1].photoUrl = 'javascript:alert(1)';
  coureurs[2].photoUrl = urlSimulee(3);
  const externes: string[] = [];
  const dialogues: string[] = [];
  page.on('dialog', async (d) => {
    dialogues.push(d.message());
    await d.dismiss();
  });
  page.on('request', (r) => {
    if (/exemple\.test|^javascript:/.test(r.url())) externes.push(r.url());
  });
  await page.route('**://exemple.test/**', (route) => route.abort());
  await servirSuivi(page, () => suiviSimule(coureurs));
  await servirImages(page, 0);
  await page.goto(racine);
  await expect(lire(page, 'suivi-polaroid')).toHaveCount(3);
  for (const d of [1, 2]) {
    await expect(lire(page, `suivi-coureur-${d}`).getByTestId('suivi-polaroid-silhouette')).toBeVisible();
    await expect(lire(page, `suivi-coureur-${d}`).getByTestId('suivi-polaroid-photo')).toHaveCount(0);
  }
  await expect(lire(page, 'suivi-coureur-3').getByTestId('suivi-polaroid-photo')).toHaveAttribute('src', urlSimulee(3));
  await laisserPasser(page, 4000);
  expect(externes).toEqual([]);
  expect(dialogues).toEqual([]);
  expect(await page.locator('img').evaluateAll((imgs) => imgs.map((i) => i.getAttribute('src') ?? '').filter((s) => /^(https?:|javascript:)/.test(s)))).toEqual([]);
  expect(jetonSimule(3)).toMatch(/^[0-9a-f]{32}$/);
});

const PALIERS_PROJECTION: [number, string][] = [[12, 'grand'], [13, 'moyen'], [30, 'moyen'], [31, 'petit'], [60, 'petit'], [61, 'compact'], [100, 'compact']];

test('CA22 - en projection, aux 4 paliers : pseudo >= 24 px, dossard >= 16 px, texte complet dans le DOM, aucun défilement interne ; hors projection la légende garde les tailles de la charte', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  let effectif = 12;
  await servirSuivi(page, () => suiviSimule(coureursSimules(effectif).map((c) => (c.dossard === 1 ? { ...c, pseudo: 'pseudo-de-trente-caracteres-xx' } : c))));
  for (const n of [12, 30, 60, 100]) {
    effectif = n;
    await page.goto(racine);
    await expect(lire(page, 'suivi-polaroid')).toHaveCount(n);
    const horsProjection = await taillePolice(lire(page, 'suivi-coureur-1').getByTestId('suivi-polaroid-pseudo'));
    expect(horsProjection, `hors projection à ${n}`).toBeLessThan(24);
    await entrerEnProjection(page);
    for (const d of [1, n]) {
      const li = lire(page, `suivi-coureur-${d}`);
      expect(await taillePolice(li.getByTestId('suivi-polaroid-pseudo')), `pseudo à ${n}`).toBeGreaterThanOrEqual(24);
      expect(await taillePolice(li.getByTestId('suivi-polaroid-dossard')), `dossard à ${n}`).toBeGreaterThanOrEqual(16);
    }
    await expect(lire(page, 'suivi-coureur-1').getByTestId('suivi-polaroid-pseudo')).toHaveText('pseudo-de-trente-caracteres-xx');
    const defilement = await lire(page, 'suivi-tableau').evaluate((el) => ({ exces: el.scrollHeight - el.clientHeight, overflow: getComputedStyle(el).overflowY, maxH: getComputedStyle(el).maxHeight }));
    expect(defilement.exces).toBeLessThanOrEqual(1);
    expect(['auto', 'scroll']).not.toContain(defilement.overflow);
    expect(defilement.maxH).toBe('none');
    expect(await defilementHorizontal(page)).toBeLessThanOrEqual(0);
    await page.evaluate(() => document.exitFullscreen());
    await expect.poll(() => elementPleinEcran(page)).toBeNull();
  }
});

test('CA23 - mêmes seuils de palier en projection (12, 13, 30, 31, 60, 61, 100), largeur égale au jeton de projection', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  let effectif = 12;
  await servirSuivi(page, () => suiviSimule(coureursSimules(effectif)));
  for (const [n, palier] of PALIERS_PROJECTION) {
    effectif = n;
    await page.goto(racine);
    await expect(lire(page, 'suivi-polaroid')).toHaveCount(n);
    await entrerEnProjection(page);
    await expect(lire(page, 'suivi-tableau'), `${n} coureurs en projection`).toHaveAttribute('data-palier', palier);
    const attendue = await tailleJeton(page, `--polaroid-largeur-projection-${palier}`);
    expect(await polaroidDe(page, 1).evaluate((el) => (el as HTMLElement).offsetWidth), `largeur à ${n}`).toBeCloseTo(attendue, 0);
    await page.evaluate(() => document.exitFullscreen());
    await expect.poll(() => elementPleinEcran(page)).toBeNull();
  }
});
