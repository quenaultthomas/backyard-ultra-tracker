import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import jsQR from 'jsqr';
import { PNG } from 'pngjs';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique } from './aide-connexion';
import {
  connecterParApi,
  courseDeReference,
  creerCourseParApi,
  dateAffichee,
  dateDansJours,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
} from './aide-admin';
import {
  connecterCoureur,
  ligneCoureur,
  lireJetonQrEnBase,
  placerStatutCourseEnBase,
  placerStatutInscriptionEnBase,
  sinscrireParApi,
} from './aide-coureur';
import { ouvrirMenuCompte } from './aide-entete';
import {
  AIDES_PAGE,
  ECRANS,
  ETATS,
  RGB_PALETTE,
  RGB_TRANSPARENT,
  ouvrirScene,
  preparerJeuReference,
  type EcartCouleur,
  type MesureContraste,
  type MesuresResponsive,
} from './aide-theme';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

// Marge pour un démarrage à froid de la stack (premières requêtes de l'API lentes) ; les tests qui demandent plus l'écrasent.
test.beforeEach(({}, testInfo) => {
  testInfo.setTimeout(60_000);
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const AGRANDIR = 'html{font-size:32px}';
const FORET = 'rgb(20, 53, 42)';
const CREME = 'rgb(247, 241, 227)';
const SURFACE = 'rgb(255, 252, 245)';
const SABLE = 'rgb(239, 230, 208)';
const TRAIT = 'rgb(217, 207, 182)';
const FOCUS = 'rgb(168, 67, 0)';
const NOM_100_W = 'W'.repeat(100);
const NOM_100_ESPACES = 'Course aux mots courts '.repeat(5).slice(0, 100);

/* ------------------------------------------------------------------ préparation par l'API */

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

/** Nom de Course sans la suite « qr » (voir `inscription-course.spec.ts`, aucune image dont l'alt contient QR). */
const nomSansQr = (prefixe: string): string => nomCourseUnique(prefixe).replace(/qr/gi, 'q9');

/** Jour lointain (en jours depuis aujourd'hui) pour que les Courses du test arrivent en tête, sans croiser celles des autres tests. */
const joursLointains = (): number => 1700 + Math.floor(Math.random() * 80);

function pngLarge(largeur = 200, hauteur = 100): Buffer {
  const png = new PNG({ width: largeur, height: hauteur });
  for (let i = 0; i < largeur * hauteur; i++) {
    png.data[i * 4] = 200;
    png.data[i * 4 + 1] = 60;
    png.data[i * 4 + 2] = 20;
    png.data[i * 4 + 3] = 255;
  }
  return PNG.sync.write(png);
}

interface CourseCreee {
  id: string;
  nom: string;
  date: string;
}

async function creerCoureur(playwright: PlaywrightLib, prefixe = 'alice'): Promise<string> {
  const pseudo = pseudoUnique(prefixe);
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  return pseudo;
}

async function creerCourse(
  playwright: PlaywrightLib,
  options: { prefixe?: string; jours?: number; logo?: boolean; surcharge?: Partial<Parameters<typeof courseDeReference>[0]> } = {},
): Promise<CourseCreee> {
  const date = dateDansJours(options.jours ?? joursLointains());
  const nom = nomSansQr(options.prefixe ?? 'R8a');
  const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date, ...options.surcharge })));
  const id = course['id'] as string;
  if (options.logo) {
    const contenu = pngLarge();
    await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, id, { nom: 'large.png', type: 'image/png', contenu }));
  }
  return { id, nom, date };
}

async function sinscrire(playwright: PlaywrightLib, pseudo: string, courseId: string) {
  return avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, courseId));
}

/* ------------------------------------------------------------------ navigation et mesures */

async function ouvrir(page: Page, pseudo: string, chemin: string, largeur = 1280, hauteur = 800, grand = false): Promise<void> {
  await page.setViewportSize({ width: largeur, height: hauteur });
  await page.context().clearCookies();
  await connecterParApi(page.request, pseudo, MOT_DE_PASSE);
  await page.goto(chemin);
  await attendreEcran(page, chemin);
  if (grand) await page.addStyleTag({ content: AGRANDIR });
}

async function attendreEcran(page: Page, chemin: string): Promise<void> {
  await expect(page.getByTestId(chemin === '/coureur' ? 'coureur-titre' : 'inscriptions-titre')).toBeVisible();
  await expect(page.getByText('Chargement…')).toHaveCount(0);
}

const ligneIns = (page: Page, nom: string): Locator =>
  page.getByTestId('inscriptions-ligne').filter({ has: page.getByTestId('inscriptions-course-nom').getByText(nom, { exact: true }) });

const carteDePage = (page: Page, nom: 'Courses ouvertes' | 'Mes inscriptions'): Locator => page.getByRole('region', { name: nom });

function proche(obtenu: number, attendu: number, tolerance: number, message: string): void {
  expect(Math.abs(obtenu - attendu), `${message} : obtenu ${obtenu}, attendu ${attendu} (+/- ${tolerance})`).toBeLessThanOrEqual(tolerance);
}

async function css(element: Locator, propriete: string, pseudo?: string): Promise<string> {
  return element.evaluate((el, [p, ps]) => (getComputedStyle(el, ps || null) as unknown as Record<string, string>)[p as string], [propriete, pseudo ?? '']);
}

/** Capture d'écran du QR puis décodage par une vraie bibliothèque de lecture de QR. */
async function decoderQr(page: Page, ligne: Locator): Promise<string> {
  const qr = ligne.getByTestId('inscriptions-qr');
  await qr.scrollIntoViewIfNeeded();
  await expect(qr).toBeVisible();
  const png = PNG.sync.read(await qr.screenshot());
  const lu = jsQR(new Uint8ClampedArray(png.data), png.width, png.height);
  expect(lu, 'le QR doit être décodable').not.toBeNull();
  return lu!.data;
}

interface Boite {
  nom: string;
  left: number;
  top: number;
  width: number;
  height: number;
  bottom: number;
  bouton: number | null;
}

/** Boîtes des cartes d'une liste (dans l'ordre du DOM), éventuellement filtrées sur des noms de Course. */
async function boites(page: Page, idLigne: string, idNom: string, noms: string[], idBouton?: string): Promise<Boite[]> {
  return page.evaluate(
    ([ligne, nomId, filtre, boutonId]) => {
      const sortie: Boite[] = [];
      for (const li of Array.from(document.querySelectorAll(`[data-testid="${ligne}"]`))) {
        const nom = li.querySelector(`[data-testid="${nomId}"]`)?.textContent?.trim() ?? '';
        if (!(filtre as string[]).includes(nom)) continue;
        const r = li.getBoundingClientRect();
        const b = boutonId ? li.querySelector(`[data-testid="${boutonId}"]`)?.getBoundingClientRect() : undefined;
        sortie.push({ nom, left: r.left, top: r.top + window.scrollY, width: r.width, height: r.height, bottom: r.bottom + window.scrollY, bouton: b ? b.bottom + window.scrollY : null });
      }
      return sortie;
    },
    [idLigne, idNom, noms, idBouton ?? ''] as [string, string, string[], string],
  );
}

const colonnes = (cartes: Boite[]): number => new Set(cartes.map((c) => Math.round(c.left))).size;

async function sansDefilementHorizontal(page: Page): Promise<void> {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  expect(m.s, 'défilement horizontal').toBeLessThanOrEqual(m.c);
}

