import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { PNG } from 'pngjs';
import { MOT_DE_PASSE, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_ADMIN_MASTER,
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
  affecterBenevolesParApi,
  connecterAdminMaster,
  connecterParApi,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  dateAffichee,
  dateDansJours,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { placerStatutCourseEnBase } from './aide-coureur';
import { ouvrirMenuCompte, seDeconnecterParLeMenu } from './aide-entete';
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

// Marge pour un démarrage à froid de la stack ; les tests qui demandent plus l'écrasent.
test.beforeEach(({}, testInfo) => {
  testInfo.setTimeout(60_000);
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const AGRANDIR = 'html{font-size:32px}';
const FORET = 'rgb(20, 53, 42)';
const SURFACE = 'rgb(255, 252, 245)';
const SABLE = 'rgb(239, 230, 208)';
const TRAIT = 'rgb(217, 207, 182)';
const ENCRE_DOUCE = 'rgb(74, 90, 80)';
const FOCUS = 'rgb(168, 67, 0)';
const NOM_100_W = 'W'.repeat(100);
const NOM_100_ESPACES = 'Course aux mots courts '.repeat(5).slice(0, 100);

interface Identifiants {
  pseudo: string;
  motDePasse: string;
}
const MASTER: Identifiants = { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER };
const benevoleDe = (pseudo: string): Identifiants => ({ pseudo, motDePasse: MOT_DE_PASSE_BENEVOLE_CREE });

/* ------------------------------------------------------------------ préparation par l'API */

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

/** Nom de Course sans la suite « qr » (voir `inscription-course.spec.ts`). */
const nomSansQr = (prefixe: string): string => nomCourseUnique(prefixe).replace(/qr/gi, 'q9');

/** Jour lointain (en jours) pour que les Courses du test ne croisent pas celles des autres tests. */
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

async function creerCourse(playwright: PlaywrightLib, options: { prefixe?: string; jours?: number; logo?: boolean } = {}): Promise<CourseCreee> {
  const date = dateDansJours(options.jours ?? joursLointains());
  const nom = nomSansQr(options.prefixe ?? 'R8b');
  const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date })));
  const id = course['id'] as string;
  if (options.logo) {
    const contenu = pngLarge();
    await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, id, { nom: 'large.png', type: 'image/png', contenu }));
  }
  return { id, nom, date };
}

async function creerBenevole(playwright: PlaywrightLib, pseudo = pseudoBenevoleUnique()): Promise<string> {
  await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, pseudo));
  return pseudo;
}

async function creerAdmin(playwright: PlaywrightLib, pseudo = pseudoAdminUnique()): Promise<string> {
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, pseudo));
  return pseudo;
}

async function affecter(playwright: PlaywrightLib, courseId: string, ...pseudos: string[]): Promise<void> {
  await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, courseId, pseudos));
}

/** Pseudo unique de exactement 30 caractères (lettres minuscules, chiffres). */
function pseudo30(prefixe: string): string {
  return `${prefixe}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`.padEnd(30, 'x').slice(0, 30);
}

/* ------------------------------------------------------------------ navigation et mesures */

const TITRES: Record<string, string> = {
  '/benevole': 'accueil-benevole-titre',
  '/administration': 'titre-administration',
  '/administration/benevoles': 'titre-benevoles',
  '/administration/admins': 'titre-admins',
};

async function ouvrir(page: Page, ident: Identifiants, chemin: string, largeur = 1280, hauteur = 800, grand = false): Promise<void> {
  await page.setViewportSize({ width: largeur, height: hauteur });
  await page.context().clearCookies();
  await connecterParApi(page.request, ident.pseudo, ident.motDePasse);
  await page.goto(chemin);
  await expect(page.getByTestId(TITRES[chemin])).toBeVisible();
  await expect(page.getByText('Chargement…')).toHaveCount(0);
  if (grand) await page.addStyleTag({ content: AGRANDIR });
}

function proche(obtenu: number, attendu: number, tolerance: number, message: string): void {
  expect(Math.abs(obtenu - attendu), `${message} : obtenu ${obtenu}, attendu ${attendu} (+/- ${tolerance})`).toBeLessThanOrEqual(tolerance);
}

async function css(element: Locator, propriete: string, pseudo?: string): Promise<string> {
  return element.evaluate((el, [p, ps]) => (getComputedStyle(el, ps || null) as unknown as Record<string, string>)[p as string], [propriete, pseudo ?? '']);
}

const carteDePage = (page: Page, nom: string): Locator => page.getByRole('region', { name: nom });

interface Boite {
  texte: string;
  left: number;
  top: number;
  width: number;
  height: number;
  bas: number | null;
}

/** Boîtes des `li` de `[data-testid=idLigne]` (ordre du DOM), `selNom` donne leur libellé, `selBas` un élément dont on relève le bas. */
async function boites(page: Page, idLigne: string, selNom: string, filtre: string[] | null = null, selBas = ''): Promise<Boite[]> {
  return page.evaluate(
    ([ligne, nomSel, noms, basSel]) => {
      const sortie: Boite[] = [];
      for (const li of Array.from(document.querySelectorAll(`[data-testid="${ligne}"]`))) {
        const texte = li.querySelector(nomSel as string)?.textContent?.trim() ?? '';
        if (noms && !(noms as string[]).includes(texte)) continue;
        const r = li.getBoundingClientRect();
        const b = basSel ? li.querySelector(basSel as string)?.getBoundingClientRect() : undefined;
        sortie.push({ texte, left: r.left, top: r.top + window.scrollY, width: r.width, height: r.height, bas: b ? b.bottom + window.scrollY : null });
      }
      return sortie;
    },
    [idLigne, selNom, filtre, selBas] as [string, string, string[] | null, string],
  );
}

const colonnes = (cartes: { left: number }[]): number => new Set(cartes.map((c) => Math.round(c.left))).size;

function rangees(cartes: Boite[]): Boite[][] {
  const groupes = new Map<number, Boite[]>();
  for (const c of cartes) groupes.set(Math.round(c.top / 4), [...(groupes.get(Math.round(c.top / 4)) ?? []), c]);
  return Array.from(groupes.values());
}

async function sansDefilementHorizontal(page: Page): Promise<void> {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  expect(m.s, 'défilement horizontal').toBeLessThanOrEqual(m.c);
}

