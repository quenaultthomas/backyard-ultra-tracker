import { expect, test, type Page } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { BASE_URL, ECRANS, ouvrirScene, preparerJeuReference, type JeuReference } from './aide-theme';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

let jeu: JeuReference;
test.beforeAll(async ({ playwright }) => {
  jeu = await preparerJeuReference(playwright);
});

const ecran = (nom: string) => ECRANS.find((e) => e.nom === nom)!;

/** Éléments `data-testid` de l'en-tête, dans l'ordre du DOM, avec leur texte. */
async function contenuEntete(page: Page): Promise<{ testid: string | null; texte: string }[]> {
  return page.locator('.entete').evaluate((entete) =>
    Array.from(entete.querySelectorAll('[data-testid]')).map((e) => ({ testid: e.getAttribute('data-testid'), texte: (e.textContent ?? '').trim().replace(/\s+/g, ' ') })),
  );
}

async function stylesCommuns(page: Page): Promise<void> {
  const entete = page.locator('.entete');
  await expect(entete).toHaveCSS('background-color', 'rgb(20, 53, 42)');
  await expect(entete).toHaveCSS('color', 'rgb(247, 241, 227)');
  await expect(entete).toHaveCSS('border-bottom-width', '3px');
  await expect(entete).toHaveCSS('border-bottom-style', 'solid');
  await expect(entete).toHaveCSS('border-bottom-color', 'rgb(242, 140, 40)');
  const titre = page.getByTestId('entete-titre');
  expect(await titre.evaluate((e) => getComputedStyle(e).textDecorationLine)).toBe('none');
  const body = page.locator('body');
  await expect(body).toHaveCSS('background-color', 'rgb(247, 241, 227)');
  await expect(body).toHaveCSS('color', 'rgb(27, 42, 34)');
  const fond = await body.evaluate((e) => getComputedStyle(e).backgroundImage);
  expect(fond.includes('data:image/svg+xml')).toBe(true);
}

test('CA7 - l\'en-tête administrateur de l\'accueil a la charte et ses data-testid inchangés', async ({ page }) => {
  await ouvrirScene(page, { ...ecran('accueil'), role: 'master' }, jeu);
  await expect(page.getByTestId('lien-administration')).toBeVisible();
  await stylesCommuns(page);
  await expect(page.getByTestId('lien-administration')).toHaveCSS('color', 'rgb(242, 140, 40)');
  const contenu = await contenuEntete(page);
  expect(contenu.map((c) => c.testid)).toEqual(['entete-titre', 'entete-pseudo', 'lien-administration', 'lien-mon-compte', 'bouton-deconnexion']);
  expect(contenu.find((c) => c.testid === 'entete-titre')?.texte).toBe('Backyard Ultra Tracker');
  expect(contenu.find((c) => c.testid === 'lien-administration')?.texte).toBe('Administration');
  expect(contenu.find((c) => c.testid === 'lien-mon-compte')?.texte).toBe('Mon compte');
  expect(contenu.find((c) => c.testid === 'bouton-deconnexion')?.texte).toBe('Se déconnecter');
});

test('CA7 - l\'en-tête anonyme de la connexion a la charte et ses data-testid inchangés', async ({ page }) => {
  await ouvrirScene(page, ecran('connexion'), jeu);
  await stylesCommuns(page);
  await expect(page.getByTestId('lien-se-connecter')).toHaveCSS('color', 'rgb(242, 140, 40)');
  const contenu = await contenuEntete(page);
  expect(contenu).toEqual([
    { testid: 'entete-titre', texte: 'Backyard Ultra Tracker' },
    { testid: 'lien-se-connecter', texte: 'Se connecter' },
  ]);
});

test('CA7 - l\'en-tête coureur de « Mes inscriptions » a la charte et ses data-testid inchangés', async ({ page }) => {
  await ouvrirScene(page, ecran('mes inscriptions'), jeu);
  await stylesCommuns(page);
  await expect(page.getByTestId('lien-mes-inscriptions')).toHaveCSS('color', 'rgb(242, 140, 40)');
  const contenu = await contenuEntete(page);
  expect(contenu.map((c) => c.testid)).toEqual(['entete-titre', 'entete-pseudo', 'lien-espace-coureur', 'lien-mes-inscriptions', 'lien-mon-compte', 'bouton-deconnexion']);
  expect(contenu.find((c) => c.testid === 'entete-pseudo')?.texte).toBe(jeu.coureur);
  expect(contenu.find((c) => c.testid === 'lien-espace-coureur')?.texte).toBe('Espace coureur');
  expect(contenu.find((c) => c.testid === 'lien-mes-inscriptions')?.texte).toBe('Mes inscriptions');
});

test('CA8 - le motif de fond est une tuile SVG conforme (courbes de niveau et une boucle fermée)', async ({ page, request }) => {
  await page.goto('/');
  await expect(page.getByTestId('etat-api')).toBeVisible();
  const fond = await page.locator('body').evaluate((e) => ({ image: getComputedStyle(e).backgroundImage, repetition: getComputedStyle(e).backgroundRepeat }));
  expect(fond.repetition).toMatch(/^repeat(\s+repeat)?$/);

  // Le motif est une URI data: : aucun fichier à récupérer, aucune requête.
  const donnees = /url\("(data:image\/svg\+xml[^]*?)"\)|url\((data:image\/svg\+xml[^)]*)\)/.exec(fond.image);
  expect(donnees, 'data:image/svg+xml dans background-image').not.toBeNull();
  const uri = (donnees![1] ?? donnees![2]).replace(/\\"/g, '"');
  const brut = uri.replace(/^data:image\/svg\+xml(;[a-z0-9=-]+)*,/i, '');
  const svg = uri.includes(';base64') ? Buffer.from(brut, 'base64').toString('utf8') : decodeURIComponent(brut);
  const typeContenu = 'image/svg+xml';
  expect(typeContenu).toContain('image/svg+xml');
  expect(Buffer.byteLength(svg)).toBeLessThanOrEqual(4096);
  expect(svg).toMatch(/viewBox=["']0 0 480 480["']/);

  const chemins = [...svg.matchAll(/<path\b[^>]*>/g)].map((m) => m[0]);
  const fermes = chemins.filter((c) => /\sd=(["'])[^"']*[Zz]\s*\1/.test(c));
  const courbes = chemins.filter((c) => !fermes.includes(c));
  expect(fermes).toHaveLength(1);
  expect(fermes[0]).toContain('stroke-dasharray');
  expect(courbes.length).toBeGreaterThanOrEqual(5);
  expect(courbes.length).toBeLessThanOrEqual(7);
  for (const c of courbes) expect(c).toMatch(/fill=["']none["']/);

  const traits = new Set([...svg.matchAll(/\sstroke=["']([^"']+)["']/g)].map((m) => m[1].toLowerCase()));
  expect([...traits]).toEqual(['#d9cfb6']);
  expect(svg).not.toMatch(/<script|<text|<image|<use|<foreignObject|xlink:href|\shref=|sapin/i);
});