/** Descendants visibles qui sortent de leur carte (idLigne), pour les cartes dont le nom est dans `noms`. */
async function debordements(page: Page, idLigne: string, idNom: string, noms: string[]): Promise<string[]> {
  return page.evaluate(
    ([ligne, nomId, filtre]) => {
      const sortie: string[] = [];
      for (const li of Array.from(document.querySelectorAll(`[data-testid="${ligne}"]`))) {
        const nom = li.querySelector(`[data-testid="${nomId}"]`)?.textContent?.trim() ?? '';
        if (!(filtre as string[]).includes(nom)) continue;
        const c = li.getBoundingClientRect();
        for (const el of Array.from(li.querySelectorAll('*'))) {
          const r = el.getBoundingClientRect();
          if (r.width === 0 && r.height === 0) continue;
          if (r.left < c.left - 0.5 || r.right > c.right + 0.5) {
            sortie.push(`${el.getAttribute('data-testid') ?? el.tagName} [${r.left.toFixed(1)}; ${r.right.toFixed(1)}] hors de la carte [${c.left.toFixed(1)}; ${c.right.toFixed(1)}]`);
          }
        }
      }
      return sortie;
    },
    [idLigne, idNom, noms] as [string, string, string[]],
  );
}

/** Réécrit les noms des Courses données (par identifiant) dans les réponses de la liste `chemin` : aucune donnée dangereuse en base. */
async function reecrireNoms(page: Page, glob: string, champId: string, champNom: string, noms: Record<string, string>, dossard?: number): Promise<void> {
  await page.route(glob, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    const reponse = await route.fetch();
    const liste = (await reponse.json()) as Array<Record<string, unknown>>;
    for (const element of liste) {
      const nom = noms[element[champId] as string];
      if (nom) {
        element[champNom] = nom;
        if (dossard !== undefined) element['dossard'] = dossard;
      }
    }
    await route.fulfill({ response: reponse, json: liste });
  });
}

async function simuler409(page: Page, courseId: string, code: string): Promise<void> {
  await page.route(
    (url) => url.pathname === `/api/coureur/courses/${courseId}/inscriptions`,
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await route.fulfill({
        status: 409,
        contentType: 'application/problem+json',
        body: JSON.stringify({ type: 'about:blank', title: 'Conflit', status: 409, detail: 'Conflit', code }),
      });
    },
  );
}

/* ------------------------------------------------------------------ CA1 à CA4 : Espace coureur */

