import { expect, test, type Request } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { BASE_URL, ECRANS, ouvrirScene, preparerJeuReference, type JeuReference } from './aide-theme';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const CSP_ATTENDUE =
  "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

test('CA2 - la police Inter est auto-hébergée, chargée, servie en font/woff2 immutable (<= 100 Ko)', async ({ page, request }) => {
  const reponses: { url: string; statut: number; type: string | undefined; cache: string | undefined; taille: number }[] = [];
  page.on('response', async (r) => {
    if (/\.woff2(\?|$)/.test(r.url())) {
      const corps = await r.body().catch(() => Buffer.alloc(0));
      reponses.push({ url: r.url(), statut: r.status(), type: r.headers()['content-type'], cache: r.headers()['cache-control'], taille: corps.length });
    }
  });
  await page.goto('/connexion');
  await expect(page.getByTestId('titre-connexion')).toBeVisible();
  await page.evaluate(() => document.fonts.ready);

  expect(await page.evaluate(() => document.fonts.check('400 16px Inter'))).toBe(true);
  expect(await page.evaluate(() => document.fonts.check('700 16px Inter'))).toBe(true);
  const etats = await page.evaluate(() => Array.from(document.fonts).filter((f) => f.family.replace(/["']/g, '') === 'Inter').map((f) => f.status));
  expect(etats.length).toBeGreaterThan(0);
  expect(etats).toContain('loaded');

  await expect.poll(() => reponses.length).toBeGreaterThan(0);
  const police = reponses[0];
  expect(new URL(police.url).origin).toBe(new URL(BASE_URL).origin);
  expect(police.statut).toBe(200);
  expect(police.type).toContain('font/woff2');
  expect(police.cache).toBe('public, max-age=31536000, immutable');
  expect(police.taille).toBeGreaterThan(0);
  expect(police.taille).toBeLessThanOrEqual(100 * 1024);

  const famille = await page.evaluate(() => getComputedStyle(document.body).fontFamily);
  expect(famille.replace(/["']/g, '')).toMatch(/^Inter\b/);

  const affichage = await page.evaluate(() => {
    const rangees: string[] = [];
    const parcourir = (regles: CSSRuleList): void => {
      for (const regle of Array.from(regles)) {
        if (regle instanceof CSSFontFaceRule && regle.style.getPropertyValue('font-family').replace(/["']/g, '') === 'Inter') {
          rangees.push(regle.style.getPropertyValue('font-display'));
        }
        if ('cssRules' in regle) parcourir((regle as CSSGroupingRule).cssRules);
      }
    };
    for (const feuille of Array.from(document.styleSheets)) parcourir(feuille.cssRules);
    return rangees;
  });
  expect(affichage).toEqual(['swap']);

  const licence = await request.get('/licences/Inter-OFL.txt');
  expect(licence.status()).toBe(200);
  expect(licence.headers()['content-type']).toContain('text/plain');
  expect(await licence.text()).toContain('SIL OPEN FONT LICENSE');

  const accueil = await request.get('/');
  expect(accueil.status()).toBe(200);
  expect(accueil.headers()['cache-control']).toBe('no-cache');
});

test.describe('CA4 - aucune requête externe sur les 13 écrans', () => {
  let jeu: JeuReference;
  test.beforeAll(async ({ playwright }) => {
    jeu = await preparerJeuReference(playwright);
  });

  for (const ecran of ECRANS) {
    test(`CA4 - toutes les requêtes de l'écran « ${ecran.nom} » ont l'origine du site`, async ({ page }) => {
      test.setTimeout(60_000);
      const origine = new URL(BASE_URL).origin;
      const etrangeres: string[] = [];
      page.on('request', (r: Request) => {
        const url = r.url();
        if (url.startsWith('data:') || url.startsWith('blob:')) return;
        if (new URL(url).origin !== origine) etrangeres.push(url);
      });
      await ouvrirScene(page, ecran, jeu);
      await page.evaluate(() => document.fonts.ready);
      expect(etrangeres).toEqual([]);
    });
  }
});

test('CA4 - la CSP est inchangée, index.html ne lie aucune autre origine et les ressources empreintes sont immutables', async ({ request }) => {
  const accueil = await request.get('/');
  expect(accueil.headers()['content-security-policy']).toBe(CSP_ATTENDUE);
  const html = await accueil.text();

  const liens = [...html.matchAll(/<link\b[^>]*>/g)].map((m) => m[0]);
  expect(liens.length).toBeGreaterThan(0);
  for (const lien of liens) {
    const href = /href="([^"]+)"/.exec(lien)?.[1] ?? '';
    expect(/^(https?:)?\/\//.test(href), `lien externe : ${lien}`).toBe(false);
  }
  expect(html).not.toMatch(/fonts\.googleapis|fonts\.gstatic|preconnect/);

  // Fichiers empreintes de premier niveau : main-*.js et styles-*.css (les chunks lazy à empreinte de casse mixte sont exclus).
  const principal = /src="\/?(main-[A-Z0-9]{8}\.js)"/.exec(html)?.[1];
  const styles = /href="\/?(styles-[A-Z0-9]{8}\.css)"/.exec(html)?.[1];
  expect(principal, 'main-*.js référencé').toBeTruthy();
  expect(styles, 'styles-*.css référencé').toBeTruthy();
  const css = await request.get(`/${styles}`);
  expect(css.status()).toBe(200);
  const texteCss = await css.text();
  const police = /url\("?\.?\/?(media\/inter-4\.1-latin-variable-[A-Z0-9]{8}\.woff2)"?\)/.exec(texteCss)?.[1];
  // Le motif est une URI data: dans le CSS : aucun fichier à servir ni à mettre en cache.
  expect(texteCss).toContain('data:image/svg+xml');
  expect(texteCss).not.toMatch(/courbes-niveau[^"')]*\.svg/);
  expect(police, 'police empreinte dans le CSS').toBeTruthy();

  for (const chemin of [principal!, styles!, police!]) {
    const r = await request.get(`/${chemin}`);
    expect(r.status(), chemin).toBe(200);
    expect(r.headers()['cache-control'], chemin).toBe('public, max-age=31536000, immutable');
  }
});
