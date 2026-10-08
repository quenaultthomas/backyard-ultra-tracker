import { expect, test } from '@playwright/test';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { ALIAS, AUTRES_VARIABLES, PALETTE, ratioContraste } from './aide-theme';

const RACINE = join(__dirname, '..', '..');
const APERCU = join(RACINE, 'docs', 'design-apercu.html');
const CAPTURE = join(RACINE, 'docs', 'images', 'design-apercu.png');
const DESIGN = join(RACINE, 'docs', 'design.md');
const VARIABLES = join(RACINE, 'frontend', 'src', 'styles', 'variables.css');
const POLICE = join(RACINE, 'frontend', 'src', 'styles', 'polices', 'inter-4.1-latin-variable.woff2');

const COMPOSANTS = [
  'palette', 'typographie', 'bouton', 'bouton-secondaire', 'bouton-danger', 'bouton-danger-contour', 'bouton-sur-fonce',
  'bouton-desactive', 'champ', 'champ-invalide', 'champ-desactive', 'carte', 'message-erreur', 'message-succes',
  'message-info', 'message-avertissement', 'pastille-statut-en-preparation', 'pastille-statut-en-cours',
  'pastille-statut-terminee', 'pastille-statut-en-course', 'pastille-statut-abandon', 'pastille-statut-vainqueur',
  'entete', 'motif',
];