test.describe('Espace coureur en cartes', () => {
  test('CA1 - chaque Course ouverte est une carte avec date, nom, paramètres, limites et bouton principal', async ({ page, playwright }) => {
    const alice = await creerCoureur(playwright);
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base + 1, logo: true });
    const b = await creerCourse(playwright, {
      jours: base,
      surcharge: { distanceBoucleMetres: 400, dureeBoucleMinutes: 1, denivelePositifBoucleMetres: 0, nombreMaxParticipants: 10, nombreMaxBoucles: 5 },
    });
    await ouvrir(page, alice, '/coureur');
    const ligneA = ligneCoureur(page, a.nom);
    const ligneB = ligneCoureur(page, b.nom);
    await expect(ligneA).toBeVisible();
    await expect(ligneB).toBeVisible();

    expect(await page.getByTestId('coureur-liste-courses').evaluate((el) => el.tagName)).toBe('UL');
    expect(await ligneA.evaluate((el) => el.tagName)).toBe('LI');

    const nom = ligneA.getByTestId('coureur-course-nom');
    expect(await nom.evaluate((el) => el.tagName)).toBe('H2');
    await expect(nom).toHaveText(a.nom);
    await expect(nom).toHaveAttribute('id', `coureur-nom-${a.id}`);

    const date = ligneA.getByTestId('coureur-course-date');
    await expect(date).toHaveText(dateAffichee(a.date));
    await expect(date).toHaveAttribute('datetime', a.date);
    expect(await css(date, 'fontSize')).toBe('20px');
    expect(await css(date, 'fontWeight')).toBe('700');
    expect(await css(date, 'color')).toBe(FORET);

    const parametres = ligneA.getByTestId('coureur-course-parametres');
    const limites = ligneA.getByTestId('coureur-course-limites');
    await expect(parametres).toHaveText('6706 m · 60 min · 120 m D+');
    await expect(limites).toHaveText('24 boucles max · 50 participants max');
    const masques: string[] = [];
    for (const el of [parametres, limites]) {
      expect(await css(el, 'width', '::before')).toBe('24px');
      expect(await css(el, 'height', '::before')).toBe('24px');
      const masque = (await css(el, 'maskImage', '::before')) || (await css(el, 'webkitMaskImage', '::before'));
      expect(masque).toContain('data:image/svg+xml');
      masques.push(masque);
    }
    expect(masques[0]).not.toBe(masques[1]);

    const bouton = ligneA.getByTestId('coureur-bouton-sinscrire');
    await expect(bouton).toHaveText("S'inscrire");
    await expect(bouton).toHaveClass(/\bbouton\b/);
    expect(await css(bouton, 'backgroundColor')).toBe(FORET);
    expect((await bouton.boundingBox())!.height).toBeGreaterThanOrEqual(44);

    await expect(ligneB.getByTestId('coureur-course-parametres')).toHaveText('400 m · 1 min · 0 m D+');
    for (const id of ['coureur-dossard', 'coureur-statut-inscription', 'coureur-course-complete']) {
      await expect(ligneB.getByTestId(id)).toHaveCount(0);
    }

    // ordre de l'API
    const reponse = await page.request.get('/api/coureur/courses');
    const ordreApi = ((await reponse.json()) as Array<{ nom: string }>).map((c) => c.nom).filter((n) => n === a.nom || n === b.nom);
    const ordreDom = (await page.getByTestId('coureur-course-nom').allTextContents()).map((t) => t.trim()).filter((n) => n === a.nom || n === b.nom);
    expect(ordreDom).toEqual(ordreApi);

    // la carte
    expect(await css(ligneA, 'backgroundColor')).toBe(SURFACE);
    expect(await css(ligneA, 'borderTopWidth')).toBe('1px');
    expect(await css(ligneA, 'borderTopColor')).toBe(TRAIT);
    expect(await css(ligneA, 'borderTopLeftRadius')).toBe('12px');
    expect(await css(ligneA, 'boxShadow')).not.toBe('none');
    expect(await css(ligneA, 'paddingTop')).toBe('16px');
    expect(await ligneA.evaluate((el) => el.classList.contains('carte'))).toBe(false);
  });

  test('CA2 - le logo est contenu sans déformation dans un visuel 16/9, sinon un visuel de substitution décoratif', async ({ page, playwright }) => {
    const alice = await creerCoureur(playwright);
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base + 1, logo: true });
    const b = await creerCourse(playwright, { jours: base });
    await sinscrire(playwright, alice, a.id);
    await sinscrire(playwright, alice, b.id);
    await ouvrir(page, alice, '/coureur');
    const ligneA = ligneCoureur(page, a.nom);
    const ligneB = ligneCoureur(page, b.nom);

    const logo = ligneA.getByTestId('coureur-course-logo');
    await expect(logo).toBeVisible();
    await expect(logo).toHaveAttribute('alt', `Logo de ${a.nom}`);
    expect(await logo.getAttribute('src')).toMatch(new RegExp(`^/api/courses/${a.id}/logo`));
    const image = await logo.evaluate((el: HTMLImageElement) => ({ w: el.naturalWidth, h: el.naturalHeight }));
    expect(image.w).toBeGreaterThan(0);
    proche(image.w / image.h, 2, 0.001, 'rapport naturel');
    expect(await css(logo, 'objectFit')).toBe('contain');
    const zone = await logo.evaluate((el) => {
      const p = el.parentElement!;
      const r = p.getBoundingClientRect();
      const i = el.getBoundingClientRect();
      return { w: r.width, h: r.height, fond: getComputedStyle(p).backgroundColor, dedans: i.left >= r.left - 0.5 && i.right <= r.right + 0.5 && i.top >= r.top - 0.5 && i.bottom <= r.bottom + 0.5 };
    });
    proche(zone.w / zone.h, 16 / 9, 0.02, 'rapport de la zone du logo');
    expect(zone.fond).toBe(SABLE);
    expect(zone.dedans).toBe(true);
    expect(await ligneA.locator('img').count()).toBe(1);
    await expect(ligneA.locator('svg, canvas')).toHaveCount(0);

    await expect(ligneB.getByTestId('coureur-course-logo')).toHaveCount(0);
    const substitution = ligneB.locator('.visuel-substitution');
    await expect(substitution).toHaveCount(1);
    const boite = (await substitution.boundingBox())!;
    proche(boite.width, zone.w, 1, 'largeur du visuel de substitution');
    proche(boite.height, zone.h, 1, 'hauteur du visuel de substitution');
    expect(await css(substitution, 'backgroundColor')).toBe(SABLE);
    expect(await css(substitution, 'borderTopColor')).toBe(TRAIT);
    expect(await css(substitution, 'backgroundImage')).toContain('data:image/svg+xml');
    expect(await substitution.evaluate((el) => ({ texte: el.textContent, testid: el.getAttribute('data-testid') }))).toEqual({ texte: '', testid: null });
    await expect(ligneB.locator('svg, canvas, img')).toHaveCount(0);

    // Mes inscriptions : vignettes carrées de 64 px
    await ouvrir(page, alice, '/coureur/inscriptions');
    await expect(ligneIns(page, b.nom).getByTestId('inscriptions-course-logo')).toHaveCount(0);
    const vignetteB = (await ligneIns(page, b.nom).locator('.visuel-substitution').boundingBox())!;
    proche(vignetteB.width, 64, 0.5, 'largeur de la vignette B');
    proche(vignetteB.height, 64, 0.5, 'hauteur de la vignette B');
    const logoA = (await ligneIns(page, a.nom).getByTestId('inscriptions-course-logo').boundingBox())!;
    proche(logoA.width, 64, 0.5, 'largeur du logo A');
    proche(logoA.height, 64, 0.5, 'hauteur du logo A');
  });

  test('CA3 - Course complète, inscription en un clic et refus 409 dans la carte concernée', async ({ page, playwright }) => {
    const alice = await creerCoureur(playwright);
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base + 3 });
    const c = await creerCourse(playwright, { jours: base + 2, surcharge: { nombreMaxParticipants: 2 } });
    const d = await creerCourse(playwright, { jours: base + 1 });
    await sinscrire(playwright, await creerCoureur(playwright, 'bruno'), c.id);
    await sinscrire(playwright, await creerCoureur(playwright, 'chloe'), c.id);
    await ouvrir(page, alice, '/coureur');

    const ligneC = ligneCoureur(page, c.nom);
    const complete = ligneC.getByTestId('coureur-course-complete');
    await expect(complete).toHaveText('Complète');
    await expect(complete).toHaveClass(/pastille-statut--en-cours/);
    const boutonC = ligneC.getByTestId('coureur-bouton-sinscrire');
    await expect(boutonC).toBeDisabled();
    expect(await css(boutonC, 'backgroundColor')).toBe('rgb(221, 214, 195)');
    expect(await css(boutonC, 'color')).toBe('rgb(95, 106, 98)');
    await boutonC.focus();
    expect(await boutonC.evaluate((el) => document.activeElement === el), 'un bouton désactivé n\'est pas un arrêt de Tab').toBe(false);

    const ordreAvant = (await boites(page, 'coureur-ligne-course', 'coureur-course-nom', [a.nom, c.nom, d.nom])).map((x) => x.nom);
    const ligneA = ligneCoureur(page, a.nom);
    const gaucheAvant = (await boites(page, 'coureur-ligne-course', 'coureur-course-nom', [a.nom]))[0].left;
    await ligneA.getByTestId('coureur-bouton-sinscrire').click();
    await expect(page.getByTestId('coureur-message-inscription')).toHaveText(`Vous êtes inscrit à ${a.nom} avec le dossard 1.`);
    const dossard = ligneA.getByTestId('coureur-dossard');
    await expect(dossard).toHaveText('Dossard 1');
    expect(await css(dossard, 'borderTopWidth')).toBe('2px');
    expect(await css(dossard, 'borderTopColor')).toBe(FORET);
    expect(await css(dossard, 'fontSize')).toBe('20px');
    expect(await css(dossard, 'fontWeight')).toBe('700');
    const statut = ligneA.getByTestId('coureur-statut-inscription');
    await expect(statut).toHaveText('Inscrit');
    await expect(statut).toHaveClass(/pastille-statut/);
    await expect(statut).toHaveClass(/pastille-statut--en-course/);
    await expect(ligneA.getByTestId('coureur-bouton-sinscrire')).toHaveCount(0);
    const ordreApres = (await boites(page, 'coureur-ligne-course', 'coureur-course-nom', [a.nom, c.nom, d.nom])).map((x) => x.nom);
    expect(ordreApres).toEqual(ordreAvant);
    proche((await boites(page, 'coureur-ligne-course', 'coureur-course-nom', [a.nom]))[0].left, gaucheAvant, 1, 'colonne de la carte A');

    await simuler409(page, d.id, 'COURSE_COMPLETE');
    const ligneD = ligneCoureur(page, d.nom);
    await ligneD.getByTestId('coureur-bouton-sinscrire').click();
    await expect(ligneD.getByTestId('coureur-erreur')).toHaveText('Cette course est complète.');
    await expect(ligneA.getByTestId('coureur-erreur')).toHaveCount(0);
    await expect(ligneC.getByTestId('coureur-erreur')).toHaveCount(0);
    await expect(page.getByTestId('coureur-erreur')).toHaveCount(1);
  });

  test("CA4 - la liste vide affiche un bloc sable en pointillés, sans débordement, et l'erreur 500 reste distincte", async ({ page, playwright }) => {
    test.setTimeout(90_000);
    const alice = await creerCoureur(playwright);
    let statut = 200;
    await page.route('**/api/coureur/courses', async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill(statut === 200 ? { status: 200, contentType: 'application/json', body: '[]' } : { status: statut, contentType: 'application/problem+json', body: '{}' });
    });
    await ouvrir(page, alice, '/coureur');
    const verifier = async (): Promise<void> => {
      const vide = page.getByTestId('coureur-vide');
      await expect(vide).toBeVisible();
      await expect(vide).toHaveText('Aucune course ouverte aux inscriptions pour le moment.');
      await expect(page.getByTestId('coureur-liste-courses')).toHaveCount(0);
      const aide = page.getByText('Revenez plus tard : les prochaines courses apparaîtront ici.');
      await expect(aide).toBeVisible();
      await expect(vide.getByText('Revenez plus tard')).toHaveCount(0);
      const bloc = page.locator('.etat-vide');
      expect(await css(bloc, 'backgroundColor')).toBe(SABLE);
      expect(await css(bloc, 'borderTopStyle')).toBe('dashed');
      expect(await css(bloc, 'borderTopWidth')).toBe('1px');
      expect(await css(bloc, 'borderTopColor')).toBe(TRAIT);
      const visuel = (await bloc.locator('.visuel-substitution').boundingBox())!;
      proche(visuel.width / visuel.height, 16 / 9, 0.02, 'rapport du visuel');
      expect(visuel.width).toBeLessThanOrEqual(192.5);
      const sorties = await bloc.evaluate((el) => {
        const b = el.getBoundingClientRect();
        const carte = el.closest('section')!.getBoundingClientRect();
        const dehors = Array.from(el.querySelectorAll('*')).filter((e) => {
          const r = e.getBoundingClientRect();
          return r.left < b.left - 0.5 || r.right > b.right + 0.5;
        }).length;
        return { dehors, dansCarte: b.left >= carte.left - 0.5 && b.right <= carte.right + 0.5 };
      });
      expect(sorties).toEqual({ dehors: 0, dansCarte: true });
      await sansDefilementHorizontal(page);
    };
    await verifier();
    await page.setViewportSize({ width: 360, height: 640 });
    await verifier();
    await page.setViewportSize({ width: 320, height: 640 });
    await page.addStyleTag({ content: AGRANDIR });
    await verifier();

    statut = 500;
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.reload();
    await expect(page.getByTestId('coureur-erreur-chargement')).toBeVisible();
    await expect(page.getByTestId('coureur-vide')).toHaveCount(0);
    await expect(page.locator('.etat-vide')).toHaveCount(0);
  });
});