/** Descendants visibles qui sortent de leur carte, de leur encart, de leur bloc vide ou de la carte de la page. */
async function debordements(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const sortie: string[] = [];
    for (const c of Array.from(document.querySelectorAll('main section.carte, main li, main form, main .etat-vide'))) {
      if (!c.checkVisibility()) continue;
      const b = c.getBoundingClientRect();
      if (b.width === 0) continue;
      for (const el of Array.from(c.querySelectorAll('*'))) {
        if (!el.checkVisibility()) continue;
        const r = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) continue;
        if (r.left < b.left - 0.5 || r.right > b.right + 0.5) {
          sortie.push(`${el.getAttribute('data-testid') ?? el.tagName.toLowerCase()} [${r.left.toFixed(1)}; ${r.right.toFixed(1)}] hors de ${c.tagName.toLowerCase()}.${c.className} [${b.left.toFixed(1)}; ${b.right.toFixed(1)}]`);
        }
      }
    }
    return sortie;
  });
}

/** Réécrit les noms de Course d'une liste renvoyée par l'API (aucune donnée dangereuse en base). */
async function reecrireNoms(page: Page, glob: string, noms: Record<string, string>): Promise<void> {
  await page.route(glob, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    const reponse = await route.fetch();
    const liste = (await reponse.json()) as Array<Record<string, unknown>>;
    for (const element of liste) {
      const nom = noms[element['id'] as string];
      if (nom) element['nom'] = nom;
    }
    await route.fulfill({ response: reponse, json: liste });
  });
}

async function interceptervide(page: Page, glob: string): Promise<void> {
  await page.route(glob, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });
}

async function intercepter500(page: Page, glob: string): Promise<void> {
  await page.route(glob, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    await route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{}' });
  });
}

const ligneCourse = (page: Page, nom: string): Locator =>
  page.getByTestId('benevole-ligne-course').filter({ has: page.getByTestId('benevole-course-nom').getByText(nom, { exact: true }) });

/* ------------------------------------------------------------------ CA1, CA2 : Espace bénévole */

test.describe('Espace bénévole en cartes', () => {
  test('CA1 - chaque Course du bénévole est une carte passive avec visuel, date, nom et pastille', async ({ page, playwright }) => {
    test.setTimeout(90_000);
    const leo = await creerBenevole(playwright);
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base, logo: true });
    const b = await creerCourse(playwright, { jours: base + 10 });
    await affecter(playwright, a.id, leo);
    await affecter(playwright, b.id, leo);
    placerStatutCourseEnBase(b.id, 'EN_COURS');
    await ouvrir(page, benevoleDe(leo), '/benevole');

    const liste = page.getByTestId('benevole-liste-courses');
    expect(await liste.evaluate((el) => el.tagName)).toBe('UL');
    const lignes = page.getByTestId('benevole-ligne-course');
    await expect(lignes).toHaveCount(2);
    const reponse = await page.request.get('/api/benevole/courses');
    const ordreApi = ((await reponse.json()) as Array<{ nom: string }>).map((c) => c.nom);
    expect((await page.getByTestId('benevole-course-nom').allTextContents()).map((t) => t.trim())).toEqual(ordreApi);

    for (let i = 0; i < 2; i++) {
      const ligne = lignes.nth(i);
      expect(await ligne.evaluate((el) => el.tagName)).toBe('LI');
      expect(await ligne.evaluate((el) => el.classList.contains('carte'))).toBe(false);
      expect(await css(ligne, 'backgroundColor')).toBe(SURFACE);
      expect(await css(ligne, 'borderTopWidth')).toBe('1px');
      expect(await css(ligne, 'borderTopColor')).toBe(TRAIT);
      expect(await css(ligne, 'borderTopLeftRadius')).toBe('12px');
      expect(await css(ligne, 'boxShadow')).not.toBe('none');
      await expect(ligne.locator('a, button, input, select, textarea, [tabindex]')).toHaveCount(0);
    }

    const ligneA = ligneCourse(page, a.nom);
    const ligneB = ligneCourse(page, b.nom);
    const ordre = await ligneA.evaluate((li) => {
      const visuel = li.querySelector('.carte-liste__visuel')!;
      const date = li.querySelector('[data-testid="benevole-course-date"]')!;
      const nom = li.querySelector('[data-testid="benevole-course-nom"]')!;
      const statut = li.querySelector('[data-testid="benevole-course-statut"]')!;
      const avant = (x: Element, y: Element) => !!(x.compareDocumentPosition(y) & Node.DOCUMENT_POSITION_FOLLOWING);
      return avant(visuel, date) && avant(date, nom) && avant(nom, statut);
    });
    expect(ordre, 'ordre du DOM visuel, date, nom, statut').toBe(true);

    const date = ligneA.getByTestId('benevole-course-date');
    await expect(date).toHaveText(dateAffichee(a.date));
    await expect(date).toHaveAttribute('datetime', a.date);
    expect(await css(date, 'fontSize')).toBe('20px');
    expect(await css(date, 'fontWeight')).toBe('700');
    expect(await css(date, 'color')).toBe(FORET);

    const nom = ligneA.getByTestId('benevole-course-nom');
    expect(await nom.evaluate((el) => el.tagName)).toBe('H2');
    await expect(nom).toHaveClass(/\bcarte-liste__titre\b/);
    await expect(nom).toHaveText(a.nom);
    expect(await css(nom, 'fontSize')).toBe('18px');
    expect(await css(nom, 'fontWeight')).toBe('700');

    const statutA = ligneA.getByTestId('benevole-course-statut');
    await expect(statutA).toHaveText('En préparation');
    await expect(statutA).toHaveClass(/\bpastille-statut\b/);
    await expect(statutA).toHaveClass(/pastille-statut--en-preparation/);
    const statutB = ligneB.getByTestId('benevole-course-statut');
    await expect(statutB).toHaveText('En cours');
    await expect(statutB).toHaveClass(/pastille-statut--en-cours/);

    const logo = ligneA.getByTestId('benevole-course-logo');
    await expect(logo).toBeVisible();
    await expect(logo).toHaveAttribute('alt', `Logo de ${a.nom}`);
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

    await expect(ligneB.getByTestId('benevole-course-logo')).toHaveCount(0);
    const substitution = ligneB.locator('.visuel-substitution');
    await expect(substitution).toHaveCount(1);
    const boite = (await substitution.boundingBox())!;
    proche(boite.width, zone.w, 1, 'largeur du visuel de substitution');
    proche(boite.height, zone.h, 1, 'hauteur du visuel de substitution');
    expect(await css(substitution, 'backgroundImage')).toContain('data:image/svg+xml');
    expect(await substitution.evaluate((el) => ({ texte: el.textContent, testid: el.getAttribute('data-testid') }))).toEqual({ texte: '', testid: null });
  });

  test('CA2 - les colonnes de cartes suivent la largeur de la fenêtre', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const leo = await creerBenevole(playwright);
    const seul = await creerBenevole(playwright);
    const base = 1800 + Math.floor(Math.random() * 15);
    const courses: CourseCreee[] = [];
    for (let i = 0; i < 4; i++) courses.push(await creerCourse(playwright, { jours: base + i, logo: i === 0 }));
    for (const c of courses) await affecter(playwright, c.id, leo);
    await affecter(playwright, courses[0].id, leo, seul);
    const noms = courses.map((c) => c.nom);

    const mesurer = async (l: number, h: number) => {
      await ouvrir(page, benevoleDe(leo), '/benevole', l, h);
      const reponse = await page.request.get('/api/benevole/courses');
      const ordreApi = ((await reponse.json()) as Array<{ nom: string }>).map((c) => c.nom).filter((n) => noms.includes(n));
      const cartes = await boites(page, 'benevole-ligne-course', '[data-testid="benevole-course-nom"]', noms, '[data-testid="benevole-course-statut"]');
      expect(cartes.map((c) => c.texte), 'ordre de lecture').toEqual(ordreApi);
      await sansDefilementHorizontal(page);
      return { cartes, region: (await carteDePage(page, 'Espace bénévole').boundingBox())! };
    };

    const grand = await mesurer(1280, 800);
    proche(grand.region.width, 1024, 1, 'carte de la page');
    expect(colonnes(grand.cartes)).toBe(3);
    for (const c of grand.cartes) proche(c.width, 314, 1, 'largeur de carte');
    for (const rangee of rangees(grand.cartes)) {
      for (const c of rangee) {
        proche(c.height, rangee[0].height, 1, 'hauteur dans la rangée');
        proche(c.bas!, rangee[0].bas!, 1, 'alignement des pastilles');
      }
    }

    await ouvrir(page, benevoleDe(seul), '/benevole', 1280, 800);
    const unique = await boites(page, 'benevole-ligne-course', '[data-testid="benevole-course-nom"]', [courses[0].nom]);
    expect(unique).toHaveLength(1);
    proche(unique[0].width, 314, 1, 'carte seule');

    const tablette = await mesurer(768, 1024);
    expect(colonnes(tablette.cartes)).toBe(2);
    for (const c of tablette.cartes) proche(c.width, 335, 1, 'largeur à 768');

    expect(colonnes((await mesurer(640, 360)).cartes)).toBe(1);

    const telephone = await mesurer(360, 640);
    expect(colonnes(telephone.cartes)).toBe(1);
    proche(telephone.region.width, 328, 1, 'carte de la page à 360');
    proche(telephone.region.x, 16, 1, 'bord gauche à 360');
    for (const c of telephone.cartes) proche(c.width, 278, 1, 'carte à 360');
  });

  test('CA3 - sans Course, un bloc sable en pointillés l\'explique ; l\'erreur 500 reste distincte', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const marc = await creerBenevole(playwright);
    const affecte = await creerBenevole(playwright);
    const c = await creerCourse(playwright);
    await affecter(playwright, c.id, affecte);

    const verifier = async (): Promise<void> => {
      const vide = page.getByTestId('accueil-benevole-vide');
      await expect(vide).toBeVisible();
      await expect(vide).toHaveText('Aucune course à scanner pour le moment.');
      await expect(page.getByTestId('benevole-liste-courses')).toHaveCount(0);
      await expect(page.getByText('Les courses auxquelles un administrateur vous affecte apparaîtront ici.')).toBeVisible();
      await expect(vide.getByText('Les courses auxquelles')).toHaveCount(0);
      const bloc = page.locator('.etat-vide');
      expect(await css(bloc, 'backgroundColor')).toBe(SABLE);
      expect(await css(bloc, 'borderTopStyle')).toBe('dashed');
      expect(await css(bloc, 'borderTopWidth')).toBe('1px');
      expect(await css(bloc, 'borderTopColor')).toBe(TRAIT);
      const visuel = (await bloc.locator('.visuel-substitution').boundingBox())!;
      proche(visuel.width / visuel.height, 16 / 9, 0.02, 'rapport du visuel');
      expect(visuel.width).toBeLessThanOrEqual(192.5);
      expect(await debordements(page)).toEqual([]);
      await sansDefilementHorizontal(page);
    };

    // Marc : aucune affectation
    await ouvrir(page, benevoleDe(marc), '/benevole');
    await verifier();
    await page.setViewportSize({ width: 360, height: 640 });
    await verifier();
    await page.setViewportSize({ width: 320, height: 640 });
    await page.addStyleTag({ content: AGRANDIR });
    await verifier();

    // liste interceptée à []
    await interceptervide(page, '**/api/benevole/courses');
    await ouvrir(page, benevoleDe(affecte), '/benevole');
    await verifier();
    await page.setViewportSize({ width: 360, height: 640 });
    await verifier();
    await page.setViewportSize({ width: 320, height: 640 });
    await page.addStyleTag({ content: AGRANDIR });
    await verifier();

    // erreur 500
    await page.unrouteAll({ behavior: 'ignoreErrors' });
    await intercepter500(page, '**/api/benevole/courses');
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.reload();
    await expect(page.getByTestId('accueil-benevole-erreur')).toHaveText('Impossible de charger vos courses. Réessayez plus tard.');
    await expect(page.getByTestId('accueil-benevole-vide')).toHaveCount(0);
    await expect(page.locator('.etat-vide')).toHaveCount(0);
  });
});

