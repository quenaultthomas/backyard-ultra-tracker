import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { ALIAS, AUTRES_VARIABLES, PALETTE, normaliserValeurCss, ouvrirSession, preparerJeuReference } from './aide-theme';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const ATTENDUES: Record<string, string> = { ...PALETTE, ...ALIAS, ...AUTRES_VARIABLES };

/** Une propriété personnalisée se lit une fois ses `var()` résolus : l'alias vaut la valeur de la variable visée. */
function valeurResolue(attendue: string): string {
  const reference = /^var\((--[a-z-]+)\)$/.exec(attendue)?.[1];
  return reference ? ATTENDUES[reference] : attendue;
}

test('CA1 - les variables de la charte ont les valeurs de la spec et aucun nom anglais n\'est défini', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('etat-api')).toBeVisible();

  const lues = await page.evaluate((noms) => {
    const style = getComputedStyle(document.documentElement);
    const valeurs: Record<string, string> = {};
    for (const nom of noms) valeurs[nom] = style.getPropertyValue(nom);
    return { valeurs, schemaCouleurs: style.colorScheme };
  }, Object.keys(ATTENDUES));

  for (const [nom, attendue] of Object.entries(ATTENDUES)) {
    expect(normaliserValeurCss(lues.valeurs[nom]), `variable ${nom}`).toBe(normaliserValeurCss(valeurResolue(attendue)));
  }
  expect(lues.schemaCouleurs).toBe('light');

  // Noms définis sur :root par les feuilles du site : exactement ceux de la charte, tous en français.
  const definies = await page.evaluate(() => {
    const noms = new Set<string>();
    for (const feuille of Array.from(document.styleSheets)) {
      const parcourir = (regles: CSSRuleList): void => {
        for (const regle of Array.from(regles)) {
          if (regle instanceof CSSStyleRule && regle.selectorText.split(',').map((s) => s.trim()).includes(':root')) {
            for (const propriete of Array.from(regle.style)) if (propriete.startsWith('--')) noms.add(propriete);
          }
          if ('cssRules' in regle) parcourir((regle as CSSGroupingRule).cssRules);
        }
      };
      parcourir(feuille.cssRules);
    }
    return Array.from(noms).sort();
  });
  expect(definies).toEqual(Object.keys(ATTENDUES).sort());
  const anglais = /^--(color|colour|font|size|space|spacing|radius|shadow|width|height|duration|line|weight|border|background|text)\b/;
  expect(definies.filter((n) => anglais.test(n))).toEqual([]);
});

test('CA3 - les chiffres sont tabulaires (Inter) et le dossard est affiché en Inter', async ({ page, playwright }) => {
  await page.goto('/');
  await expect(page.getByTestId('etat-api')).toBeVisible();
  await page.evaluate(() => document.fonts.load('400 16px Inter'));
  await page.evaluate(() => document.fonts.ready);

  const mesure = await page.evaluate(() => {
    const largeur = (texte: string): number => {
      const s = document.createElement('span');
      s.style.fontFamily = 'Inter';
      s.style.position = 'absolute';
      s.style.whiteSpace = 'nowrap';
      s.textContent = texte;
      document.body.appendChild(s);
      const l = s.getBoundingClientRect().width;
      s.remove();
      return l;
    };
    return { uns: largeur('1111111111'), zeros: largeur('0000000000'), variante: getComputedStyle(document.body).fontVariantNumeric };
  });
  expect(mesure.variante).toContain('tabular-nums');
  expect(Math.abs(mesure.uns - mesure.zeros)).toBeLessThanOrEqual(0.5);

  const jeu = await preparerJeuReference(playwright);
  await ouvrirSession(page, 'coureur', jeu);
  await page.goto('/coureur/inscriptions');
  const dossard = page.getByTestId('inscriptions-dossard').first();
  await expect(dossard).toBeVisible();
  await expect(dossard).toContainText('1');
  const police = await dossard.evaluate((el) => getComputedStyle(el).fontFamily);
  expect(police.replace(/["']/g, '')).toMatch(/^Inter\b/);
  await page.evaluate(() => document.fonts.ready);
  expect(await page.evaluate(() => document.fonts.check('400 16px Inter'))).toBe(true);
});