/* ------------------------------------------------------------------ CA5 à CA8 : Mes inscriptions */

test.describe('Mes inscriptions façon dossard', () => {
  test('CA5 - chaque Inscription est un dossard : bande du numéro, Course, ligne de découpe, QR et aide', async ({ page, playwright }) => {
    const alice = await creerCoureur(playwright);
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base, logo: true });
    const b = await creerCourse(playwright, { jours: base + 10 });
    const insA = await sinscrire(playwright, alice, a.id);
    await sinscrire(playwright, alice, b.id);
    await ouvrir(page, alice, '/coureur/inscriptions');

    const liste = page.getByTestId('inscriptions-liste');
    expect(await liste.evaluate((el) => el.tagName)).toBe('UL');
    const lignes = page.getByTestId('inscriptions-ligne');
    await expect(lignes).toHaveCount(2);
    await expect(lignes.nth(0).getByTestId('inscriptions-course-nom')).toHaveText(b.nom);
    await expect(lignes.nth(1).getByTestId('inscriptions-course-nom')).toHaveText(a.nom);

    for (const ligne of [lignes.nth(0), lignes.nth(1)]) {
      expect(await ligne.evaluate((el) => el.tagName)).toBe('LI');
      expect(await ligne.evaluate((el) => ({ dossard: el.classList.contains('dossard-carte'), carte: el.classList.contains('carte') }))).toEqual({ dossard: true, carte: false });
      expect(await css(ligne, 'borderTopWidth')).toBe('2px');
      expect(await css(ligne, 'borderTopStyle')).toBe('solid');
      expect(await css(ligne, 'borderTopColor')).toBe(FORET);
      const ordre = await ligne.evaluate((el) =>
        Array.from(el.children).map((e) => (e.tagName === 'APP-DESINSCRIPTION' ? 'desinscription' : e.classList.contains('dossard-carte__numero') ? 'numero' : e.classList.contains('course') ? 'course' : e.classList.contains('dossard-carte__qr') ? 'qr' : e.tagName)),
      );
      expect(ordre).toEqual(['numero', 'course', 'qr', 'desinscription']);

      const dossard = ligne.getByTestId('inscriptions-dossard');
      await expect(dossard).toHaveText('Dossard 1');
      expect(await dossard.evaluate((el) => getComputedStyle(el.parentElement!).backgroundColor)).toBe(FORET);
      expect(await css(dossard, 'color')).toBe(CREME);
      expect(await css(dossard, 'fontWeight')).toBe('700');
      const taille = parseFloat(await css(dossard, 'fontSize'));
      expect(taille).toBeGreaterThanOrEqual(28);
      expect(taille).toBeLessThanOrEqual(42);
      const statut = ligne.getByTestId('inscriptions-statut');
      await expect(statut).toHaveText('En course');
      await expect(statut).toHaveClass(/pastille-statut--en-course/);
      expect(await statut.evaluate((el) => el.parentElement!.contains(el) && el.parentElement === (el.parentElement!.querySelector('[data-testid="inscriptions-dossard"]') as HTMLElement).parentElement)).toBe(true);

      const nom = ligne.getByTestId('inscriptions-course-nom');
      expect(await nom.evaluate((el) => el.tagName)).toBe('H2');
      await expect(ligne.getByTestId('inscriptions-course-statut')).toHaveText('En préparation');

      const zoneQr = ligne.getByTestId('inscriptions-aide-qr').locator('..');
      expect(await css(zoneQr, 'borderTopStyle')).toBe('dashed');
      expect(await css(zoneQr, 'borderTopWidth')).toBe('2px');
      expect(await css(zoneQr, 'borderTopColor')).toBe(TRAIT);
      await expect(ligne.getByTestId('inscriptions-aide-qr')).toHaveText('Présentez ce QR code au bénévole à chaque passage.');
      expect(
        await zoneQr.evaluate((el) => {
          const qr = el.querySelector('[data-testid="inscriptions-qr"]');
          const aide = el.querySelector('[data-testid="inscriptions-aide-qr"]');
          return !!qr && !!aide && !!(qr.compareDocumentPosition(aide) & Node.DOCUMENT_POSITION_FOLLOWING);
        }),
      ).toBe(true);
    }
    await expect(lignes.nth(1).getByTestId('inscriptions-course-date')).toHaveText(dateAffichee(a.date));

    // Statuts posés en base
    const verifierPastille = async (libelle: string, fond: string, texte: string): Promise<void> => {
      const statut = ligneIns(page, a.nom).getByTestId('inscriptions-statut');
      await expect(statut).toHaveText(libelle);
      expect(await css(statut, 'backgroundColor')).toBe(fond);
      expect(await css(statut, 'color')).toBe(texte);
      await expect(ligneIns(page, a.nom).getByTestId('inscriptions-qr')).toBeVisible();
    };
    placerStatutInscriptionEnBase(insA.id, 'ABANDON');
    await page.reload();
    await verifierPastille('Abandon', 'rgb(251, 233, 228)', 'rgb(155, 28, 28)');
    placerStatutInscriptionEnBase(insA.id, 'VAINQUEUR');
    await page.reload();
    await verifierPastille('Vainqueur', 'rgb(242, 140, 40)', 'rgb(27, 42, 34)');
  });

  test('CA6 - le QR reste noir sur blanc, carré, entier et décodé en jeton dans toutes les configurations', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const a = await creerCourse(playwright, { logo: true });
    const insA = await sinscrire(playwright, alice, a.id);
    const jeton = lireJetonQrEnBase(insA.id);
    expect(jeton).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const requetes: string[] = [];
    page.on('request', (r) => requetes.push(r.url()));

    const configs: Array<{ nom: string; l: number; h: number; grand?: boolean; forcees?: boolean; exact?: number; min: number }> = [
      { nom: '1280 x 800', l: 1280, h: 800, exact: 240, min: 240 },
      { nom: '360 x 640', l: 360, h: 640, exact: 240, min: 240 },
      { nom: '320 x 640', l: 320, h: 640, min: 200 },
      { nom: '320 x 640, texte à 200 %', l: 320, h: 640, grand: true, min: 160 },
      { nom: '640 x 360', l: 640, h: 360, min: 200 },
      { nom: '1280 x 800, contraste forcé', l: 1280, h: 800, forcees: true, exact: 240, min: 240 },
    ];
    for (const c of configs) {
      await page.emulateMedia({ forcedColors: c.forcees ? 'active' : 'none' });
      await ouvrir(page, alice, '/coureur/inscriptions', c.l, c.h, c.grand);
      const ligne = ligneIns(page, a.nom);
      const qr = ligne.getByTestId('inscriptions-qr');
      await qr.scrollIntoViewIfNeeded();
      expect(await decoderQr(page, ligne), `décodage ${c.nom}`).toBe(jeton);
      const boite = (await qr.boundingBox())!;
      expect(boite.width, `largeur ${c.nom}`).toBeGreaterThanOrEqual(c.min - 1);
      proche(boite.width, boite.height, 1, `QR carré ${c.nom}`);
      if (c.exact) proche(boite.width, c.exact, 1, `taille ${c.nom}`);
      const carte = (await ligne.boundingBox())!;
      expect(boite.x).toBeGreaterThanOrEqual(carte.x - 0.5);
      expect(boite.x + boite.width).toBeLessThanOrEqual(carte.x + carte.width + 0.5);
      expect(await css(qr, 'forcedColorAdjust')).toBe('none');
    }

    await page.emulateMedia({ forcedColors: 'none' });
    await ouvrir(page, alice, '/coureur/inscriptions');
    const qr = ligneIns(page, a.nom).getByTestId('inscriptions-qr');
    const dessin = await qr.evaluate((svg) => {
      const hote = svg.parentElement!;
      const rect = svg.querySelector('rect')!.getBoundingClientRect();
      const boite = svg.getBoundingClientRect();
      const neutre = (el: Element) => {
        const cs = getComputedStyle(el);
        return cs.paddingTop === '0px' && cs.borderTopWidth === '0px' && cs.borderTopLeftRadius === '0px' && cs.opacity === '1' && cs.filter === 'none' && cs.transform === 'none';
      };
      return {
        remplissages: Array.from(new Set(Array.from(svg.querySelectorAll('[fill]')).map((e) => e.getAttribute('fill')))).sort(),
        fondPlein: Math.abs(rect.width - boite.width) <= 0.5 && Math.abs(rect.height - boite.height) <= 0.5,
        neutres: neutre(svg) && neutre(hote),
        aria: svg.getAttribute('aria-label'),
        titre: svg.getAttribute('title'),
        elementsTitre: svg.querySelectorAll('title').length,
        html: document.documentElement.outerHTML,
      };
    });
    expect(dessin.remplissages).toEqual(['#000', '#fff']);
    expect(dessin.fondPlein).toBe(true);
    expect(dessin.neutres).toBe(true);
    expect(dessin.aria).toBe("QR code de l'inscription, dossard 1");
    expect(dessin.titre).toBeNull();
    expect(dessin.elementsTitre).toBe(0);
    expect(dessin.html).not.toContain(jeton);
    expect(await page.content()).not.toContain(jeton);
    const images = requetes.filter((u) => /qr/i.test(new URL(u).pathname) || /\.(png|svg)$/i.test(new URL(u).pathname));
    expect(images, 'aucune requête de QR').toEqual([]);

    // jeton vide : pas de QR, message d'erreur dans la zone
    await page.route('**/api/coureur/inscriptions', async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const reponse = await route.fetch();
      const liste = (await reponse.json()) as Array<Record<string, unknown>>;
      liste.forEach((i) => (i['jetonQr'] = ''));
      await route.fulfill({ response: reponse, json: liste });
    });
    await page.reload();
    const ligne = ligneIns(page, a.nom);
    await expect(ligne).toBeVisible();
    await expect(ligne.getByTestId('inscriptions-qr')).toHaveCount(0);
    await expect(ligne.getByTestId('inscriptions-erreur-qr')).toBeVisible();
  });

  test('CA7 - la désinscription se confirme dans la carte concernée sans déplacer les cartes voisines', async ({ page, playwright }) => {
    test.setTimeout(90_000);
    const alice = await creerCoureur(playwright);
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base + 2 });
    const b = await creerCourse(playwright, { jours: base + 1 });
    const e = await creerCourse(playwright, { jours: base });
    await sinscrire(playwright, alice, a.id);
    await sinscrire(playwright, alice, b.id);
    await sinscrire(playwright, alice, e.id);
    placerStatutCourseEnBase(e.id, 'EN_COURS');

    // Carte sans action : pas de bouton ni de zone vide (une colonne, la carte n'est pas étirée par sa voisine)
    await ouvrir(page, alice, '/coureur/inscriptions', 360, 640);
    const ligneE = ligneIns(page, e.nom);
    await expect(ligneE.getByTestId('inscriptions-bouton-desinscrire')).toHaveCount(0);
    const mesure = await ligneE.evaluate((li) => {
      const r = li.getBoundingClientRect();
      const aide = li.querySelector('[data-testid="inscriptions-aide-qr"]')!.getBoundingClientRect();
      const cs = getComputedStyle(li);
      return { bas: r.bottom, aideBas: aide.bottom, marge: parseFloat(cs.paddingBottom) + parseFloat(cs.borderBottomWidth) };
    });
    proche(mesure.aideBas + mesure.marge, mesure.bas, 1, 'bas de la carte sans zone vide');

    await ouvrir(page, alice, '/coureur/inscriptions', 1280, 800);
    const ligneA = ligneIns(page, a.nom);
    const ligneB = ligneIns(page, b.nom);
    const bouton = ligneA.getByTestId('inscriptions-bouton-desinscrire');
    const dimensions = await ligneA.evaluate((li) => {
      const cs = getComputedStyle(li);
      const r = li.getBoundingClientRect();
      return { utile: r.width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight) - parseFloat(cs.borderLeftWidth) - parseFloat(cs.borderRightWidth) };
    });
    proche((await bouton.boundingBox())!.width, dimensions.utile, 1, 'largeur du bouton');

    const qrB = ligneB.getByTestId('inscriptions-qr');
    const positionAbsolue = (): Promise<{ x: number; y: number }> => qrB.evaluate((el) => ({ x: el.getBoundingClientRect().x + window.scrollX, y: el.getBoundingClientRect().y + window.scrollY }));
    const avant = await positionAbsolue();
    await bouton.click();
    const confirmation = ligneA.getByTestId('inscriptions-confirmation-desinscription');
    await expect(confirmation).toBeVisible();
    const boiteConfirmation = (await confirmation.boundingBox())!;
    const boiteCarte = (await ligneA.boundingBox())!;
    expect(boiteConfirmation.x).toBeGreaterThanOrEqual(boiteCarte.x - 0.5);
    expect(boiteConfirmation.y).toBeGreaterThanOrEqual(boiteCarte.y - 0.5);
    expect(boiteConfirmation.x + boiteConfirmation.width).toBeLessThanOrEqual(boiteCarte.x + boiteCarte.width + 0.5);
    expect(boiteConfirmation.y + boiteConfirmation.height).toBeLessThanOrEqual(boiteCarte.y + boiteCarte.height + 0.5);
    const apres = await positionAbsolue();
    proche(apres.x, avant.x, 1, 'QR voisin (x)');
    proche(apres.y, avant.y, 1, 'QR voisin (y)');

    await ligneA.getByTestId('inscriptions-bouton-annuler-desinscription').click();
    await expect(confirmation).toHaveCount(0);

    await bouton.click();
    await ligneA.getByTestId('inscriptions-bouton-confirmer-desinscription').click();
    await expect(ligneA).toHaveCount(0);
    await expect(page.getByTestId('inscriptions-message-desinscription')).toHaveText(`Vous êtes désinscrit de ${a.nom}.`);
    await expect(ligneB).toBeVisible();

    await ligneB.getByTestId('inscriptions-bouton-desinscrire').click();
    await ligneB.getByTestId('inscriptions-bouton-confirmer-desinscription').click();
    await expect(ligneB).toHaveCount(0);
    await expect(ligneIns(page, e.nom)).toBeVisible();
  });

  test('CA8 - sans Inscription, un bloc sable en pointillés propose de voir les courses ouvertes', async ({ page, playwright }) => {
    test.setTimeout(90_000);
    const alice = await creerCoureur(playwright);
    await page.route('**/api/coureur/inscriptions', async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });
    await ouvrir(page, alice, '/coureur/inscriptions');
    const verifier = async (): Promise<void> => {
      const vide = page.getByTestId('inscriptions-vide');
      await expect(vide).toBeVisible();
      await expect(vide).toHaveText("Vous n'êtes inscrit à aucune course.");
      await expect(page.getByTestId('inscriptions-liste')).toHaveCount(0);
      const lien = page.getByTestId('inscriptions-lien-courses-ouvertes');
      await expect(lien).toHaveText('Voir les courses ouvertes');
      await expect(lien).toHaveClass(/\bbouton\b/);
      await expect(lien).toHaveAttribute('href', '/coureur');
      expect(await css(lien, 'backgroundColor')).toBe(FORET);
      expect((await lien.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      const bloc = page.locator('.etat-vide');
      expect(await css(bloc, 'backgroundColor')).toBe(SABLE);
      expect(await css(bloc, 'borderTopStyle')).toBe('dashed');
      expect(await css(bloc, 'borderTopColor')).toBe(TRAIT);
      const visuel = (await bloc.locator('.visuel-substitution').boundingBox())!;
      proche(visuel.width / visuel.height, 16 / 9, 0.02, 'rapport du visuel');
      const dehors = await bloc.evaluate((el) => {
        const b = el.getBoundingClientRect();
        const carte = el.closest('section')!.getBoundingClientRect();
        return Array.from(el.querySelectorAll('*')).filter((e) => {
          const r = e.getBoundingClientRect();
          return r.left < b.left - 0.5 || r.right > b.right + 0.5;
        }).length + (b.left < carte.left - 0.5 || b.right > carte.right + 0.5 ? 1 : 0);
      });
      expect(dehors).toBe(0);
      await sansDefilementHorizontal(page);
    };
    await verifier();
    await page.setViewportSize({ width: 360, height: 640 });
    await verifier();
    await page.setViewportSize({ width: 320, height: 640 });
    await page.addStyleTag({ content: AGRANDIR });
    await verifier();

    await page.setViewportSize({ width: 1280, height: 800 });
    await page.getByTestId('inscriptions-lien-courses-ouvertes').click();
    await expect(page).toHaveURL(/\/coureur$/);
    await expect(page.getByTestId('coureur-titre')).toBeVisible();
  });
});

