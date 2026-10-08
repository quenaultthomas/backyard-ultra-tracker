import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import {
  MOT_DE_PASSE_ADMIN_MASTER,
  PSEUDO_ADMIN_MASTER,
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  connecterParApi,
  creerAdminParApi,
  creerBenevoleParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique } from './aide-connexion';
import { ouvrirMenuVisiteur } from './aide-entete';
import { PALETTE, ratioContraste } from './aide-theme';

const TITRE = 'Backyard Ultra Tracker';
const ACCROCHE = "Une boucle après l'autre, jusqu'au dernier debout.";

const PALETTE_MINUSCULES = Object.values(PALETTE).map((c) => c.toLowerCase());

/** Charge `/` et attend que le titre, l'accroche et les polices soient prêts. */
async function chargerAccueil(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.getByTestId('titre')).toBeVisible();
  await expect(page.getByTestId('accroche')).toBeVisible();
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
}

interface Rect { x: number; y: number; width: number; height: number; bas: number; droite: number }

async function rect(page: Page, testid: string): Promise<Rect> {
  return page.getByTestId(testid).evaluate((e) => {
    const r = e.getBoundingClientRect();
    return { x: r.left, y: r.top, width: r.width, height: r.height, bas: r.bottom, droite: r.right };
  });
}

async function basMain(page: Page): Promise<number> {
  return page.locator('main').evaluate((e) => e.getBoundingClientRect().bottom);
}