/* ------------------------------------------------------------------ CA4 à CA6 : Gestion des comptes */

interface TypeCompte {
  chemin: '/administration/benevoles' | '/administration/admins';
  liste: string;
  ligne: string;
  vide: string;
  erreur: string;
  titre: string;
  prefixe: string;
  texteVide: string;
  texteErreur: string;
  boutonCreer: string;
  titreCreation: string;
  succes: (pseudo: string) => string;
  creer: (playwright: PlaywrightLib, pseudo?: string) => Promise<string>;
  api: string;
}

const BENEVOLES: TypeCompte = {
  chemin: '/administration/benevoles',
  liste: 'liste-benevoles',
  ligne: 'ligne-benevole',
  vide: 'benevoles-vide',
  erreur: 'benevoles-erreur',
  titre: 'Gestion des bénévoles',
  prefixe: 'benevole',
  texteVide: 'Aucun bénévole pour le moment.',
  texteErreur: 'Impossible de charger la liste des bénévoles. Réessayez plus tard.',
  boutonCreer: 'Créer le bénévole',
  titreCreation: 'Créer un bénévole',
  succes: (p) => `Le bénévole ${p} a été créé.`,
  creer: creerBenevole,
  api: '**/api/administration/benevoles',
};
const ADMINS: TypeCompte = {
  chemin: '/administration/admins',
  liste: 'liste-admins',
  ligne: 'ligne-admin',
  vide: 'admins-vide',
  erreur: 'admins-erreur',
  titre: 'Gestion des administrateurs',
  prefixe: 'admin',
  texteVide: 'Aucun administrateur pour le moment.',
  texteErreur: 'Impossible de charger la liste des administrateurs. Réessayez plus tard.',
  boutonCreer: "Créer l'administrateur",
  titreCreation: 'Créer un administrateur',
  succes: (p) => `L'administrateur ${p} a été créé.`,
  creer: creerAdmin,
  api: '**/api/administration/admins',
};
const TYPES = [BENEVOLES, ADMINS];