/* ------------------------------------------------------------------ CA9, CA10 : grilles et débordements */

test.describe('Grilles et largeurs', () => {
  test('CA9 - les colonnes suivent la largeur de la fenêtre sur les deux écrans', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const bob = await creerCoureur(playwright, 'bob');
    const seul = await creerCoureur(playwright, 'carol');
    const base = 1800 + Math.floor(Math.random() * 15);
    const courses: CourseCreee[] = [];
    for (let i = 0; i < 4; i++) courses.push(await creerCourse(playwright, { jours: base + i, logo: i === 0 }));
    for (const c of courses) await sinscrire(playwright, alice, c.id);
    await sinscrire(playwright, seul, courses[0].id);
    const noms = courses.map((c) => c.nom);
    const ordreAttendu = [...noms].reverse(); // date décroissante

    const mesurer = async (pseudo: string, chemin: '/coureur' | '/coureur/inscriptions', l: number, h: number) => {
      await ouvrir(page, pseudo, chemin, l, h);
      const coureur = chemin === '/coureur';
      const cartes = await boites(page, coureur ? 'coureur-ligne-course' : 'inscriptions-ligne', coureur ? 'coureur-course-nom' : 'inscriptions-course-nom', noms, coureur ? 'coureur-bouton-sinscrire' : undefined);
      expect(cartes.map((c) => c.nom), 'ordre de lecture').toEqual(ordreAttendu);
      await sansDefilementHorizontal(page);
      const region = (await carteDePage(page, coureur ? 'Courses ouvertes' : 'Mes inscriptions').boundingBox())!;
      return { cartes, region };
    };
    const rangees = (cartes: Boite[]): Boite[][] => {
      const groupes = new Map<number, Boite[]>();
      for (const c of cartes) groupes.set(Math.round(c.top / 4), [...(groupes.get(Math.round(c.top / 4)) ?? []), c]);
      return Array.from(groupes.values());
    };

    // 1280 x 800
    for (const [pseudo, chemin, largeur] of [[bob, '/coureur', 314], [alice, '/coureur/inscriptions', 314]] as const) {
      const { cartes, region } = await mesurer(pseudo, chemin, 1280, 800);
      proche(region.width, 1024, 1, `carte de la page ${chemin}`);
      expect(colonnes(cartes), `colonnes ${chemin}`).toBe(3);
      for (const c of cartes) proche(c.width, largeur, 1, `largeur de carte ${chemin}`);
      for (const rangee of rangees(cartes)) {
        for (const c of rangee) proche(c.height, rangee[0].height, 1, `hauteur dans la rangée ${chemin}`);
        if (chemin === '/coureur') for (const c of rangee) proche(c.bouton!, rangee[0].bouton!, 1, "alignement des boutons S'inscrire");
      }
    }
    // une seule Inscription
    await ouvrir(page, seul, '/coureur/inscriptions', 1280, 800);
    const unique = await boites(page, 'inscriptions-ligne', 'inscriptions-course-nom', [courses[0].nom]);
    expect(unique).toHaveLength(1);
    proche(unique[0].width, 314, 1, 'carte seule');

    // 1024 x 768
    {
      const insc = await mesurer(alice, '/coureur/inscriptions', 1024, 768);
      expect(colonnes(insc.cartes)).toBe(2);
      for (const c of insc.cartes) proche(c.width, 463, 1, 'dossard à 1024');
      const cour = await mesurer(bob, '/coureur', 1024, 768);
      expect(colonnes(cour.cartes)).toBe(3);
    }
    // 768 x 1024
    for (const [pseudo, chemin] of [[bob, '/coureur'], [alice, '/coureur/inscriptions']] as const) {
      const { cartes, region } = await mesurer(pseudo, chemin, 768, 1024);
      proche(region.width, 736, 1, 'carte de la page à 768');
      expect(colonnes(cartes)).toBe(2);
      for (const c of cartes) proche(c.width, 335, 1, 'largeur à 768');
    }
    // 640 x 360 et 360 x 640
    for (const [pseudo, chemin] of [[bob, '/coureur'], [alice, '/coureur/inscriptions']] as const) {
      const paysage = await mesurer(pseudo, chemin, 640, 360);
      expect(colonnes(paysage.cartes)).toBe(1);
      const { cartes, region } = await mesurer(pseudo, chemin, 360, 640);
      expect(colonnes(cartes)).toBe(1);
      proche(region.width, 328, 1, 'carte de la page à 360');
      proche(region.x, 16, 1, 'bord gauche à 360');
      for (const c of cartes) proche(c.width, 278, 1, 'carte à 360');
    }
  });

  test('CA10 - aucun contenu ne déborde avec des noms de 100 caractères et le texte à 200 %', async ({ page, playwright }) => {
    test.setTimeout(240_000);
    const alice = await creerCoureur(playwright);
    const bob = await creerCoureur(playwright, 'bob');
    const base = joursLointains();
    const w = await creerCourse(playwright, { jours: base + 1 });
    const espaces = await creerCourse(playwright, { jours: base });
    await sinscrire(playwright, alice, w.id);
    await sinscrire(playwright, alice, espaces.id);
    const reecritures = { [w.id]: NOM_100_W, [espaces.id]: NOM_100_ESPACES };
    const nomsAffiches = [NOM_100_W, NOM_100_ESPACES];

    const pageCoureur = async (pseudo: string, l: number, h: number, grand: boolean) => {
      await page.unrouteAll({ behavior: 'ignoreErrors' });
      await reecrireNoms(page, '**/api/coureur/courses', 'id', 'nom', reecritures);
      await reecrireNoms(page, '**/api/coureur/inscriptions', 'courseId', 'courseNom', reecritures, 1234);
      await ouvrir(page, pseudo, '/coureur', l, h, grand);
      await expect(ligneCoureur(page, NOM_100_W)).toBeVisible();
    };

    for (const [l, h] of [[320, 640], [360, 640], [1280, 800]] as const) {
      // Espace coureur : liste simple puis erreur de ligne
      await pageCoureur(bob, l, h, true);
      expect(await debordements(page, 'coureur-ligne-course', 'coureur-course-nom', nomsAffiches), `Espace coureur ${l}`).toEqual([]);
      await sansDefilementHorizontal(page);
      if (l < 1000) expect(colonnes(await boites(page, 'coureur-ligne-course', 'coureur-course-nom', nomsAffiches))).toBe(1);
      await simuler409(page, w.id, 'COURSE_COMPLETE');
      await ligneCoureur(page, NOM_100_W).getByTestId('coureur-bouton-sinscrire').click();
      await expect(ligneCoureur(page, NOM_100_W).getByTestId('coureur-erreur')).toBeVisible();
      expect(await debordements(page, 'coureur-ligne-course', 'coureur-course-nom', nomsAffiches), `Espace coureur avec erreur ${l}`).toEqual([]);
      await sansDefilementHorizontal(page);

      // Mes inscriptions : liste simple (Dossard 1234) puis confirmation ouverte
      await page.unrouteAll({ behavior: 'ignoreErrors' });
      await reecrireNoms(page, '**/api/coureur/inscriptions', 'courseId', 'courseNom', reecritures, 1234);
      await ouvrir(page, alice, '/coureur/inscriptions', l, h, true);
      const ligneW = page.getByTestId('inscriptions-ligne').filter({ has: page.getByTestId('inscriptions-course-nom').getByText(NOM_100_W, { exact: true }) });
      await expect(ligneW).toBeVisible();
      await expect(ligneW.getByTestId('inscriptions-dossard')).toHaveText('Dossard 1234');
      const nomW = ligneW.getByTestId('inscriptions-course-nom');
      expect(await css(nomW, 'overflowWrap')).toBe('anywhere');
      expect(((await nomW.textContent()) ?? '').trim()).toHaveLength(100);
      expect(await debordements(page, 'inscriptions-ligne', 'inscriptions-course-nom', nomsAffiches), `Mes inscriptions ${l}`).toEqual([]);
      await sansDefilementHorizontal(page);
      if (l < 1000) expect(colonnes(await boites(page, 'inscriptions-ligne', 'inscriptions-course-nom', nomsAffiches))).toBe(1);
      await ligneW.getByTestId('inscriptions-bouton-desinscrire').click();
      await expect(ligneW.getByTestId('inscriptions-confirmation-desinscription')).toBeVisible();
      expect(await debordements(page, 'inscriptions-ligne', 'inscriptions-course-nom', nomsAffiches), `confirmation ${l}`).toEqual([]);
      await sansDefilementHorizontal(page);
    }

    // texte normal à 320 et 360 : carte de la page, cibles, texte
    await page.unrouteAll({ behavior: 'ignoreErrors' });
    for (const l of [320, 360]) {
      for (const [pseudo, chemin, nom] of [[bob, '/coureur', 'Courses ouvertes'], [alice, '/coureur/inscriptions', 'Mes inscriptions']] as const) {
        await ouvrir(page, pseudo, chemin, l, 640);
        await page.evaluate(AIDES_PAGE);
        const mesures = (await page.evaluate(() => (window as unknown as { __t: { responsive(): MesuresResponsive } }).__t.responsive())) as MesuresResponsive;
        const region = (await carteDePage(page, nom).boundingBox())!;
        proche(region.width, mesures.clientWidth - 32, 1, `carte de la page ${chemin} à ${l}`);
        proche(region.x, 16, 1, `bord gauche ${chemin} à ${l}`);
        expect(mesures.cibles, `cibles ${chemin} à ${l}`).toEqual([]);
        expect(mesures.petits, `texte ${chemin} à ${l}`).toEqual([]);
      }
    }
  });
});