test('CA12 - l\'aperçu de la charte montre chaque composant avec le CSS réel, sans script ni requête externe', async ({ page }) => {
  const requetes: string[] = [];
  page.on('request', (r) => requetes.push(r.url()));
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(pathToFileURL(APERCU).href);

  for (const composant of COMPOSANTS) {
    await expect(page.locator(`[data-composant="${composant}"]`), composant).toHaveCount(1);
  }

  // Palette : 18 pastilles de couleur, chacune avec son nom et son hexadécimal.
  const couleurs = await page.locator('[data-composant="palette"] .couleur').allInnerTexts();
  expect(couleurs).toHaveLength(18);
  for (const [nom, hex] of Object.entries(PALETTE)) {
    expect(couleurs.some((t) => t.includes(nom) && t.toUpperCase().includes(hex.toUpperCase())), `${nom} ${hex}`).toBe(true);
  }

  // Le CSS réel est chargé.
  const foret = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--couleur-foret').trim());
  expect(foret.toUpperCase()).toBe('#14352A');
  await page.evaluate(() => document.fonts.ready);
  const etats = await page.evaluate(() => Array.from(document.fonts).filter((f) => f.family.replace(/["']/g, '') === 'Inter').map((f) => f.status));
  expect(etats).toContain('loaded');

  expect(requetes.filter((u) => !u.startsWith('file://') && !u.startsWith('data:'))).toEqual([]);
  await expect(page.locator('script')).toHaveCount(0);

  if (process.env.MAJ_CAPTURE === '1') {
    await page.screenshot({ path: CAPTURE, fullPage: true });
  }
  expect(existsSync(CAPTURE), 'docs/images/design-apercu.png').toBe(true);
  expect(statSync(CAPTURE).size).toBeGreaterThan(0);
  const png = readFileSync(CAPTURE);
  expect(png.subarray(1, 4).toString('ascii')).toBe('PNG');
  expect(png.readUInt32BE(16)).toBeGreaterThanOrEqual(1000);

  const design = readFileSync(DESIGN, 'utf8');
  expect(design).toContain('images/design-apercu.png');
  expect(design).toContain('design-apercu.html');
});

/* ------------------------------------------------------------------ CA13 */

function ligneDe(design: string, nom: string): string | undefined {
  return design.split('\n').find((l) => l.includes(`\`${nom}\``));
}

test('CA13 - docs/design.md contient les 10 sections, toutes les variables, les pastilles, la licence et des contrastes exacts', () => {
  const design = readFileSync(DESIGN, 'utf8');

  // Les 10 sections, dans l'ordre.
  const positions = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((n) => design.search(new RegExp(`^## ${n}\\. `, 'm')));
  for (const p of positions) expect(p).toBeGreaterThanOrEqual(0);
  expect([...positions].sort((a, b) => a - b)).toEqual(positions);

  // Chaque variable de RG3, RG6, RG8 avec sa valeur exacte.
  for (const [nom, valeur] of Object.entries({ ...PALETTE, ...ALIAS, ...AUTRES_VARIABLES })) {
    const ligne = ligneDe(design, nom);
    expect(ligne, `variable ${nom} absente de design.md`).toBeTruthy();
    expect(ligne, `valeur de ${nom}`).toContain(`\`${valeur}\``);
  }

  // Les valeurs de design.md sont celles de variables.css.
  const css = readFileSync(VARIABLES, 'utf8');
  const declarees = [...css.matchAll(/^\s*(--[a-z0-9-]+):\s*([^;]+);/gm)].map((m) => ({ nom: m[1], valeur: m[2].trim() }));
  expect(declarees.length).toBe(Object.keys({ ...PALETTE, ...ALIAS, ...AUTRES_VARIABLES }).length);
  for (const { nom, valeur } of declarees) {
    const ligne = ligneDe(design, nom);
    expect(ligne, `${nom} de variables.css absente de design.md`).toBeTruthy();
    expect(ligne, `valeur de ${nom} dans design.md`).toContain(`\`${valeur}\``);
  }

  // Tableau des 6 pastilles.
  for (const modificateur of ['--en-preparation', '--en-cours', '--terminee', '--en-course', '--abandon', '--vainqueur']) {
    expect(ligneDe(design, modificateur), `pastille ${modificateur}`).toBeTruthy();
  }
  for (const libelle of ['En préparation', 'En cours', 'Terminée', 'En course', 'Abandon', 'Vainqueur']) {
    expect(design).toContain(`| ${libelle} |`);
  }

  // Licence, version, somme SHA-256 recalculée du fichier du dépôt.
  expect(design).toMatch(/SIL Open Font License 1\.1/);
  expect(design).toMatch(/Inter 4\.1/);
  const somme = createHash('sha256').update(readFileSync(POLICE)).digest('hex');
  expect(design.toLowerCase()).toContain(somme);

  // Les 4 décisions de direction artistique.
  expect(design).toMatch(/clair/i);
  expect(design).toMatch(/crème/i);
  expect(design).toMatch(/Inter/);
  expect(design).toMatch(/sans police display/i);
  expect(design).toMatch(/courbes de niveau/i);
  expect(design).toMatch(/boucle fermée/i);
  expect(design).toMatch(/aucun sapin/i);
  expect(design).toMatch(/forêt/i);
  expect(design).toMatch(/orange/i);

  // Contrastes : ratio recalculé = ratio affiché (0,05 près) et >= seuil.
  const section3 = design.split(/^## 3\. /m)[1].split(/^## 4\. /m)[0];
  const hexDe: Record<string, string> = {
    'encre': PALETTE['--couleur-encre'], 'encre-douce': PALETTE['--couleur-encre-douce'], 'crème': PALETTE['--couleur-creme'],
    'surface': PALETTE['--couleur-surface'], 'sable': PALETTE['--couleur-sable'], 'trait': PALETTE['--couleur-trait'],
    'forêt': PALETTE['--couleur-foret'], 'forêt-survol': PALETTE['--couleur-foret-survol'], 'orange': PALETTE['--couleur-orange'],
    'orange-fonce': PALETTE['--couleur-orange-fonce'], 'orange-pale': PALETTE['--couleur-orange-pale'], 'danger': PALETTE['--couleur-danger'],
    'danger-survol': PALETTE['--couleur-danger-survol'], 'danger-pale': PALETTE['--couleur-danger-pale'],
    'succes-pale': PALETTE['--couleur-succes-pale'], 'bordure-champ': PALETTE['--couleur-bordure-champ'],
  };
  const rangees = section3.split('\n').filter((l) => /^\|.*\/.*\|\s*[\d,]+\s*\|/.test(l));
  expect(rangees.length).toBeGreaterThanOrEqual(26);
  for (const rangee of rangees) {
    const [couple, ratio, seuil, conforme] = rangee.split('|').slice(1, 5).map((c) => c.trim());
    const [a, b] = couple.replace(/\s*\(.*$/, '').split(' / ').map((c) => c.trim());
    expect(hexDe[a], `couleur « ${a} » (${couple})`).toBeTruthy();
    expect(hexDe[b], `couleur « ${b} » (${couple})`).toBeTruthy();
    const calcule = ratioContraste(hexDe[a], hexDe[b]);
    const affiche = parseFloat(ratio.replace(',', '.'));
    expect(Math.abs(calcule - affiche), `${couple} : calculé ${calcule.toFixed(3)}, affiché ${ratio}`).toBeLessThanOrEqual(0.05);
    expect(calcule, `${couple} sous le seuil ${seuil}`).toBeGreaterThanOrEqual(parseFloat(seuil.replace(',', '.')));
    expect(conforme).toBe('oui');
  }
});