/** Décode les couches de fond d'un `background-image` calculé : liste des SVG (texte) et nombre total de `url(`. */
function extraireSvg(backgroundImage: string): { svgs: string[]; nombreUrl: number } {
  const nombreUrl = (backgroundImage.match(/url\(/g) ?? []).length;
  const svgs = [...backgroundImage.matchAll(/url\("(data:image\/svg\+xml,[^"]*)"\)/g)].map((m) =>
    decodeURIComponent(m[1].replace(/^data:image\/svg\+xml,/, '')),
  );
  return { svgs, nombreUrl };
}

function couleursDe(svg: string): string[] {
  return [...svg.matchAll(/\b(?:stroke|fill)=["']([^"']+)["']/g)].map((m) => m[1].toLowerCase()).filter((c) => c !== 'none');
}

function cheminsDe(svg: string): string[] {
  return [...svg.matchAll(/<path\b[^>]*>/g)].map((m) => m[0]);
}

test.describe('Accueil', () => {
  test('[CA14 / 0.2 CA7] le visiteur voit le titre Backyard Ultra Tracker', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('titre')).toHaveText('Backyard Ultra Tracker');
  });

  test('R.4 CA1 - l\'accueil visiteur n\'a ni indicateur d\'API, ni lien de création de compte, ni requête de santé', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const requetes: string[] = [];
    page.on('request', (r) => requetes.push(r.url()));
    // L'horloge simulée fait écouler 6 s (plus que l'ancien intervalle de 5 s) sans attente réelle.
    await page.clock.install();
    await page.goto('/');
    await expect(page.getByTestId('titre')).toHaveText(TITRE);
    await expect(page.getByTestId('accroche')).toHaveText(ACCROCHE);
    await page.clock.runFor(6000);

    await expect(page.getByTestId('titre')).toHaveText(TITRE);
    await expect(page.getByTestId('accroche')).toHaveText(ACCROCHE);
    await expect(page.locator('main > *')).toHaveCount(2);
    await expect(page.locator('main > *').nth(0)).toHaveJSProperty('tagName', 'H1');
    await expect(page.locator('main > *').nth(1)).toHaveJSProperty('tagName', 'P');
    await expect(page.getByTestId('etat-api')).toHaveCount(0);
    await expect(page.getByTestId('lien-creer-compte')).toHaveCount(0);
    await expect(page.locator('main [role="status"], main [aria-live], main a, main button')).toHaveCount(0);
    await expect(page.locator('h1')).toHaveCount(1);
    await expect(page).toHaveTitle(TITRE);
    const texte = await page.evaluate(() => document.body.innerText);
    expect(texte).not.toMatch(/\bapi\b/i);
    expect(texte).not.toMatch(/indisponible|disponible/i);
    expect(requetes.filter((u) => u.includes('/api/sante'))).toEqual([]);

    await ouvrirMenuVisiteur(page);
    await page.getByTestId('menu-lien-creer-compte').click();
    await expect(page).toHaveURL(/\/creer-compte$/);
  });

  test.describe('selon la session', () => {
    test.beforeAll(() => {
      exigerIdentifiantsAdminMaster();
    });

    async function contexteConnecte(browser: Browser, pseudo: string, motDePasse: string): Promise<BrowserContext> {
      const contexte = await browser.newContext({ viewport: { width: 1280, height: 800 } });
      await connecterParApi(contexte.request, pseudo, motDePasse);
      return contexte;
    }

    test('R.4 CA2 - visiteur, coureur, bénévole, admin et admin master voient exactement la même page', async ({ browser, playwright }) => {
      const api = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
      const coureur = pseudoUnique('alice');
      const benevole = pseudoBenevoleUnique();
      const admin = pseudoAdminUnique();
      try {
        await creerCompteParApi(api, coureur);
        await creerBenevoleParApi(await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' }), benevole);
        await creerAdminParApi(await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' }), admin);
      } finally {
        await api.dispose();
      }

      const sessions: { nom: string; pseudo?: string; motDePasse?: string }[] = [
        { nom: 'visiteur' },
        { nom: 'coureur', pseudo: coureur, motDePasse: MOT_DE_PASSE },
        { nom: 'benevole', pseudo: benevole, motDePasse: MOT_DE_PASSE_BENEVOLE_CREE },
        { nom: 'admin', pseudo: admin, motDePasse: MOT_DE_PASSE_ADMIN_CREE },
        { nom: 'admin master', pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER },
      ];
      const mesures: { nom: string; titre: string; accroche: string; classe: string; image: string; fond: string; rt: Rect; ra: Rect }[] = [];
      for (const s of sessions) {
        const contexte = s.pseudo ? await contexteConnecte(browser, s.pseudo, s.motDePasse!) : await browser.newContext({ viewport: { width: 1280, height: 800 } });
        try {
          const page = await contexte.newPage();
          await chargerAccueil(page);
          await expect(page.getByTestId('lien-creer-compte')).toHaveCount(0);
          const style = await page.locator('main').evaluate((e) => ({ classe: e.className, image: getComputedStyle(e).backgroundImage, fond: getComputedStyle(e).backgroundColor }));
          mesures.push({
            nom: s.nom,
            titre: (await page.getByTestId('titre').textContent()) ?? '',
            accroche: (await page.getByTestId('accroche').textContent()) ?? '',
            ...style,
            rt: await rect(page, 'titre'),
            ra: await rect(page, 'accroche'),
          });
        } finally {
          await contexte.close();
        }
      }
      const ref = mesures[0];
      expect(ref.titre).toBe(TITRE);
      expect(ref.accroche).toBe(ACCROCHE);
      for (const m of mesures) {
        expect(m.titre, m.nom).toBe(ref.titre);
        expect(m.accroche, m.nom).toBe(ref.accroche);
        expect(m.classe, m.nom).toContain('decor-backyard');
        expect(m.classe, m.nom).toBe(ref.classe);
        expect(m.image, m.nom).toBe(ref.image);
        expect(m.fond, m.nom).toBe(ref.fond);
        for (const cle of ['x', 'y', 'width', 'height'] as const) {
          expect(Math.abs(m.rt[cle] - ref.rt[cle]), `${m.nom} titre ${cle}`).toBeLessThanOrEqual(1);
          expect(Math.abs(m.ra[cle] - ref.ra[cle]), `${m.nom} accroche ${cle}`).toBeLessThanOrEqual(1);
        }
      }
    });

    test('R.4 CA2 - la page est déjà complète pendant l\'état de session inconnu et ne bouge pas ensuite', async ({ browser, playwright }) => {
      const api = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
      const coureur = pseudoUnique('alice');
      try {
        await creerCompteParApi(api, coureur);
      } finally {
        await api.dispose();
      }
      const contexte = await contexteConnecte(browser, coureur, MOT_DE_PASSE);
      try {
        const page = await contexte.newPage();
        let reponseRecue = false;
        await page.route('**/api/comptes/moi', async (route) => {
          await new Promise((resolve) => setTimeout(resolve, 3000));
          reponseRecue = true;
          await route.continue();
        });
        await page.goto('/');
        await expect(page.getByTestId('titre')).toBeVisible();
        await expect(page.getByTestId('accroche')).toBeVisible();
        expect(reponseRecue, 'la session n\'est pas encore résolue').toBe(false);
        const avantTitre = await rect(page, 'titre');
        const avantAccroche = await rect(page, 'accroche');

        await expect(page.getByTestId('entete-pseudo')).toHaveText(coureur, { timeout: 10_000 });
        const apresTitre = await rect(page, 'titre');
        const apresAccroche = await rect(page, 'accroche');
        for (const cle of ['x', 'y', 'width', 'height'] as const) {
          expect(Math.abs(apresTitre[cle] - avantTitre[cle]), `titre ${cle}`).toBeLessThanOrEqual(1);
          expect(Math.abs(apresAccroche[cle] - avantAccroche[cle]), `accroche ${cle}`).toBeLessThanOrEqual(1);
        }
      } finally {
        await contexte.close();
      }
    });
  });

  test('R.4 CA3 - le décor est un fond CSS de deux tuiles SVG conformes, sans élément ni requête supplémentaire', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const requetes: string[] = [];
    page.on('request', (r) => requetes.push(r.url()));
    await chargerAccueil(page);

    const style = await page.locator('main').evaluate((e) => {
      const cs = getComputedStyle(e);
      return {
        couleur: cs.backgroundColor,
        image: cs.backgroundImage,
        repetition: cs.backgroundRepeat,
        positionY: cs.backgroundPositionY,
        taille: cs.backgroundSize,
        formes: e.querySelectorAll('svg, img, canvas, picture, video').length,
      };
    });
    expect(style.couleur).toBe('rgb(247, 241, 227)');
    const { svgs, nombreUrl } = extraireSvg(style.image);
    expect(nombreUrl).toBe(2);
    expect(svgs).toHaveLength(2);
    expect(style.repetition.split(',').map((s) => s.trim())).toEqual(['repeat-x', 'repeat']);
    expect(style.positionY.split(',')[0].trim()).toBe('100%');
    expect(style.taille.split(',')[0].trim()).toBe('auto 96px');
    expect(style.formes).toBe(0);
    await expect(page.locator('main svg, main img, main canvas, main picture, main video')).toHaveCount(0);

    const [sapins, courbes] = svgs;
    for (const svg of svgs) {
      expect(Buffer.byteLength(svg)).toBeLessThanOrEqual(4096);
      expect(svg).not.toMatch(/<script|<text|<image|<use|<foreignObject|href/i);
      expect(svg.replace(/xmlns=["'][^"']*["']/, '')).not.toMatch(/http/i);
      for (const c of couleursDe(svg)) expect(PALETTE_MINUSCULES, `couleur ${c}`).toContain(c);
    }

    const vbCourbes = /viewBox=["']0 0 640 480["']/.exec(courbes);
    expect(vbCourbes, 'viewBox des courbes').not.toBeNull();
    const vbSapins = /viewBox=["']0 0 (\d+) (\d+)["']/.exec(sapins);
    expect(vbSapins, 'viewBox des sapins').not.toBeNull();
    expect(Number(vbSapins![1])).toBeLessThanOrEqual(480);
    expect(Number(vbSapins![2])).toBeLessThanOrEqual(160);

    expect(new Set(couleursDe(courbes))).toEqual(new Set(['#d9cfb6']));
    const cheminsCourbes = cheminsDe(courbes);
    const fermes = cheminsCourbes.filter((c) => /\sd=(["'])[^"']*[Zz]\s*\1/.test(c));
    const ouverts = cheminsCourbes.filter((c) => !fermes.includes(c));
    expect(fermes).toHaveLength(1);
    expect(fermes[0]).toContain('stroke-dasharray');
    expect(ouverts.length).toBeGreaterThanOrEqual(5);
    for (const c of ouverts) expect(c).toMatch(/fill=["']none["']/);

    for (const c of couleursDe(sapins)) expect(['#1f5a43', '#14352a']).toContain(c);
    expect(cheminsDe(sapins).length + (sapins.match(/<(polygon|rect|circle|ellipse)\b/g) ?? []).length).toBeGreaterThanOrEqual(5);

    expect(requetes.filter((u) => /\.(svg|png|jpe?g|webp)(\?|$)/i.test(u))).toEqual([]);
    const origine = new URL(page.url()).origin;
    expect(requetes.filter((u) => !u.startsWith(origine) && !u.startsWith('data:'))).toEqual([]);
  });

  for (const viewport of [
    { width: 1280, height: 800 },
    { width: 360, height: 640 },
    { width: 320, height: 640 },
  ]) {
    test(`R.4 CA4 - titre et accroche sont lisibles sur le décor à ${viewport.width} px et hors de la bande de sapins`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await chargerAccueil(page);
      const mesures = await page.evaluate(() => {
        const main = document.querySelector('main')!;
        const titre = document.querySelector('[data-testid="titre"]')!;
        const accroche = document.querySelector('[data-testid="accroche"]')!;
        const entete = document.querySelector('.entete')!;
        return {
          couleurTitre: getComputedStyle(titre).color,
          couleurAccroche: getComputedStyle(accroche).color,
          paddingBas: parseFloat(getComputedStyle(main).paddingBottom),
          basAccroche: accroche.getBoundingClientRect().bottom,
          basMain: main.getBoundingClientRect().bottom,
          hautTitre: titre.getBoundingClientRect().top,
          basEntete: entete.getBoundingClientRect().bottom,
          taillePoliceAccroche: parseFloat(getComputedStyle(accroche).fontSize),
        };
      });
      expect(mesures.couleurTitre).toBe('rgb(20, 53, 42)');
      expect(mesures.couleurAccroche).toBe('rgb(27, 42, 34)');
      for (const texte of ['#14352A', '#1B2A22']) {
        for (const dessous of ['#F7F1E3', '#D9CFB6']) {
          expect(ratioContraste(texte, dessous), `${texte} sur ${dessous}`).toBeGreaterThanOrEqual(4.5);
        }
      }
      expect(mesures.paddingBas).toBeGreaterThanOrEqual(112);
      expect(mesures.basAccroche).toBeLessThanOrEqual(mesures.basMain - 96);
      expect(mesures.hautTitre).toBeGreaterThanOrEqual(mesures.basEntete);
      expect(mesures.taillePoliceAccroche).toBeGreaterThanOrEqual(13);
    });
  }

  test.describe('mise en page', () => {
    const attendue = (largeur: number) => Math.min(Math.max(32, 0.06 * largeur), 64);

    for (const v of [
      { width: 320, height: 640, sansDefilement: false },
      { width: 360, height: 640, sansDefilement: true },
      { width: 1280, height: 800, sansDefilement: true },
      { width: 1920, height: 1080, sansDefilement: true },
    ]) {
      test(`R.4 CA5 - à ${v.width} x ${v.height} la page tient dans l'écran, centrée, sapins au bas de la fenêtre`, async ({ page }) => {
        await page.setViewportSize({ width: v.width, height: v.height });
        await chargerAccueil(page);
        const m = await page.evaluate(() => {
          const racine = document.documentElement;
          const main = document.querySelector('main')!.getBoundingClientRect();
          return {
            scrollWidth: racine.scrollWidth,
            clientWidth: racine.clientWidth,
            scrollHeight: racine.scrollHeight,
            clientHeight: racine.clientHeight,
            hauteurFenetre: window.innerHeight,
            largeurFenetre: window.innerWidth,
            basMain: main.bottom,
            centreMain: main.left + main.width / 2,
            taillePoliceTitre: parseFloat(getComputedStyle(document.querySelector('[data-testid="titre"]')!).fontSize),
          };
        });
        expect(m.scrollWidth).toBeLessThanOrEqual(m.clientWidth);
        for (const id of ['titre', 'accroche']) {
          const r = await rect(page, id);
          expect(r.x, `${id} gauche`).toBeGreaterThanOrEqual(0);
          expect(r.droite, `${id} droite`).toBeLessThanOrEqual(m.largeurFenetre);
        }
        expect(m.basMain).toBeGreaterThanOrEqual(m.hauteurFenetre - 1);
        if (v.sansDefilement) expect(m.scrollHeight).toBeLessThanOrEqual(m.clientHeight);
        const titre = await rect(page, 'titre');
        expect(Math.abs(titre.x + titre.width / 2 - m.centreMain)).toBeLessThanOrEqual(1);
        expect(Math.abs(m.taillePoliceTitre - attendue(v.width))).toBeLessThanOrEqual(0.1);
      });
    }

    test('R.4 CA5 - en paysage 640 x 360 la page peut défiler et les sapins restent sous l\'accroche', async ({ page }) => {
      await page.setViewportSize({ width: 640, height: 360 });
      await chargerAccueil(page);
      const racine = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
      expect(racine.sw).toBeLessThanOrEqual(racine.cw);
      const accroche = await rect(page, 'accroche');
      expect(accroche.bas).toBeLessThanOrEqual((await basMain(page)) - 96);
    });

    test('R.4 CA6 - à 320 px avec le texte à 200 % rien ne déborde et les sapins restent sous l\'accroche', async ({ page }) => {
      await page.setViewportSize({ width: 320, height: 640 });
      await page.goto('/');
      await page.addStyleTag({ content: 'html{font-size:32px}' });
      await expect(page.getByTestId('accroche')).toBeVisible();
      await page.evaluate(() => document.fonts.ready.then(() => undefined));

      const m = await page.evaluate(() => {
        const t = document.querySelector('[data-testid="titre"]') as HTMLElement;
        const a = document.querySelector('[data-testid="accroche"]') as HTMLElement;
        const r = (e: HTMLElement) => e.getBoundingClientRect();
        return {
          policeTitre: parseFloat(getComputedStyle(t).fontSize),
          scrollWidthPage: document.documentElement.scrollWidth,
          titre: { gauche: r(t).left, droite: r(t).right, debordement: t.scrollWidth <= t.clientWidth },
          accroche: { gauche: r(a).left, droite: r(a).right, debordement: a.scrollWidth <= a.clientWidth, bas: r(a).bottom },
          enroulement: getComputedStyle(t).overflowWrap,
          basMain: document.querySelector('main')!.getBoundingClientRect().bottom,
        };
      });
      expect(m.policeTitre).toBe(64);
      expect(m.scrollWidthPage).toBeLessThanOrEqual(320);
      expect(m.titre.gauche).toBeGreaterThanOrEqual(0);
      expect(m.titre.droite).toBeLessThanOrEqual(320);
      expect(m.titre.debordement).toBe(true);
      expect(m.accroche.gauche).toBeGreaterThanOrEqual(0);
      expect(m.accroche.droite).toBeLessThanOrEqual(320);
      expect(m.accroche.debordement).toBe(true);
      expect(m.enroulement).toBe('anywhere');
      expect(m.accroche.bas).toBeLessThanOrEqual(m.basMain - 192);
    });

    test('R.4 CA6 - à 1280 px avec le texte à 200 % rien ne déborde et les sapins restent sous l\'accroche', async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 800 });
      await page.goto('/');
      await page.addStyleTag({ content: 'html{font-size:32px}' });
      await expect(page.getByTestId('accroche')).toBeVisible();
      await page.evaluate(() => document.fonts.ready.then(() => undefined));
      const racine = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
      expect(racine.sw).toBeLessThanOrEqual(racine.cw);
      const accroche = await rect(page, 'accroche');
      expect(accroche.bas).toBeLessThanOrEqual((await basMain(page)) - 192);
    });
  });

  test('R.4 CA7 - aucune animation, décor masqué en contraste forcé, thème clair', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await chargerAccueil(page);

    const lire = () =>
      page.evaluate(() =>
        ['main', '[data-testid="titre"]', '[data-testid="accroche"]'].map((s) => {
          const cs = getComputedStyle(document.querySelector(s)!);
          return { selecteur: s, transition: cs.transitionDuration, animation: cs.animationName };
        }),
      );
    for (const emulation of [{ reducedMotion: 'reduce' as const }, { reducedMotion: 'no-preference' as const }]) {
      await page.emulateMedia(emulation);
      for (const m of await lire()) {
        expect(m.transition, `${emulation.reducedMotion} ${m.selecteur}`).toBe('0s');
        expect(m.animation, `${emulation.reducedMotion} ${m.selecteur}`).toBe('none');
      }
      expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
    }

    await page.emulateMedia({ forcedColors: 'none', reducedMotion: 'no-preference' });
    expect(await page.locator('main').evaluate((e) => getComputedStyle(e).backgroundImage)).not.toBe('none');
    await page.emulateMedia({ forcedColors: 'active' });
    await expect.poll(() => page.locator('main').evaluate((e) => getComputedStyle(e).backgroundImage)).toBe('none');
    await expect(page.getByTestId('titre')).toBeVisible();
    await expect(page.getByTestId('accroche')).toBeVisible();
    for (const id of ['titre', 'accroche']) {
      const r = await rect(page, id);
      expect(r.x).toBeGreaterThanOrEqual(0);
      expect(r.droite).toBeLessThanOrEqual(1280);
    }
    await page.emulateMedia({ forcedColors: 'none' });
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme)).toBe('light');
  });

  for (const mode of ['abort', 'fulfill 503'] as const) {
    test(`R.4 CA8 - API injoignable (${mode}) : l'accueil s'affiche à l'identique, sans message`, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 800 });
      const requetes: string[] = [];
      const erreurs: Error[] = [];
      page.on('request', (r) => requetes.push(r.url()));
      page.on('pageerror', (e) => erreurs.push(e));
      await page.route('**/api/**', (route) => (mode === 'abort' ? route.abort() : route.fulfill({ status: 503, body: '' })));

      await chargerAccueil(page);
      await expect(page.getByTestId('titre')).toHaveText(TITRE);
      await expect(page.getByTestId('accroche')).toHaveText(ACCROCHE);
      const image = await page.locator('main').evaluate((e) => getComputedStyle(e).backgroundImage);
      expect(extraireSvg(image).svgs).toHaveLength(2);
      await expect(page.locator('main [role="alert"], main [role="status"]')).toHaveCount(0);
      expect(requetes.filter((u) => u.includes('/api/sante'))).toEqual([]);
      expect(erreurs.map((e) => e.message)).toEqual([]);
    });
  }
});