/* ------------------------------------------------------------------ CA11 : clavier */

interface Arret {
  testid: string | null;
  carte: string | null;
  contour: string;
  ancetres: string[];
}

async function arretCourant(page: Page): Promise<Arret | null> {
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el || el === document.body) return null;
    const li = el.closest('li');
    const nom = li?.querySelector('[data-testid$="course-nom"]')?.textContent?.trim() ?? null;
    const cs = getComputedStyle(el);
    const ancetres: string[] = [];
    for (let n = el.parentElement; n; n = n.parentElement) {
      const c = getComputedStyle(n);
      if (c.overflowX !== 'visible' || c.overflowY !== 'visible') ancetres.push(`${n.tagName} ${c.overflowX}/${c.overflowY}`);
      if (n.tagName === 'MAIN') break;
    }
    return { testid: el.getAttribute('data-testid'), carte: nom, contour: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`, ancetres };
  });
}

test.describe('Clavier', () => {
  test("CA11 - l'ordre de Tab suit le DOM, un seul arrêt par action, contour visible en entier", async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base + 3 });
    const c = await creerCourse(playwright, { jours: base + 2, surcharge: { nombreMaxParticipants: 2 } });
    await sinscrire(playwright, await creerCoureur(playwright, 'bruno'), c.id);
    await sinscrire(playwright, await creerCoureur(playwright, 'chloe'), c.id);

    await ouvrir(page, alice, '/coureur');
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    const miens = [a.nom, c.nom];
    const arrets: Arret[] = [];
    let vuA = false;
    for (let i = 0; i < 400; i++) {
      await page.keyboard.press('Tab');
      const arret = await arretCourant(page);
      if (!arret) break;
      if (arret.carte && miens.includes(arret.carte)) arrets.push(arret);
      else if (vuA && arret.carte) break;
      if (arret.carte === a.nom && arret.testid === 'coureur-bouton-sinscrire') vuA = true;
    }
    expect(arrets.map((x) => `${x.carte} > ${x.testid}`)).toEqual([`${a.nom} > coureur-bouton-sinscrire`]);
    for (const arret of arrets) {
      expect(arret.contour).toBe(`solid 3px ${FOCUS}`);
      expect(arret.ancetres).toEqual([]);
    }
    // Entrée sur « S'inscrire » inscrit
    await ligneCoureur(page, a.nom).getByTestId('coureur-bouton-sinscrire').focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('coureur-message-inscription')).toHaveText(`Vous êtes inscrit à ${a.nom} avec le dossard 1.`);

    // Mes inscriptions : deux dossards désinscriptibles et E (en cours)
    const alice2 = await creerCoureur(playwright);
    const b1 = await creerCourse(playwright, { jours: base + 6 });
    const b2 = await creerCourse(playwright, { jours: base + 5 });
    const e = await creerCourse(playwright, { jours: base + 4 });
    for (const course of [b1, b2, e]) await sinscrire(playwright, alice2, course.id);
    placerStatutCourseEnBase(e.id, 'EN_COURS');
    await ouvrir(page, alice2, '/coureur/inscriptions');
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    const stops: Arret[] = [];
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press('Tab');
      const arret = await arretCourant(page);
      if (!arret) break;
      if (arret.carte) stops.push(arret);
      if (stops.length === 2) break;
    }
    expect(stops.map((x) => `${x.carte} > ${x.testid}`)).toEqual([
      `${b1.nom} > inscriptions-bouton-desinscrire`,
      `${b2.nom} > inscriptions-bouton-desinscrire`,
    ]);
    for (const arret of stops) {
      expect(arret.contour).toBe(`solid 3px ${FOCUS}`);
      expect(arret.ancetres).toEqual([]);
    }
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('inscriptions-bouton-annuler-desinscription')).toBeFocused();
  });
});

/* ------------------------------------------------------------------ CA12 : thème, palette, contraste */

test.describe('Palette, contraste et polices des écrans', () => {
  test('CA12 - les scènes du thème sont déclarées, dans la palette et au contraste AA', async ({ page, playwright }) => {
    test.setTimeout(180_000);
    const noms = ETATS.map((e) => e.nom);
    expect(noms).toContain('courses ouvertes vide');
    expect(noms).toContain('mes inscriptions vide');
    expect(noms).toContain('mes inscriptions avec confirmation de desinscription');

    const jeu = await preparerJeuReference(playwright);
    const scenes = [
      ...ECRANS.filter((e) => e.nom === 'courses ouvertes' || e.nom === 'mes inscriptions'),
      ...ETATS.filter((e) => ['courses ouvertes vide', 'mes inscriptions vide', 'mes inscriptions avec confirmation de desinscription'].includes(e.nom)),
    ];
    for (const scene of scenes) {
      await page.unrouteAll({ behavior: 'ignoreErrors' });
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.context().clearCookies();
      const nettoyage = await ouvrirScene(page, scene, jeu);
      await page.mouse.move(0, 0);
      const ecarts = (await page.evaluate((a) => (window as unknown as { __t: { horsPalette(a: string[]): EcartCouleur[] } }).__t.horsPalette(a), [...RGB_PALETTE, RGB_TRANSPARENT])) as EcartCouleur[];
      expect(ecarts, `hors palette : ${scene.nom}`).toEqual([]);
      const mesures = (await page.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
      expect(mesures.length).toBeGreaterThan(0);
      const sous = mesures.filter((m) => m.ratio + 1e-9 < m.seuil).map((m) => `${m.element} ${m.couleur} sur ${m.fond} = ${m.ratio.toFixed(2)}`);
      expect(sous, `contraste : ${scene.nom}`).toEqual([]);
      if (scene.nom === 'mes inscriptions') {
        const bande = mesures.find((m) => m.element.startsWith('inscriptions-dossard'));
        expect(bande, 'mesure du numéro').toBeTruthy();
        expect(bande!.ratio).toBeGreaterThanOrEqual(11.8);
      }
      await nettoyage?.();
    }

    await page.unrouteAll({ behavior: 'ignoreErrors' });
    for (const largeur of [320, 360]) {
      for (const scene of scenes.filter((s) => ECRANS.includes(s))) {
        await page.setViewportSize({ width: largeur, height: 640 });
        await page.goto(typeof scene.route === 'string' ? scene.route : scene.route(jeu));
        await scene.pret(page, jeu);
        await page.evaluate(AIDES_PAGE);
        const m = (await page.evaluate(() => (window as unknown as { __t: { responsive(): MesuresResponsive } }).__t.responsive())) as MesuresResponsive;
        expect(m.cartes.length).toBeGreaterThan(0);
        for (const carte of m.cartes) {
          proche(carte.largeur, m.clientWidth - 32, 1, `${scene.nom} à ${largeur}`);
          proche(carte.gauche, 16, 1, `${scene.nom} bord gauche à ${largeur}`);
        }
        expect(m.cibles).toEqual([]);
        expect(m.petits).toEqual([]);
      }
    }
  });
});

/* ------------------------------------------------------------------ CA13 : modes d'affichage */

test.describe('Modes d\'affichage', () => {
  test('CA13 - mouvement réduit et contraste forcé sur les deux écrans', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const bob = await creerCoureur(playwright, 'bob');
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base + 1, logo: true });
    const b = await creerCourse(playwright, { jours: base });
    const insA = await sinscrire(playwright, alice, a.id);
    await sinscrire(playwright, alice, b.id);
    const jeton = lireJetonQrEnBase(insA.id);

    // mouvement réduit
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await ouvrir(page, bob, '/coureur');
    const sinscrireBouton = ligneCoureur(page, a.nom).getByTestId('coureur-bouton-sinscrire');
    expect(await css(sinscrireBouton, 'transitionDuration')).toBe('0s');
    expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
    await ouvrir(page, alice, '/coureur/inscriptions');
    const desinscrire = ligneIns(page, a.nom).getByTestId('inscriptions-bouton-desinscrire');
    expect(await css(desinscrire, 'transitionDuration')).toBe('0s');
    expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);

    // color-scheme déclaré (en contraste forcé, le navigateur le recalcule lui-même)
    expect(await css(page.locator('html'), 'colorScheme')).toBe('light');

    // contraste forcé
    await page.emulateMedia({ reducedMotion: 'no-preference', forcedColors: 'active' });
    await ouvrir(page, alice, '/coureur');
    const ligneB = ligneCoureur(page, b.nom);
    expect(await css(ligneB.locator('.visuel-substitution'), 'backgroundImage')).toBe('none');
    for (const id of ['coureur-course-parametres', 'coureur-course-limites']) {
      const fond = await css(ligneB.getByTestId(id), 'backgroundColor', '::before');
      expect(fond, `pictogramme ${id}`).not.toBe(RGB_TRANSPARENT);
    }
    expect(parseFloat(await css(ligneB, 'borderTopWidth'))).toBeGreaterThanOrEqual(1);
    expect(await css(ligneB, 'borderTopStyle')).not.toBe('none');
    await expect(ligneB.getByTestId('coureur-statut-inscription')).toHaveText('Inscrit');

    await ouvrir(page, alice, '/coureur/inscriptions');
    const insB = ligneIns(page, b.nom);
    expect(await css(insB.locator('.visuel-substitution'), 'backgroundImage')).toBe('none');
    expect(parseFloat(await css(insB, 'borderTopWidth'))).toBeGreaterThanOrEqual(1);
    expect(await css(insB, 'borderTopStyle')).not.toBe('none');
    await expect(insB.getByTestId('inscriptions-statut')).toHaveText('En course');
    await expect(insB.getByTestId('inscriptions-course-statut')).toHaveText('En préparation');
    expect(await decoderQr(page, ligneIns(page, a.nom))).toBe(jeton);
    const remplissages = await ligneIns(page, a.nom).getByTestId('inscriptions-qr').evaluate((svg) =>
      Array.from(new Set(Array.from(svg.querySelectorAll('[fill]')).map((e) => e.getAttribute('fill')))).sort(),
    );
    expect(remplissages).toEqual(['#000', '#fff']);
  });
});

/* ------------------------------------------------------------------ CA14 : parcours nominal */

test.describe('Parcours coureur de bout en bout', () => {
  test('CA14 - créer un compte, voir les cartes, s\'inscrire, lire son dossard, décoder le QR, se désinscrire', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const course = await creerCourse(playwright, { prefixe: 'R8a parcours' });
    const pseudo = pseudoUnique('visiteur');
    await page.setViewportSize({ width: 360, height: 640 });

    // création du compte par l'interface
    const csrf = page.waitForResponse((r) => r.url().includes('/api/csrf'));
    await page.goto('/creer-compte');
    await csrf;
    await page.getByTestId('champ-pseudo').fill(pseudo);
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
    await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('message-succes')).toContainText(pseudo);

    // connexion puis menu « Espace coureur »
    await connecterCoureur(page, pseudo);
    const ligne = ligneCoureur(page, course.nom);
    await expect(ligne).toBeVisible();
    await expect(ligne.getByTestId('coureur-course-parametres')).toHaveText('6706 m · 60 min · 120 m D+');
    await ligne.getByTestId('coureur-bouton-sinscrire').scrollIntoViewIfNeeded();
    const inscription = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith(`/api/coureur/courses/${course.id}/inscriptions`));
    await ligne.getByTestId('coureur-bouton-sinscrire').click();
    const corps = (await (await inscription).json()) as { id: string; dossard: number };
    expect(corps.dossard).toBe(1);
    await expect(page.getByTestId('coureur-message-inscription')).toHaveText(`Vous êtes inscrit à ${course.nom} avec le dossard 1.`);
    await expect(ligne.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
    await expect(ligne.getByTestId('coureur-statut-inscription')).toHaveText('Inscrit');

    // Mes inscriptions par le menu
    await ouvrirMenuCompte(page);
    await page.getByTestId('menu-lien-mes-inscriptions').click();
    await expect(page).toHaveURL(/\/coureur\/inscriptions$/);
    const dossard = ligneIns(page, course.nom);
    await expect(dossard).toBeVisible();
    await expect(dossard.getByTestId('inscriptions-dossard')).toHaveText('Dossard 1');
    await expect(dossard.getByTestId('inscriptions-statut')).toHaveText('En course');
    await expect(dossard.getByTestId('inscriptions-course-date')).toHaveText(dateAffichee(course.date));
    expect(await decoderQr(page, dossard)).toBe(lireJetonQrEnBase(corps.id));
    const boite = (await dossard.getByTestId('inscriptions-qr').boundingBox())!;
    expect(boite.width).toBeGreaterThanOrEqual(200);
    expect(boite.height).toBeGreaterThanOrEqual(200);

    // désinscription
    await dossard.getByTestId('inscriptions-bouton-desinscrire').click();
    await dossard.getByTestId('inscriptions-bouton-confirmer-desinscription').click();
    await expect(page.getByTestId('inscriptions-message-desinscription')).toHaveText(`Vous êtes désinscrit de ${course.nom}.`);
    await expect(page.getByTestId('inscriptions-vide')).toHaveText("Vous n'êtes inscrit à aucune course.");
  });
});
