import { expect, test, type Locator, type Page } from '@playwright/test';
import { pseudoUnique } from './aide-connexion';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { champ, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

/** Rapport de contraste WCAG 2.x entre deux couleurs `rgb()`/`rgba()` calculées. */
function contraste(a: string, b: string): number {
  const luminance = (css: string) => {
    const [r, g, bl] = (css.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number).map((v) => {
      const c = v / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [clair, sombre] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (clair + 0.05) / (sombre + 0.05);
}

async function styles(l: Locator, proprietes: string[], pseudo?: string): Promise<Record<string, string>> {
  return l.evaluate(
    (el, [liste, pseudoElement]) => {
      const calcule = getComputedStyle(el, (pseudoElement as string | null) ?? undefined);
      return Object.fromEntries((liste as string[]).map((p) => [p, calcule.getPropertyValue(p)]));
    },
    [proprietes, pseudo ?? null] as const,
  );
}

async function pasDeDefilementHorizontal(page: Page): Promise<void> {
  const debordement = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(debordement, 'défilement horizontal').toBeLessThanOrEqual(0);
}

async function atteindreAuClavier(page: Page, cible: Locator): Promise<void> {
  for (let i = 0; i < 40; i++) {
    if (await cible.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  expect(await cible.evaluate((el) => el === document.activeElement), 'élément atteint par Tab').toBe(true);
}

async function focusVisible(cible: Locator): Promise<void> {
  const s = await styles(cible, ['outline-style', 'outline-width', 'box-shadow']);
  const contour = s['outline-style'] !== 'none' && parseFloat(s['outline-width']) > 0;
  expect(contour || s['box-shadow'] !== 'none', 'contour de focus visible').toBe(true);
}

for (const { largeur, zoom, libelle } of [
  { largeur: 375, zoom: false, libelle: '375 px' },
  { largeur: 375, zoom: true, libelle: '375 px, texte à 200 %' },
  { largeur: 320, zoom: false, libelle: '320 px' },
  { largeur: 320, zoom: true, libelle: '320 px, texte à 200 %' },
]) {
  test(`CA21 - l'écran de scan à ${libelle} : pas de défilement horizontal, cibles de 44 px, pseudo long contenu`, async ({ page, playwright }) => {
    const pseudoLong = `${pseudoUnique('l')}`.padEnd(30, 'm').slice(0, 30);
    expect(pseudoLong).toHaveLength(30);
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, pseudos: [pseudoLong] });
    await page.setViewportSize({ width: largeur, height: 740 });
    await ouvrirEcranDeScan(page, course);
    if (zoom) await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
    await pasDeDefilementHorizontal(page);

    const formulaire = page.locator('form');
    const boiteForm = (await formulaire.boundingBox())!;
    const boiteChamp = (await champ(page).boundingBox())!;
    const bouton = page.getByTestId('scan-bouton-enregistrer');
    const boiteBouton = (await bouton.boundingBox())!;
    expect(boiteChamp.height).toBeGreaterThanOrEqual(44);
    expect(boiteBouton.height).toBeGreaterThanOrEqual(44);
    expect(Math.abs(boiteChamp.width - boiteBouton.width)).toBeLessThanOrEqual(2);
    expect(boiteChamp.width).toBeGreaterThanOrEqual(boiteForm.width - 2);
    expect((await page.getByTestId('scan-lien-changer').boundingBox())!.height).toBeGreaterThanOrEqual(44);

    await scannerParEntree(page, course.coureurs[0].jeton);
    await verifierSucces(page, 1, pseudoLong, 1);
    await pasDeDefilementHorizontal(page);
    const carte = (await page.locator('section.carte').boundingBox())!;
    const pseudo = (await page.getByTestId('scan-resultat-pseudo').boundingBox())!;
    expect(pseudo.x + pseudo.width).toBeLessThanOrEqual(carte.x + carte.width + 1);
    const succes = (await page.getByTestId('scan-resultat-succes').boundingBox())!;
    expect(succes.x + succes.width).toBeLessThanOrEqual(carte.x + carte.width + 1);

    const dossard = await styles(page.getByTestId('scan-resultat-dossard'), ['font-size']);
    const corps = await styles(page.getByTestId('scan-resultat-pseudo'), ['font-size']);
    expect(parseFloat(dossard['font-size'])).toBeGreaterThan(parseFloat(corps['font-size']));

    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page.getByTestId('scan-resultat-erreur')).toBeVisible();
    await pasDeDefilementHorizontal(page);
    const boiteErreur = (await page.getByTestId('scan-resultat-erreur').boundingBox())!;
    expect(boiteErreur.x + boiteErreur.width).toBeLessThanOrEqual(carte.x + carte.width + 1);
  });
}

test('CA21 - le retour vert et rouge a un contraste AA, un pictogramme et un libellé, jamais la couleur seule', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
  await page.setViewportSize({ width: 375, height: 740 });
  await ouvrirEcranDeScan(page, course);

  for (const etat of ['succes', 'erreur'] as const) {
    await scannerParEntree(page, course.coureurs[0].jeton);
    const bloc = page.getByTestId(`scan-resultat-${etat}`);
    await expect(bloc).toBeVisible();
    const couleurs = await styles(bloc, ['color', 'background-color']);
    expect(contraste(couleurs['color'], couleurs['background-color']), `contraste ${etat}`).toBeGreaterThanOrEqual(4.5);
    expect((await bloc.innerText()).trim().length, 'libellé').toBeGreaterThan(0);
    const picto = await styles(bloc, ['content', 'mask-image', '-webkit-mask-image', 'width', 'height'], '::before');
    expect(picto['content']).not.toBe('none');
    expect(picto['mask-image'] !== 'none' || picto['-webkit-mask-image'] !== 'none', `pictogramme ${etat}`).toBe(true);
    expect(parseFloat(picto['width'])).toBeGreaterThan(0);
    expect(parseFloat(picto['height'])).toBeGreaterThan(0);
  }

  expect(await page.getByTestId('scan-resultat-erreur').getAttribute('role')).toBe('alert');
});

test('CA21 - focus visible au clavier sur le champ, le bouton et « Changer de course », rôles status et alert', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
  await ouvrirEcranDeScan(page, course);
  await page.keyboard.press('Tab');
  for (const cible of [page.getByTestId('scan-lien-changer'), champ(page), page.getByTestId('scan-bouton-enregistrer')]) {
    await atteindreAuClavier(page, cible);
    await focusVisible(cible);
  }
  await scannerParEntree(page, course.coureurs[0].jeton);
  await expect(page.getByTestId('scan-resultat-succes')).toHaveAttribute('role', 'status');
  await scannerParEntree(page, course.coureurs[0].jeton);
  await expect(page.getByTestId('scan-resultat-erreur')).toHaveAttribute('role', 'alert');
});

test('CA21 - prefers-reduced-motion : aucune animation ni transition sur l\'écran de scan', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await ouvrirEcranDeScan(page, course);
  await scannerParEntree(page, course.coureurs[0].jeton);
  await expect(page.getByTestId('scan-resultat-succes')).toBeVisible();
  await scannerParEntree(page, course.coureurs[0].jeton);
  await expect(page.getByTestId('scan-resultat-erreur')).toBeVisible();
  const animations = await page.evaluate(() =>
    [...document.querySelectorAll('main, main *')].flatMap((el) => {
      const s = getComputedStyle(el);
      const duree = [...s.animationDuration.split(','), ...s.transitionDuration.split(',')];
      return s.animationName !== 'none' || duree.some((d) => parseFloat(d) > 0.001) ? [el.tagName + '.' + el.className] : [];
    }),
  );
  expect(animations).toEqual([]);
  expect(await page.getByTestId('scan-resultat-erreur').evaluate((el) => getComputedStyle(el).animationName)).toBe('none');
});

test('CA21 - le champ de saisie est configuré contre la correction automatique et n\'est pas un mot de passe', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
  await ouvrirEcranDeScan(page, course);
  const saisie = champ(page);
  await expect(saisie).toHaveAttribute('type', 'text');
  await expect(saisie).toHaveAttribute('autocomplete', 'off');
  await expect(saisie).toHaveAttribute('autocapitalize', 'off');
  await expect(saisie).toHaveAttribute('autocorrect', 'off');
  await expect(saisie).toHaveAttribute('spellcheck', 'false');
  await expect(saisie).toHaveAttribute('enterkeyhint', 'send');
  await expect(page.getByLabel('Jeton du coureur')).toBeVisible();
});