test.describe('Gestion des comptes en cartes', () => {
  for (const t of TYPES) {
    test(`CA4 - ${t.chemin} : une carte par Compte, en grille de 2 colonnes, dans l'ordre de l'API`, async ({ page, playwright }) => {
      test.setTimeout(120_000);
      const p1 = await t.creer(playwright);
      const p2 = await t.creer(playwright);
      const long = await t.creer(playwright, pseudo30('l'));
      expect(long).toHaveLength(30);
      await ouvrir(page, MASTER, t.chemin);

      const liste = page.getByTestId(t.liste);
      expect(await liste.evaluate((el) => el.tagName)).toBe('UL');
      const reponse = await page.request.get(t.chemin.replace('/administration/', '/api/administration/'));
      const ordreApi = ((await reponse.json()) as Array<{ pseudo: string }>).map((c) => c.pseudo).filter((n) => [p1, p2, long].includes(n));
      const cartes = await boites(page, t.ligne, '.ligne__pseudo', [p1, p2, long]);
      expect(cartes.map((c) => c.texte)).toEqual(ordreApi);

      const lignes = page.getByTestId(t.ligne);
      const ligne1 = lignes.filter({ hasText: p1 });
      await expect(ligne1).toHaveCount(1);
      expect(await ligne1.evaluate((el) => ({ tag: el.tagName, liste: el.classList.contains('carte-liste'), compte: el.classList.contains('compte'), carte: el.classList.contains('carte') }))).toEqual({ tag: 'LI', liste: true, compte: true, carte: false });
      expect(await css(ligne1, 'backgroundColor')).toBe(SURFACE);
      expect(await css(ligne1, 'borderTopWidth')).toBe('1px');
      expect(await css(ligne1, 'borderTopColor')).toBe(TRAIT);
      expect(await css(ligne1, 'borderTopLeftRadius')).toBe('12px');
      expect(await css(ligne1, 'paddingTop')).toBe('12px');
      const pseudo = ligne1.locator('.ligne__pseudo');
      await expect(pseudo).toHaveText(p1);
      expect(await css(pseudo, 'fontSize')).toBe('18px');
      expect(await css(pseudo, 'fontWeight')).toBe('700');
      expect(await css(pseudo, 'color')).toBe(FORET);
      const date = ligne1.locator('.ligne__date');
      await expect(date).toContainText('Créé le');
      const heure = date.locator('time');
      await expect(heure).toHaveText(/^\d{2}\/\d{2}\/\d{4} à \d{2}:\d{2}$/);
      expect(await heure.getAttribute('datetime')).toMatch(/^\d{4}-\d{2}-\d{2}T/);
      expect(await css(heure, 'color')).toBe(ENCRE_DOUCE);

      // le pseudo de 30 caractères reste dans sa carte
      expect(await debordements(page)).toEqual([]);

      const mesurer = async (l: number, h: number) => {
        await ouvrir(page, MASTER, t.chemin, l, h);
        const toutes = await boites(page, t.ligne, '.ligne__pseudo');
        expect(toutes.length).toBeGreaterThanOrEqual(3);
        await sansDefilementHorizontal(page);
        return { toutes, region: (await carteDePage(page, t.titre).boundingBox())! };
      };
      for (const l of [1280, 768]) {
        const r = await mesurer(l, 800);
        if (l === 1280) proche(r.region.width, 640, 1, 'carte de la page');
        expect(colonnes(r.toutes), `colonnes à ${l}`).toBe(2);
        for (const c of r.toutes) proche(c.width, 287, 1, `carte à ${l}`);
      }
      const tel = await mesurer(360, 640);
      expect(colonnes(tel.toutes)).toBe(1);
      proche(tel.region.width, 328, 1, 'carte de la page à 360');
      proche(tel.region.x, 16, 1, 'bord gauche à 360');
      for (const c of tel.toutes) proche(c.width, 278, 1, 'carte à 360');
    });

    test(`CA5 - ${t.chemin} : la liste vide affiche un bloc sable en pointillés au-dessus du formulaire`, async ({ page }) => {
      test.setTimeout(120_000);
      await interceptervide(page, t.api);
      await ouvrir(page, MASTER, t.chemin);
      const verifier = async (): Promise<void> => {
        const vide = page.getByTestId(t.vide);
        await expect(vide).toBeVisible();
        await expect(vide).toHaveText(t.texteVide);
        await expect(page.getByTestId(t.liste)).toHaveCount(0);
        await expect(page.getByText('Créez-en un avec le formulaire ci-dessous.')).toBeVisible();
        await expect(vide.getByText('Créez-en un')).toHaveCount(0);
        const bloc = page.locator('.etat-vide');
        expect(await css(bloc, 'backgroundColor')).toBe(SABLE);
        expect(await css(bloc, 'borderTopStyle')).toBe('dashed');
        expect(await css(bloc, 'borderTopColor')).toBe(TRAIT);
        const visuel = (await bloc.locator('.visuel-substitution').boundingBox())!;
        proche(visuel.width / visuel.height, 16 / 9, 0.02, 'rapport du visuel');
        await expect(page.getByTestId(`${t.prefixe}-champ-pseudo`)).toBeVisible();
        expect(await debordements(page)).toEqual([]);
        await sansDefilementHorizontal(page);
      };
      await verifier();
      await page.setViewportSize({ width: 360, height: 640 });
      await verifier();
      await page.setViewportSize({ width: 320, height: 640 });
      await page.addStyleTag({ content: AGRANDIR });
      await verifier();

      await page.unrouteAll({ behavior: 'ignoreErrors' });
      await intercepter500(page, t.api);
      await page.setViewportSize({ width: 1280, height: 800 });
      await page.reload();
      await expect(page.getByTestId(t.erreur)).toHaveText(t.texteErreur);
      await expect(page.getByTestId(t.vide)).toHaveCount(0);
      await expect(page.locator('.etat-vide')).toHaveCount(0);
    });

    test(`CA6 - ${t.chemin} : le formulaire est dans un encart sable et crée un Compte`, async ({ page, playwright }) => {
      test.setTimeout(90_000);
      const existant = await t.creer(playwright);
      await ouvrir(page, MASTER, t.chemin);
      const p = t.prefixe;
      const encart = page.getByRole('form', { name: t.titreCreation });
      await expect(encart).toBeVisible();
      expect(await css(encart, 'backgroundColor')).toBe(SABLE);
      expect(await css(encart, 'borderTopWidth')).toBe('1px');
      expect(await css(encart, 'borderTopColor')).toBe(TRAIT);
      expect(await css(encart, 'borderTopLeftRadius')).toBe('12px');
      expect(await css(encart, 'paddingTop')).toBe('16px');
      await expect(encart.getByRole('heading', { level: 2, name: t.titreCreation })).toBeVisible();
      await expect(encart.getByTestId(`${p}-champ-mot-de-passe`)).toHaveAttribute('type', 'password');
      await expect(encart.getByTestId(`${p}-champ-confirmation`)).toHaveAttribute('type', 'password');
      const bouton = encart.getByTestId(`${p}-bouton-creer`);
      await expect(bouton).toHaveText(t.boutonCreer);
      await expect(bouton).toHaveClass(/\bbouton\b/);
      expect(await css(bouton, 'backgroundColor')).toBe(FORET);
      expect((await bouton.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      const ordre = await encart.evaluate((form, prefixe) => {
        const ids = ['h2', `[data-testid="${prefixe}-champ-pseudo"]`, `[data-testid="${prefixe}-champ-mot-de-passe"]`, `[data-testid="${prefixe}-champ-confirmation"]`, `[data-testid="${prefixe}-bouton-creer"]`];
        const els = ids.map((s) => form.querySelector(s)!);
        return els.every((e, i) => i === 0 || !!(els[i - 1].compareDocumentPosition(e) & Node.DOCUMENT_POSITION_FOLLOWING));
      }, p);
      expect(ordre, 'ordre des éléments de l\'encart').toBe(true);
      const retour = page.getByTestId('lien-retour-administration');
      await expect(retour).toHaveText("Retour à l'administration");
      const position = await retour.evaluate((el) => {
        const form = el.closest('main')!.querySelector('form')!;
        return { dedans: form.contains(el), apres: !!(form.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) };
      });
      expect(position).toEqual({ dedans: false, apres: true });

      // nominal
      const pseudo = t.chemin === BENEVOLES.chemin ? pseudoBenevoleUnique() : pseudoAdminUnique();
      await expect(page.getByTestId(t.ligne).filter({ hasText: pseudo })).toHaveCount(0);
      await page.getByTestId(`${p}-champ-pseudo`).fill(pseudo);
      await page.getByTestId(`${p}-champ-mot-de-passe`).fill(MOT_DE_PASSE);
      await page.getByTestId(`${p}-champ-confirmation`).fill(MOT_DE_PASSE);
      await bouton.click();
      await expect(encart.getByTestId(`${p}-message-succes`)).toHaveText(t.succes(pseudo));
      await expect(page.getByTestId(t.ligne).filter({ hasText: pseudo })).toHaveCount(1);

      // pseudo déjà pris
      await page.getByTestId(`${p}-champ-pseudo`).fill(existant);
      await page.getByTestId(`${p}-champ-mot-de-passe`).fill(MOT_DE_PASSE);
      await page.getByTestId(`${p}-champ-confirmation`).fill(MOT_DE_PASSE);
      await bouton.click();
      await expect(encart.getByTestId(`${p}-erreur-pseudo`).or(encart.getByTestId(`${p}-erreur-generale`))).toContainText(/déjà utilisé/);
      await expect(page.getByTestId(t.ligne).filter({ hasText: existant })).toHaveCount(1);

      // mot de passe trop court
      await page.getByTestId(`${p}-champ-pseudo`).fill(pseudoUnique('court'));
      await page.getByTestId(`${p}-champ-mot-de-passe`).fill('12345678901');
      await page.getByTestId(`${p}-champ-confirmation`).fill('12345678901');
      await bouton.click();
      await expect(page.getByTestId(`${p}-erreur-mot-de-passe`)).toHaveText('Le mot de passe doit faire au moins 12 caractères.');
    });
  }
});

/* ------------------------------------------------------------------ CA7 : accueil Administration */

test.describe('Accueil Administration en cartes de navigation', () => {
  test('CA7 - trois cartes pour l\'admin master, deux pour un admin, erreur sans carte', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const nadia = await creerAdmin(playwright);
    await ouvrir(page, MASTER, '/administration');
    await expect(page.getByTestId('administration-role')).toHaveText('Administrateur master');
    await expect(page.getByTestId('administration-vide')).toBeVisible();

    const liste = page.getByRole('list', { name: 'Administration', exact: true });
    const cartes = liste.locator(':scope > li');
    await expect(cartes).toHaveCount(3);
    const attendu = [
      { titre: 'Courses', texte: 'Déclarer les courses, les démarrer et suivre les coureurs.', testid: 'lien-gestion-courses', libelle: 'Gérer les courses', url: /\/administration\/courses$/ },
      { titre: 'Bénévoles', texte: 'Créer les comptes des bénévoles.', testid: 'lien-gestion-benevoles', libelle: 'Gérer les bénévoles', url: /\/administration\/benevoles$/ },
      { titre: 'Administrateurs', texte: 'Créer les comptes des administrateurs.', testid: 'lien-gestion-admins', libelle: 'Gérer les administrateurs', url: /\/administration\/admins$/ },
    ];
    for (let i = 0; i < 3; i++) {
      const carte = cartes.nth(i);
      const titre = carte.locator('h2');
      await expect(titre).toHaveText(attendu[i].titre);
      await expect(titre).toHaveClass(/\bcarte-liste__titre\b/);
      const texte = carte.locator('p');
      await expect(texte).toHaveText(attendu[i].texte);
      await expect(texte).toHaveClass(/\bcarte-liste__texte\b/);
      const lien = carte.getByTestId(attendu[i].testid);
      await expect(lien).toHaveText(attendu[i].libelle);
      await expect(lien).toHaveClass(/\bbouton\b/);
      expect(await css(lien, 'backgroundColor')).toBe(FORET);
      expect((await lien.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }

    const mesurer = async (l: number, h: number) => {
      await ouvrir(page, MASTER, '/administration', l, h);
      const boitesCartes = await liste.locator(':scope > li').evaluateAll((lis) =>
        lis.map((li) => {
          const r = li.getBoundingClientRect();
          const b = li.querySelector('a')!.getBoundingClientRect();
          return { texte: '', left: r.left, top: r.top + window.scrollY, width: r.width, height: r.height, bas: b.bottom + window.scrollY };
        }),
      );
      await sansDefilementHorizontal(page);
      return boitesCartes as Boite[];
    };
    const grand = await mesurer(1280, 800);
    expect(colonnes(grand)).toBe(3);
    for (const c of grand) proche(c.width, 314, 1, 'carte à 1280');
    for (const rangee of rangees(grand)) for (const c of rangee) proche(c.bas!, rangee[0].bas!, 1, 'alignement des boutons');
    const tablette = await mesurer(768, 1024);
    expect(colonnes(tablette)).toBe(2);
    for (const c of tablette) proche(c.width, 335, 1, 'carte à 768');
    for (const rangee of rangees(tablette)) for (const c of rangee) proche(c.bas!, rangee[0].bas!, 1, 'alignement des boutons à 768');
    expect(colonnes(await mesurer(360, 640))).toBe(1);

    // clic sur chacun des liens
    for (const a of attendu) {
      await ouvrir(page, MASTER, '/administration');
      await page.getByTestId(a.testid).click();
      await expect(page).toHaveURL(a.url);
    }

    // admin simple
    await ouvrir(page, { pseudo: nadia, motDePasse: MOT_DE_PASSE_ADMIN_CREE }, '/administration');
    await expect(page.getByTestId('administration-role')).toHaveText('Administrateur');
    await expect(page.getByTestId('administration-vide')).toBeVisible();
    await expect(liste.locator(':scope > li')).toHaveCount(2);
    await expect(page.getByTestId('lien-gestion-admins')).toHaveCount(0);
    await expect(page.getByTestId('lien-gestion-courses')).toBeVisible();
    await expect(page.getByTestId('lien-gestion-benevoles')).toBeVisible();

    // erreur de vérification des droits
    await page.context().clearCookies();
    await connecterParApi(page.request, MASTER.pseudo, MASTER.motDePasse);
    await page.route('**/api/administration/acces', (route) => route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{}' }));
    await page.goto('/administration');
    await expect(page.getByTestId('administration-erreur')).toHaveText("Impossible de vérifier vos droits d'accès. Réessayez plus tard.");
    await expect(page.getByTestId('lien-gestion-courses')).toHaveCount(0);
    await expect(page.locator('main li')).toHaveCount(0);
  });
});

/* ------------------------------------------------------------------ CA8 : 320 px, texte à 200 % */

test.describe('Débordements', () => {
  test('CA8 - aucun contenu ne déborde avec des noms et pseudos longs, le texte à 200 % et à 320 / 360 px', async ({ page, playwright }) => {
    test.setTimeout(300_000);
    const benevoleLong = await creerBenevole(playwright, pseudo30('b'));
    const adminLong = await creerAdmin(playwright, pseudo30('a'));
    const w = await creerCourse(playwright);
    const espaces = await creerCourse(playwright);
    await affecter(playwright, w.id, benevoleLong);
    await affecter(playwright, espaces.id, benevoleLong);
    expect(benevoleLong).toHaveLength(30);
    expect(adminLong).toHaveLength(30);

    interface Cas {
      nom: string;
      ident: Identifiants;
      chemin: string;
      etats: Array<'simple' | 'erreurs' | 'vide'>;
      apiVide?: string;
      prefixe?: string;
    }
    const cas: Cas[] = [
      { nom: 'Espace bénévole', ident: benevoleDe(benevoleLong), chemin: '/benevole', etats: ['simple', 'vide'], apiVide: '**/api/benevole/courses' },
      { nom: 'Gestion des bénévoles', ident: MASTER, chemin: '/administration/benevoles', etats: ['simple', 'erreurs', 'vide'], apiVide: BENEVOLES.api, prefixe: 'benevole' },
      { nom: 'Gestion des administrateurs', ident: MASTER, chemin: '/administration/admins', etats: ['simple', 'erreurs', 'vide'], apiVide: ADMINS.api, prefixe: 'admin' },
      { nom: 'Administration', ident: MASTER, chemin: '/administration', etats: ['simple'] },
    ];

    for (const c of cas) {
      for (const etat of c.etats) {
        for (const [l, h, grand] of [[320, 640, true], [360, 640, true], [1280, 800, false]] as const) {
          await page.unrouteAll({ behavior: 'ignoreErrors' });
          if (c.chemin === '/benevole' && etat === 'simple') await reecrireNoms(page, '**/api/benevole/courses', { [w.id]: NOM_100_W, [espaces.id]: NOM_100_ESPACES });
          if (etat === 'vide') await interceptervide(page, c.apiVide!);
          await ouvrir(page, c.ident, c.chemin, l, h, grand);
          const contexte = `${c.nom} / ${etat} / ${l} px`;
          if (etat === 'erreurs') {
            await page.getByTestId(`${c.prefixe}-bouton-creer`).click();
            await expect(page.getByTestId(`${c.prefixe}-erreur-pseudo`)).toBeVisible();
            await expect(page.getByTestId(`${c.prefixe}-erreur-mot-de-passe`)).toBeVisible();
          }
          if (c.chemin === '/benevole' && etat === 'simple') {
            const nom = ligneCourse(page, NOM_100_W).getByTestId('benevole-course-nom');
            await expect(nom).toHaveText(NOM_100_W);
            expect(await css(nom, 'overflowWrap')).toBe('anywhere');
            expect(((await nom.textContent()) ?? '').trim()).toHaveLength(100);
            await expect(ligneCourse(page, NOM_100_ESPACES.trim())).toHaveCount(1);
          }
          expect(await debordements(page), contexte).toEqual([]);
          await sansDefilementHorizontal(page);
          if (l < 1000 && etat !== 'vide') {
            const lis = await page.locator('main ul.grille-cartes > li').evaluateAll((els) => els.map((e) => ({ left: e.getBoundingClientRect().left })));
            expect(lis.length, contexte).toBeGreaterThan(0);
            expect(colonnes(lis), `${contexte} : colonnes`).toBe(1);
          }
        }
      }
    }

    // texte normal à 320 et 360 : carte de la page, cibles, texte
    await page.unrouteAll({ behavior: 'ignoreErrors' });
    for (const l of [320, 360]) {
      for (const c of cas) {
        await ouvrir(page, c.ident, c.chemin, l, 640);
        await page.evaluate(AIDES_PAGE);
        const mesures = (await page.evaluate(() => (window as unknown as { __t: { responsive(): MesuresResponsive } }).__t.responsive())) as MesuresResponsive;
        const region = (await page.locator('main > section.carte').boundingBox())!;
        proche(region.width, mesures.clientWidth - 32, 1, `carte de la page ${c.chemin} à ${l}`);
        proche(region.x, 16, 1, `bord gauche ${c.chemin} à ${l}`);
        expect(mesures.cibles, `cibles ${c.chemin} à ${l}`).toEqual([]);
        expect(mesures.petits, `texte ${c.chemin} à ${l}`).toEqual([]);
      }
    }
  });
});

/* ------------------------------------------------------------------ CA9 : clavier */

interface Arret {
  testid: string | null;
  texte: string;
  dansEntete: boolean;
  dansListe: boolean;
  outlineStyle: string;
  outlineWidth: string;
  outlineColor: string;
  ancetresAvecOverflow: string[];
}

async function arretsAuTab(page: Page, maximum = 20): Promise<Arret[]> {
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  const arrets: Arret[] = [];
  for (let i = 0; i < maximum; i++) {
    await page.keyboard.press('Tab');
    const arret = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return null;
      const cs = getComputedStyle(el);
      const ancetres: string[] = [];
      for (let n = el.parentElement; n; n = n.parentElement) {
        const s = getComputedStyle(n);
        if (s.overflowX !== 'visible' || s.overflowY !== 'visible') ancetres.push(`${n.tagName.toLowerCase()}.${n.className}`);
        if (n.tagName === 'MAIN') break;
      }
      return {
        testid: el.getAttribute('data-testid'),
        texte: (el.textContent ?? '').trim(),
        dansEntete: !!el.closest('.entete'),
        dansListe: !!el.closest('[data-testid="benevole-liste-courses"]'),
        outlineStyle: cs.outlineStyle,
        outlineWidth: cs.outlineWidth,
        outlineColor: cs.outlineColor,
        ancetresAvecOverflow: ancetres,
      };
    });
    if (!arret) break;
    arrets.push(arret);
  }
  return arrets;
}

async function titresDeLaPage(page: Page): Promise<string[]> {
  return page.locator('main').evaluate((main) => Array.from(main.querySelectorAll('h1, h2')).map((h) => `${h.tagName.toLowerCase()}:${(h.textContent ?? '').trim()}`));
}

test.describe('Clavier et titres', () => {
  test('CA9 - l\'ordre de Tab suit le DOM, le contour est visible en entier et les titres sont ordonnés', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const leo = await creerBenevole(playwright);
    const a = await creerCourse(playwright);
    const b = await creerCourse(playwright);
    await affecter(playwright, a.id, leo);
    await affecter(playwright, b.id, leo);

    const verifierContours = (arrets: Arret[]): void => {
      for (const arret of arrets) {
        expect(arret.outlineStyle, `${arret.texte} : style`).toBe('solid');
        expect(arret.outlineWidth, `${arret.texte} : épaisseur`).toBe('3px');
        expect(arret.outlineColor, `${arret.texte} : couleur`).toBe(FOCUS);
        expect(arret.ancetresAvecOverflow, `${arret.texte} : ancêtres qui rognent`).toEqual([]);
      }
    };

    // Espace bénévole : aucun arrêt dans la liste
    await ouvrir(page, benevoleDe(leo), '/benevole');
    await expect(page.getByTestId('benevole-ligne-course')).toHaveCount(2);
    const arretsBenevole = await arretsAuTab(page);
    expect(arretsBenevole.length).toBeGreaterThan(0);
    expect(arretsBenevole.filter((x) => x.dansListe)).toEqual([]);
    expect(arretsBenevole.filter((x) => !x.dansEntete).map((x) => x.texte), 'arrêts hors en-tête').toEqual([]);
    expect(await titresDeLaPage(page)).toEqual(['h1:Espace bénévole', `h2:${(await page.getByTestId('benevole-course-nom').first().textContent())!.trim()}`, `h2:${(await page.getByTestId('benevole-course-nom').nth(1).textContent())!.trim()}`]);

    // Gestion des bénévoles
    await ouvrir(page, MASTER, '/administration/benevoles');
    const arretsComptes = (await arretsAuTab(page)).filter((x) => !x.dansEntete);
    expect(arretsComptes.map((x) => x.testid ?? x.texte)).toEqual(['benevole-champ-pseudo', 'benevole-champ-mot-de-passe', 'benevole-champ-confirmation', 'benevole-bouton-creer', 'lien-retour-administration']);
    verifierContours(arretsComptes);
    expect(await titresDeLaPage(page)).toEqual(['h1:Gestion des bénévoles', 'h2:Bénévoles', 'h2:Créer un bénévole']);

    // Administration
    await ouvrir(page, MASTER, '/administration');
    const arretsAdmin = (await arretsAuTab(page)).filter((x) => !x.dansEntete);
    expect(arretsAdmin.map((x) => x.testid)).toEqual(['lien-gestion-courses', 'lien-gestion-benevoles', 'lien-gestion-admins']);
    expect(arretsAdmin.map((x) => x.texte)).toEqual(['Gérer les courses', 'Gérer les bénévoles', 'Gérer les administrateurs']);
    verifierContours(arretsAdmin);
    expect(await titresDeLaPage(page)).toEqual(['h1:Administration', 'h2:Courses', 'h2:Bénévoles', 'h2:Administrateurs']);
  });
});

/* ------------------------------------------------------------------ CA10 : palette et contraste des scènes ajoutées */

test.describe('Palette et contraste des états ajoutés', () => {
  const AUTORISEES = [...RGB_PALETTE, RGB_TRANSPARENT];
  const NOUVELLES = ['espace benevole vide', 'gestion des admins vide', 'gestion des benevoles vide'];
  const scenes = [...ECRANS.filter((e) => ['administration', 'gestion des admins', 'gestion des benevoles', 'espace benevole'].includes(e.nom)), ...ETATS.filter((e) => NOUVELLES.includes(e.nom))];

  test('CA10 - les scènes ETATS attendues existent', () => {
    expect(ETATS.filter((e) => NOUVELLES.includes(e.nom)).map((e) => e.nom).sort()).toEqual([...NOUVELLES].sort());
    expect(scenes).toHaveLength(7);
  });

  for (const nom of ['administration', 'gestion des admins', 'gestion des benevoles', 'espace benevole', ...NOUVELLES]) {
    test(`CA10 - « ${nom} » : palette, contrastes AA, texte à 13 px et cibles de 44 px`, async ({ page, playwright }) => {
      test.setTimeout(90_000);
      const jeu = await preparerJeuReference(playwright);
      const scene = scenes.find((s) => s.nom === nom)!;
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await ouvrirScene(page, scene, jeu);
      await page.mouse.move(0, 0);
      const ecarts = (await page.evaluate((a) => (window as unknown as { __t: { horsPalette(a: string[]): EcartCouleur[] } }).__t.horsPalette(a), AUTORISEES)) as EcartCouleur[];
      expect(ecarts, 'couleurs hors palette').toEqual([]);
      const mesures = (await page.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
      expect(mesures.length).toBeGreaterThan(0);
      const sousSeuil = mesures.filter((m) => m.ratio + 1e-9 < m.seuil).map((m) => `${m.element} ${m.couleur} sur ${m.fond} = ${m.ratio.toFixed(2)} < ${m.seuil}`);
      expect(sousSeuil, 'couples sous le seuil AA').toEqual([]);
      for (const [l, h] of [[320, 640], [360, 640]] as const) {
        await page.setViewportSize({ width: l, height: h });
        const r = (await page.evaluate(() => (window as unknown as { __t: { responsive(): MesuresResponsive } }).__t.responsive())) as MesuresResponsive;
        expect(r.cibles, `cibles à ${l}`).toEqual([]);
        expect(r.petits, `texte à ${l}`).toEqual([]);
        proche(r.cartes[0].largeur, r.clientWidth - 32, 1, `carte de la page à ${l}`);
      }
    });
  }
});

/* ------------------------------------------------------------------ CA11 : modes d'affichage */

test.describe('Modes d\'affichage', () => {
  test('CA11 - mouvement réduit, contraste forcé et schéma de couleurs clair', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const leo = await creerBenevole(playwright);
    const base = joursLointains();
    const a = await creerCourse(playwright, { jours: base, logo: true });
    const b = await creerCourse(playwright, { jours: base + 10 });
    await affecter(playwright, a.id, leo);
    await affecter(playwright, b.id, leo);
    await creerBenevole(playwright);

    await page.emulateMedia({ reducedMotion: 'reduce' });
    await ouvrir(page, benevoleDe(leo), '/benevole');
    await expect(page.getByTestId('benevole-ligne-course')).toHaveCount(2);
    expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
    await ouvrir(page, MASTER, '/administration/benevoles');
    expect(await css(page.getByTestId('benevole-bouton-creer'), 'transitionDuration')).toBe('0s');
    expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
    await ouvrir(page, MASTER, '/administration');
    expect(await css(page.getByTestId('lien-gestion-courses'), 'transitionDuration')).toBe('0s');
    expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
    expect(await css(page.locator('html'), 'colorScheme')).toBe('light');

    await page.emulateMedia({ reducedMotion: 'no-preference', forcedColors: 'active' });
    await ouvrir(page, benevoleDe(leo), '/benevole');
    const ligneB = ligneCourse(page, b.nom);
    await expect(ligneB).toBeVisible();
    expect(await css(ligneB.locator('.visuel-substitution'), 'backgroundImage')).toBe('none');
    for (const ligne of [ligneB, ligneCourse(page, a.nom)]) {
      expect(parseFloat(await css(ligne, 'borderTopWidth'))).toBeGreaterThanOrEqual(1);
      expect(await css(ligne, 'borderTopStyle')).not.toBe('none');
    }
    await expect(ligneB.getByTestId('benevole-course-statut')).toHaveText('En préparation');
    await expect(ligneCourse(page, a.nom).getByTestId('benevole-course-statut')).toHaveText('En préparation');
    await ouvrir(page, MASTER, '/administration/benevoles');
    const encart = page.getByRole('form', { name: 'Créer un bénévole' });
    expect(parseFloat(await css(encart, 'borderTopWidth'))).toBeGreaterThanOrEqual(1);
    expect(await css(encart, 'borderTopStyle')).not.toBe('none');
    const carte = page.getByTestId('ligne-benevole').first();
    expect(parseFloat(await css(carte, 'borderTopWidth'))).toBeGreaterThanOrEqual(1);
    expect(await css(carte, 'borderTopStyle')).not.toBe('none');
    // color-scheme : vérifié sans contraste forcé plus haut (le navigateur le recalcule en contraste forcé).
  });
});

/* ------------------------------------------------------------------ CA12 : parcours complet */

test.describe('Parcours bout en bout', () => {
  test('CA12 - l\'admin crée un bénévole, l\'affecte à une Course, le bénévole voit sa carte', async ({ browser, playwright }) => {
    test.setTimeout(150_000);
    const course = await creerCourse(playwright);
    const zoe = pseudoUnique('zoe');
    const contexteAdmin = await browser.newContext({ viewport: { width: 360, height: 640 } });
    const contexteZoe = await browser.newContext({ viewport: { width: 360, height: 640 } });
    try {
      const admin = await contexteAdmin.newPage();
      await connecterAdminMaster(admin);
      await expect(admin).toHaveURL(/\/administration$/);
      await ouvrirMenuCompte(admin);
      await admin.getByTestId('menu-lien-administration').click();
      await expect(admin.getByTestId('titre-administration')).toBeVisible();
      await admin.getByTestId('lien-gestion-benevoles').click();
      await expect(admin).toHaveURL(/\/administration\/benevoles$/);
      await admin.getByTestId('benevole-champ-pseudo').fill(zoe);
      await admin.getByTestId('benevole-champ-mot-de-passe').fill(MOT_DE_PASSE_BENEVOLE_CREE);
      await admin.getByTestId('benevole-champ-confirmation').fill(MOT_DE_PASSE_BENEVOLE_CREE);
      await admin.getByTestId('benevole-bouton-creer').click();
      await expect(admin.getByTestId('benevole-message-succes')).toHaveText(`Le bénévole ${zoe} a été créé.`);
      await expect(admin.getByTestId('ligne-benevole').filter({ hasText: zoe })).toHaveCount(1);

      // Zoe : aucune affectation, état vide
      const pageZoe = await contexteZoe.newPage();
      await ouvrirConnexion(pageZoe);
      await saisir(pageZoe, zoe, MOT_DE_PASSE_BENEVOLE_CREE);
      await pageZoe.getByTestId('bouton-connexion').click();
      await expect(pageZoe).toHaveURL(/\/benevole$/);
      await expect(pageZoe.getByTestId('accueil-benevole-vide')).toHaveText('Aucune course à scanner pour le moment.');

      // l'admin affecte Zoe par la fiche de la Course
      await admin.goto(`/administration/courses/${course.id}`);
      const caseZoe = admin.getByLabel(zoe, { exact: true });
      await expect(caseZoe).toBeVisible();
      await caseZoe.check();
      await admin.getByTestId('fiche-bouton-enregistrer').click();
      await expect(admin.getByTestId('fiche-message-succes')).toBeVisible();

      // Zoe : menu, Espace bénévole, carte de la Course
      await pageZoe.goto('/');
      await ouvrirMenuCompte(pageZoe);
      await pageZoe.getByTestId('menu-lien-espace-benevole').click();
      await expect(pageZoe).toHaveURL(/\/benevole$/);
      const ligne = ligneCourse(pageZoe, course.nom);
      await expect(ligne).toBeVisible();
      await expect(ligne.getByTestId('benevole-course-date')).toHaveText(dateAffichee(course.date));
      await expect(ligne.getByTestId('benevole-course-statut')).toHaveText('En préparation');
      await expect(ligne.locator('a, button')).toHaveCount(0);
      await expect(pageZoe.getByTestId('accueil-benevole-vide')).toHaveCount(0);

      await seDeconnecterParLeMenu(pageZoe);
      // Comportement existant (inchangé par R.8b) : la déconnexion mène à l'écran de connexion (la spec dit « accueil »).
      await expect(pageZoe).toHaveURL(/\/connexion$/);
      await expect(pageZoe.getByTestId('titre-connexion')).toBeVisible();
    } finally {
      await contexteAdmin.close();
      await contexteZoe.close();
    }
  });
});
