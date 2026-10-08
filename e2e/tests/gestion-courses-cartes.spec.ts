import { expect, test, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { join } from 'node:path';
import { PNG } from 'pngjs';
import {
  connecterAdminMaster,
  connecterParApi,
  courseDeReference,
  creerAdminParApi,
  creerCourseParApi,
  dateAffichee,
  dateDansJours,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  supprimerCourseParApi,
  MOT_DE_PASSE_ADMIN_CREE,
  type DonneesCourse,
} from './aide-admin';
import { placerStatutCourseEnBase } from './aide-coureur';
import {
  AIDES_PAGE,
  ECRANS,
  ETATS,
  RGB_PALETTE,
  RGB_TRANSPARENT,
  ouvrirScene,
  preparerJeuReference,
  type EcartCouleur,
  type JeuReference,
  type MesureContraste,
  type MesuresResponsive,
} from './aide-theme';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const FIXTURES = join(__dirname, '..', 'fixtures');
const fixture = (nom: string): string => join(FIXTURES, nom);
const AGRANDIR = 'html{font-size:32px}';
const MOTIF_LISTE = '**/api/administration/courses';
/**
 * Nom de Course unique sans la suite « qr » : la page des courses ouvertes affiche les logos (`alt` « Logo de … ») et
 * `inscription-course` CA11 y interdit tout `img[alt*="QR" i]`.
 */
const nomSansQr = (prefixe: string): string => nomCourseUnique(prefixe).replace(/qr/gi, 'q9');

const AUTORISEES = [...RGB_PALETTE, RGB_TRANSPARENT];

const SABLE = 'rgb(239, 230, 208)';
const TRAIT = 'rgb(217, 207, 182)';
const SURFACE = 'rgb(255, 252, 245)';
const FORET = 'rgb(20, 53, 42)';
const ENCRE = 'rgb(27, 42, 34)';
const ENCRE_DOUCE = 'rgb(74, 90, 80)';
const FOCUS = 'rgb(168, 67, 0)';

/* ------------------------------------------------------------------ aides */

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: Awaited<ReturnType<PlaywrightLib['request']['newContext']>>) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

interface CourseCreee {
  id: string;
  nom: string;
}

/** Date lointaine (aaaa-mm-jj) pour que les Courses du test arrivent en tête, sans croiser celles des autres tests. */
function dateLointaine(): string {
  return dateDansJours(900 + Math.floor(Math.random() * 800));
}

function jourPrecedent(iso: string, jours: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - jours);
  return d.toISOString().slice(0, 10);
}

async function creerCourse(playwright: PlaywrightLib, surcharge: Partial<DonneesCourse> = {}, statut?: 'EN_COURS' | 'TERMINEE'): Promise<CourseCreee> {
  const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom: nomSansQr('R7'), date: dateLointaine(), ...surcharge })));
  const id = course['id'] as string;
  if (statut) placerStatutCourseEnBase(id, statut);
  return { id, nom: course['nom'] as string };
}

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

async function creerCourseAvecLogo(playwright: PlaywrightLib, surcharge: Partial<DonneesCourse> = {}): Promise<CourseCreee> {
  const course = await creerCourse(playwright, surcharge);
  const contenu = pngLarge();
  await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, course.id, { nom: 'large.png', type: 'image/png', contenu }));
  return course;
}

async function ouvrirSessionMaster(page: Page): Promise<void> {
  const { PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER } = await import('./aide-admin');
  await connecterParApi(page.request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
}

async function ouvrirListe(page: Page, taille: { width: number; height: number } = { width: 1280, height: 800 }): Promise<void> {
  await page.setViewportSize(taille);
  await ouvrirSessionMaster(page);
  await page.goto('/administration/courses');
  await expect(page.getByTestId('liste-courses')).toBeVisible();
}

function carte(page: Page, nom: string): Locator {
  return page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });
}

interface Boite {
  left: number;
  top: number;
  right: number;
  bottom: number;
  width: number;
  height: number;
}

async function boite(l: Locator): Promise<Boite> {
  return l.evaluate((el) => {
    const r = el.getBoundingClientRect();
    return { left: r.left + scrollX, top: r.top + scrollY, right: r.right + scrollX, bottom: r.bottom + scrollY, width: r.width, height: r.height };
  });
}

interface CarteMesuree {
  nom: string;
  left: number;
  top: number;
  width: number;
  height: number;
  ficheBottom: number;
}

/** Cartes dont le nom contient `jeton`, dans l'ordre du DOM, avec leurs coordonnées de page. */
async function mesurerCartes(page: Page, jeton: string): Promise<CarteMesuree[]> {
  return page.evaluate((j) => {
    const sortie: CarteMesuree[] = [];
    for (const li of Array.from(document.querySelectorAll('[data-testid="ligne-course"]'))) {
      const nom = li.querySelector('[data-testid="course-nom"]')?.textContent?.trim() ?? '';
      if (!nom.includes(j)) continue;
      const r = li.getBoundingClientRect();
      const f = li.querySelector('[data-testid="course-lien-fiche"]')!.getBoundingClientRect();
      sortie.push({ nom, left: r.left + scrollX, top: r.top + scrollY, width: r.width, height: r.height, ficheBottom: f.bottom + scrollY });
    }
    return sortie;
  }, jeton);
}

const distincts = (valeurs: number[], tolerance = 1): number[] =>
  valeurs.reduce<number[]>((acc, v) => (acc.some((a) => Math.abs(a - v) <= tolerance) ? acc : [...acc, v]), []);

/** Les cartes d'une même rangée (même `top`) ont la même hauteur et leurs boutons « Fiche » alignés. */
function verifierRangees(cartes: CarteMesuree[]): void {
  for (const c of cartes) {
    const rangee = cartes.filter((x) => Math.abs(x.top - c.top) <= 1);
    for (const x of rangee) {
      expect(Math.abs(x.height - c.height), 'même hauteur sur la rangée').toBeLessThanOrEqual(1);
      expect(Math.abs(x.ficheBottom - c.ficheBottom), 'boutons Fiche alignés').toBeLessThanOrEqual(1);
    }
  }
}

async function pasDeScrollHorizontal(page: Page): Promise<void> {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  expect(m.s, 'scrollWidth <= clientWidth').toBeLessThanOrEqual(m.c);
}

async function mesures(page: Page): Promise<MesuresResponsive> {
  await page.evaluate(AIDES_PAGE);
  return page.evaluate(() => (window as unknown as { __t: { responsive(): unknown } }).__t.responsive()) as Promise<MesuresResponsive>;
}

/* ------------------------------------------------------------------ tests */

test.describe('Gestion des courses : liste en cartes', () => {
  test('CA1 - les cartes affichent toutes les données de chaque Course, dans l\'ordre de l\'API', async ({ page, browser, playwright }) => {
    const base = dateLointaine();
    const dateA = base;
    const dateB = jourPrecedent(base, 1);
    const dateC = jourPrecedent(base, 2);
    const a = await creerCourse(playwright, { date: dateA });
    const b = await creerCourse(playwright, { date: dateB, distanceBoucleMetres: 400, dureeBoucleMinutes: 1, denivelePositifBoucleMetres: 0, nombreMaxParticipants: 10, nombreMaxBoucles: 5 }, 'EN_COURS');
    const c = await creerCourse(playwright, { date: dateC, distanceBoucleMetres: 1000, dureeBoucleMinutes: 2, denivelePositifBoucleMetres: 10, nombreMaxParticipants: 2, nombreMaxBoucles: 2 }, 'TERMINEE');
    await ouvrirListe(page);

    const liste = page.getByTestId('liste-courses');
    expect(await liste.evaluate((el) => el.tagName)).toBe('UL');
    expect(await page.getByTestId('ligne-course').first().evaluate((el) => el.tagName)).toBe('LI');

    const la = carte(page, a.nom);
    await expect(la.getByTestId('course-nom')).toHaveText(a.nom);
    expect(await la.getByTestId('course-nom').evaluate((el) => el.tagName)).toBe('H3');
    await expect(la.getByTestId('course-date')).toHaveText(dateAffichee(dateA));
    await expect(la.getByTestId('course-date')).toHaveAttribute('datetime', dateA);
    await expect(la.getByTestId('course-statut')).toHaveText('En préparation');
    await expect(la.getByTestId('course-statut')).toHaveClass(/pastille-statut pastille-statut--en-preparation/);
    await expect(la.getByTestId('course-distance')).toHaveText('6706 m');
    await expect(la.getByTestId('course-duree')).toHaveText('60 min');
    await expect(la.getByTestId('course-denivele')).toHaveText('120 m');
    await expect(la.getByTestId('course-participants-max')).toHaveText('50');
    await expect(la.getByTestId('course-boucles-max')).toHaveText('24');
    await expect(la.getByTestId('course-lien-fiche')).toHaveText('Fiche');
    await expect(la.getByTestId('course-lien-fiche')).toHaveAttribute('href', `/administration/courses/${a.id}`);
    await expect(la.getByTestId('course-bouton-modifier')).toHaveText('Modifier');
    await expect(la.getByTestId('course-bouton-logo')).toHaveText('Choisir un logo');
    await expect(la.getByTestId('course-bouton-supprimer')).toHaveText('Supprimer la course');

    const lb = carte(page, b.nom);
    await expect(lb.getByTestId('course-statut')).toHaveText('En cours');
    await expect(lb.getByTestId('course-statut')).toHaveClass(/pastille-statut pastille-statut--en-cours/);
    await expect(lb.getByTestId('course-distance')).toHaveText('400 m');
    await expect(lb.getByTestId('course-duree')).toHaveText('1 min');
    await expect(lb.getByTestId('course-denivele')).toHaveText('0 m');
    await expect(lb.getByTestId('course-participants-max')).toHaveText('10');
    await expect(lb.getByTestId('course-boucles-max')).toHaveText('5');
    await expect(lb.getByTestId('course-lien-fiche')).toHaveAttribute('href', `/administration/courses/${b.id}`);

    const lc = carte(page, c.nom);
    await expect(lc.getByTestId('course-statut')).toHaveText('Terminée');
    await expect(lc.getByTestId('course-statut')).toHaveClass(/pastille-statut pastille-statut--terminee/);
    await expect(lc.getByTestId('course-distance')).toHaveText('1000 m');
    await expect(lc.getByTestId('course-denivele')).toHaveText('10 m');
    await expect(lc.getByTestId('course-lien-fiche')).toHaveAttribute('href', `/administration/courses/${c.id}`);

    for (const l of [lb, lc]) {
      await expect(l.getByTestId('course-bouton-modifier')).toHaveCount(0);
      await expect(l.getByTestId('course-bouton-logo')).toHaveCount(0);
      await expect(l.getByTestId('course-bouton-supprimer')).toHaveCount(0);
      await expect(l.getByTestId('course-lien-fiche')).toHaveCount(1);
    }
    for (const l of [la, lb, lc]) {
      await expect(l.getByTestId('course-nom')).toHaveCount(1);
      await expect(l.getByTestId('course-statut')).toHaveCount(1);
    }

    // ordre = celui de l'API
    const api = await page.request.get('/api/administration/courses');
    expect(api.status()).toBe(200);
    const nomsApi = ((await api.json()) as Array<{ nom: string }>).map((x) => x.nom).filter((n) => [a.nom, b.nom, c.nom].includes(n));
    const nomsDom = (await page.getByTestId('course-nom').allTextContents()).map((n) => n.trim()).filter((n) => [a.nom, b.nom, c.nom].includes(n));
    expect(nomsDom).toEqual(nomsApi);
    expect(nomsDom).toEqual([a.nom, b.nom, c.nom]);

    // admin non master : aucune suppression
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    const contexte = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    try {
      const pageAdmin = await contexte.newPage();
      await connecterParApi(contexte.request, admin, MOT_DE_PASSE_ADMIN_CREE);
      await pageAdmin.goto('/administration/courses');
      await expect(carte(pageAdmin, a.nom)).toBeVisible();
      await expect(carte(pageAdmin, a.nom).getByTestId('course-bouton-modifier')).toHaveCount(1);
      await expect(pageAdmin.getByTestId('course-bouton-supprimer')).toHaveCount(0);
    } finally {
      await contexte.close();
    }
  });

  test('CA2 - le visuel est le logo sans déformation ou un visuel de substitution de même zone 16/9', async ({ page, playwright }) => {
    const a = await creerCourse(playwright);
    const d = await creerCourseAvecLogo(playwright);
    await ouvrirListe(page);

    const la = carte(page, a.nom);
    const absent = la.getByTestId('course-logo-absent');
    await expect(absent).toBeVisible();
    await expect(absent).toHaveText('Aucun logo');
    await expect(la.getByTestId('course-logo')).toHaveCount(0);
    const styleA = await absent.evaluate((el) => {
      const cs = getComputedStyle(el);
      const texte = el.querySelector('*') as HTMLElement | null;
      const rt = texte?.getBoundingClientRect();
      return { image: cs.backgroundImage, fond: cs.backgroundColor, bordure: cs.borderTopColor, texteL: rt?.width ?? -1, texteH: rt?.height ?? -1 };
    });
    expect(styleA.image).toContain('data:image/svg+xml');
    expect(styleA.fond).toBe(SABLE);
    expect(styleA.bordure).toBe(TRAIT);
    expect(styleA.texteL, 'texte masqué : 1 px de large').toBeLessThanOrEqual(1);
    expect(styleA.texteH, 'texte masqué : 1 px de haut').toBeLessThanOrEqual(1);
    const zoneA = await boite(absent);
    expect(Math.abs(zoneA.width / zoneA.height - 16 / 9)).toBeLessThan(0.02);

    const ld = carte(page, d.nom);
    const logo = ld.getByTestId('course-logo');
    await expect(logo).toBeVisible();
    await expect(logo).toHaveAttribute('alt', `Logo de ${d.nom}`);
    await expect(logo).toHaveAttribute('src', new RegExp(`^/api/courses/${d.id}/logo\\?v=`));
    await expect(ld.getByTestId('course-logo-absent')).toHaveCount(0);
    const infos = await logo.evaluate((el) => {
      const img = el as HTMLImageElement;
      return { nw: img.naturalWidth, nh: img.naturalHeight, ajuste: getComputedStyle(img).objectFit };
    });
    expect(infos.nw).toBeGreaterThan(0);
    expect(infos.ajuste).toBe('contain');
    expect(infos.nw / infos.nh).toBe(2);
    const zoneD = await boite(logo);
    expect(Math.abs(zoneD.width - zoneA.width)).toBeLessThanOrEqual(1);
    expect(Math.abs(zoneD.height - zoneA.height)).toBeLessThanOrEqual(1);
    // image contenue dans la zone, sans déformation : rendu = min(largeur, 2 x hauteur)
    const rendu = { w: Math.min(zoneD.width, zoneD.height * 2), h: Math.min(zoneD.width, zoneD.height * 2) / 2 };
    expect(rendu.w).toBeLessThanOrEqual(zoneD.width + 0.5);
    expect(rendu.h).toBeLessThanOrEqual(zoneD.height + 0.5);

    // le visuel est le premier élément visible de la carte, au-dessus de la date
    for (const [l, visuel] of [[la, absent], [ld, logo]] as const) {
      const v = await boite(visuel);
      const date = await boite(l.getByTestId('course-date'));
      const sommet = await l.evaluate((el) => Math.min(...Array.from(el.querySelectorAll('*')).filter((e) => e.checkVisibility() && e.getBoundingClientRect().width > 1).map((e) => e.getBoundingClientRect().top + scrollY)));
      expect(v.bottom).toBeLessThanOrEqual(date.top + 0.5);
      expect(Math.abs(v.top - sommet)).toBeLessThanOrEqual(1);
    }
  });

  test('CA3 - les actions de logo sont dans la carte, sous le visuel, avec aperçu, envoi, suppression et erreurs locales', async ({ page, playwright }) => {
    const a = await creerCourse(playwright);
    const b = await creerCourse(playwright, {}, 'EN_COURS');
    await ouvrirListe(page);
    const la = carte(page, a.nom);

    await expect(la.getByTestId('course-logo-absent')).toBeVisible();
    await la.getByTestId('course-champ-logo').setInputFiles(fixture('logo.png'));
    const apercu = la.getByTestId('course-logo-apercu');
    await expect(apercu).toBeVisible();
    const tailleApercu = await boite(apercu);
    expect(Math.round(tailleApercu.width)).toBe(64);
    expect(Math.round(tailleApercu.height)).toBe(64);
    await expect(la.getByTestId('course-bouton-logo-envoyer')).toBeVisible();
    await expect(la.getByTestId('course-bouton-logo-annuler')).toBeVisible();

    const avant = await boite(la);
    const nombreAvant = await page.getByTestId('ligne-course').count();
    const rangs = async (): Promise<string[]> => (await page.getByTestId('course-nom').allTextContents()).map((n) => n.trim()).filter((n) => n === a.nom || n === b.nom);
    const ordreAvant = await rangs();
    await la.getByTestId('course-bouton-logo-envoyer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`Le logo de la course ${a.nom} a été enregistré.`);
    await expect(la.getByTestId('course-logo')).toBeVisible();
    await expect(la.getByTestId('course-logo-absent')).toHaveCount(0);
    await expect(la.getByTestId('course-bouton-logo-supprimer')).toBeVisible();
    // la carte garde sa place dans la grille (la base E2E est partagée : la position absolue n'est comparée
    // que si aucune autre Course n'est apparue entre-temps)
    expect(await rangs()).toEqual(ordreAvant);
    if ((await page.getByTestId('ligne-course').count()) === nombreAvant) {
      const apres = await boite(la);
      expect(Math.abs(apres.left - avant.left)).toBeLessThanOrEqual(1);
      expect(Math.abs(apres.top - avant.top)).toBeLessThanOrEqual(1);
    }

    // boutons dans la carte et sous le visuel
    const rc = await boite(la);
    const rv = await boite(la.getByTestId('course-logo'));
    for (const id of ['course-bouton-logo', 'course-bouton-logo-supprimer']) {
      const r = await boite(la.getByTestId(id));
      expect(r.top, `${id} sous le visuel`).toBeGreaterThanOrEqual(rv.bottom - 0.5);
      expect(r.left).toBeGreaterThanOrEqual(rc.left);
      expect(r.right).toBeLessThanOrEqual(rc.right);
      expect(r.bottom).toBeLessThanOrEqual(rc.bottom);
    }

    await la.getByTestId('course-bouton-logo-supprimer').click();
    await expect(la.getByTestId('course-logo-absent')).toBeVisible();
    await expect(la.getByTestId('course-logo')).toHaveCount(0);

    // erreurs : dans la carte concernée seulement
    const gros = Buffer.concat([pngLarge(), Buffer.alloc(2_097_153)]);
    const cas: Array<{ fichier: string | { name: string; mimeType: string; buffer: Buffer }; message: string; bloque: boolean }> = [
      { fichier: fixture('faux-logo.png'), message: 'Ce fichier n\'est pas une image lisible.', bloque: true },
      { fichier: fixture('image.gif'), message: 'Le logo doit être une image PNG, JPEG ou WebP.', bloque: false },
      { fichier: { name: 'gros.png', mimeType: 'image/png', buffer: gros }, message: 'Le logo ne doit pas dépasser 2 Mo.', bloque: false },
    ];
    for (const c of cas) {
      await la.getByTestId('course-champ-logo').setInputFiles(c.fichier);
      if (!c.bloque) {
        await expect(la.getByTestId('course-bouton-logo-envoyer')).toBeVisible();
        await la.getByTestId('course-bouton-logo-envoyer').click();
      }
      await expect(la.getByTestId('course-logo-erreur')).toHaveText(c.message);
      await expect(page.getByTestId('course-logo-erreur')).toHaveCount(1);
    }

    const lb = carte(page, b.nom);
    await expect(lb.getByTestId('course-bouton-logo')).toHaveCount(0);
    await expect(lb.getByTestId('course-bouton-logo-supprimer')).toHaveCount(0);
    await expect(lb.getByTestId('course-champ-logo')).toHaveCount(0);
  });

  test('CA4 - la grille compte 3, 2 ou 1 colonnes selon la largeur, cartes alignées', async ({ page, playwright }) => {
    const date = dateLointaine();
    const u = nomSansQr('R7g').replace(/\s+/g, '-');
    const noms = [
      `${u} a`,
      `${u} b ${'mot '.repeat(18)}`.trim().slice(0, 100),
      `${u} c`,
      `${u} d ${'long '.repeat(10)}`.trim().slice(0, 100),
    ];
    const creees: CourseCreee[] = [];
    for (const nom of noms) creees.push(await creerCourse(playwright, { nom, date }));
    await ouvrirListe(page, { width: 1280, height: 800 });
    await expect(carte(page, creees[3].nom)).toBeVisible();
    const attendu = creees.map((c) => c.nom);

    // 1280 : 3 colonnes de 314 px
    const section = page.locator('section.carte');
    expect(Math.round((await boite(section)).width)).toBe(1024);
    let cartes = await mesurerCartes(page, u);
    expect(cartes.map((c) => c.nom)).toEqual(attendu);
    expect(distincts(cartes.map((c) => c.left))).toHaveLength(3);
    for (const c of cartes) expect(Math.abs(c.width - 314)).toBeLessThanOrEqual(1);
    expect(cartes[3].top).toBeGreaterThan(cartes[0].top + 1);
    verifierRangees(cartes);
    // ordre de lecture = ordre du DOM
    const lecture = [...cartes].sort((x, y) => (Math.abs(x.top - y.top) > 1 ? x.top - y.top : x.left - y.left)).map((c) => c.nom);
    expect(lecture).toEqual(attendu);

    // 768 : 2 colonnes de 335 px
    await page.setViewportSize({ width: 768, height: 1024 });
    expect(Math.round((await boite(section)).width)).toBe(736);
    cartes = await mesurerCartes(page, u);
    expect(distincts(cartes.map((c) => c.left))).toHaveLength(2);
    for (const c of cartes) expect(Math.abs(c.width - 335)).toBeLessThanOrEqual(1);
    verifierRangees(cartes);

    // 640 x 360 : 1 colonne
    await page.setViewportSize({ width: 640, height: 360 });
    cartes = await mesurerCartes(page, u);
    expect(distincts(cartes.map((c) => c.left))).toHaveLength(1);

    // 360 x 640 : carte de page 328 px à 16 px du bord, 1 colonne de 278 px
    await page.setViewportSize({ width: 360, height: 640 });
    const m = await mesures(page);
    expect(m.cartes).toHaveLength(1);
    expect(Math.abs(m.cartes[0].largeur - 328)).toBeLessThanOrEqual(1);
    expect(Math.round(m.cartes[0].gauche)).toBe(16);
    cartes = await mesurerCartes(page, u);
    expect(distincts(cartes.map((c) => c.left))).toHaveLength(1);
    for (const c of cartes) expect(Math.abs(c.width - 278)).toBeLessThanOrEqual(1);
    await pasDeScrollHorizontal(page);

    // une seule Course à 1280 : la carte garde sa largeur de colonne
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.route(MOTIF_LISTE, async (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      const reponse = await route.fetch();
      const liste = (await reponse.json()) as Array<{ nom: string }>;
      return route.fulfill({ response: reponse, json: liste.filter((c) => c.nom === creees[0].nom) });
    });
    await page.reload();
    await expect(page.getByTestId('ligne-course')).toHaveCount(1);
    const seule = await boite(page.getByTestId('ligne-course'));
    expect(Math.abs(seule.width - 314)).toBeLessThanOrEqual(1);
  });

  test('CA5 - styles de la carte, de l\'en-tête, des indicateurs et palette / contrastes', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const a = await creerCourse(playwright);
    await ouvrirListe(page);
    const la = carte(page, a.nom);

    const li = await la.evaluate((el) => {
      const cs = getComputedStyle(el);
      return {
        fond: cs.backgroundColor, bordure: cs.borderTopWidth, couleurBordure: cs.borderTopColor, rayon: cs.borderTopLeftRadius,
        ombre: cs.boxShadow, padding: cs.paddingTop, classes: el.className,
        interdits: el.querySelectorAll('svg, canvas, img:not([data-testid="course-logo"]):not([data-testid="course-logo-apercu"])').length,
      };
    });
    expect(li.fond).toBe(SURFACE);
    expect(li.bordure).toBe('1px');
    expect(li.couleurBordure).toBe(TRAIT);
    expect(li.rayon).toBe('12px');
    expect(li.ombre).not.toBe('none');
    expect(li.padding).toBe('16px');
    expect(li.classes.split(/\s+/)).not.toContain('carte');
    expect(li.interdits, 'ni svg, ni canvas, ni img décoratif').toBe(0);

    const style = (id: string) => la.getByTestId(id).evaluate((el) => {
      const cs = getComputedStyle(el);
      return { taille: cs.fontSize, graisse: cs.fontWeight, couleur: cs.color, balise: el.tagName };
    });
    expect(await style('course-date')).toMatchObject({ taille: '20px', graisse: '700', couleur: FORET });
    expect(await style('course-nom')).toMatchObject({ balise: 'H3', taille: '18px', graisse: '700', couleur: FORET });
    expect((await style('course-statut')).taille).toBe('13px');
    await expect(la.getByTestId('course-statut')).toHaveClass(/pastille-statut pastille-statut--en-preparation/);

    const indicateurs = await la.evaluate((el) => {
      const sortie = [];
      for (const ind of Array.from(el.querySelectorAll('.indicateur'))) {
        const av = getComputedStyle(ind, '::before');
        const dt = ind.querySelector('dt')!;
        const dd = ind.querySelector('dd')!;
        const cdt = getComputedStyle(dt);
        const cdd = getComputedStyle(dd);
        sortie.push({
          largeur: av.width, hauteur: av.height, contenu: av.content, masque: av.maskImage || av.getPropertyValue('-webkit-mask-image'),
          fond: av.backgroundColor, libelle: dt.textContent?.trim(), tailleDt: cdt.fontSize, couleurDt: cdt.color,
          tailleDd: cdd.fontSize, graisseDd: cdd.fontWeight, couleurDd: cdd.color,
        });
      }
      return sortie;
    });
    expect(indicateurs).toHaveLength(5);
    expect(indicateurs.map((i) => i.libelle)).toEqual(['Distance', 'Durée', 'Dénivelé positif', 'Participants max.', 'Boucles max.']);
    for (const i of indicateurs) {
      expect(i.largeur).toBe('24px');
      expect(i.hauteur).toBe('24px');
      expect(i.contenu).not.toBe('none');
      expect(i.masque).toContain('data:image/svg+xml');
      expect(i.fond).toBe(FORET);
      expect(i.tailleDt).toBe('14px');
      expect(i.couleurDt).toBe(ENCRE_DOUCE);
      expect(i.tailleDd).toBe('16px');
      expect(i.graisseDd).toBe('600');
      expect(i.couleurDd).toBe(ENCRE);
    }
    expect(new Set(indicateurs.map((i) => i.masque)).size, 'pictogrammes tous différents').toBe(5);

    // palette et contrastes sur les scènes de l'écran
    const jeu: JeuReference = await preparerJeuReference(playwright);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    for (const nom of ['gestion des courses', 'gestion des courses avec confirmation de suppression']) {
      const scene = [...ECRANS, ...ETATS].find((s) => s.nom === nom)!;
      await page.context().clearCookies();
      const nettoyage = await ouvrirScene(page, scene, jeu);
      await page.mouse.move(0, 0);
      const ecarts = (await page.evaluate((x) => (window as unknown as { __t: { horsPalette(a: string[]): EcartCouleur[] } }).__t.horsPalette(x), AUTORISEES));
      expect(ecarts, `${nom} : couleurs hors palette`).toEqual([]);
      const ms = (await page.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
      expect(ms.length).toBeGreaterThan(0);
      expect(ms.filter((m) => m.ratio + 1e-9 < m.seuil).map((m) => `${m.element} ${m.ratio.toFixed(2)} < ${m.seuil}`), `${nom} : contrastes`).toEqual([]);
      await nettoyage?.();
    }
  });

  test('CA6 - 320 px, 360 px et texte à 200 % : rien ne déborde des cartes ni de la page', async ({ page, playwright }) => {
    test.setTimeout(180_000);
    const date = dateLointaine();
    // 100 caractères sans espace, sans suite de 43 caractères alphanumériques (qui ressemblerait à un jeton QR
    // pour le test « inscription-course » CA11, qui lit la page des courses ouvertes).
    const NOM_SANS_ESPACE = `${'W'.repeat(40)}.${'W'.repeat(40)}.${'W'.repeat(18)}`;
    const sansEspace = await creerCourse(playwright, { nom: NOM_SANS_ESPACE, date });
    try {
      const avecEspaces = await creerCourse(playwright, { nom: `${nomSansQr('Rx')} ${'mot '.repeat(30)}`.trim().slice(0, 100), date });
      const a = await creerCourse(playwright, { date });
      await ouvrirSessionMaster(page);

      const etats = ['liste simple', 'confirmation de suppression', 'aperçu de logo', 'erreur de logo'] as const;
      async function ouvrirEtat(etat: (typeof etats)[number]): Promise<void> {
        const cible = page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(NOM_SANS_ESPACE, { exact: true }) }).first();
        if (etat === 'confirmation de suppression') {
          await cible.getByTestId('course-bouton-supprimer').click();
          await expect(cible.getByTestId('course-confirmation-suppression')).toBeVisible();
        } else if (etat === 'aperçu de logo') {
          await cible.getByTestId('course-champ-logo').setInputFiles(fixture('logo.png'));
          await expect(cible.getByTestId('course-logo-apercu')).toBeVisible();
        } else if (etat === 'erreur de logo') {
          await cible.getByTestId('course-champ-logo').setInputFiles(fixture('faux-logo.png'));
          await expect(cible.getByTestId('course-logo-erreur')).toBeVisible();
        }
      }

      const tousDebordements: string[] = [];
      for (const [largeur, hauteur] of [[320, 640], [360, 640], [1280, 800]] as const) {
        for (const etat of etats) {
          await page.setViewportSize({ width: largeur, height: hauteur });
          await page.goto('/administration/courses');
          await expect(page.getByTestId('liste-courses')).toBeVisible();
          await page.addStyleTag({ content: AGRANDIR });
          await ouvrirEtat(etat);
          const contexte = `${largeur}px, ${etat}`;
          await pasDeScrollHorizontal(page);

          const debordements = await page.evaluate(() => {
            const sortie: string[] = [];
            for (const li of Array.from(document.querySelectorAll('[data-testid="ligne-course"]'))) {
              const rc = li.getBoundingClientRect();
              for (const d of Array.from(li.querySelectorAll('*'))) {
                if (!d.checkVisibility()) continue;
                const r = d.getBoundingClientRect();
                if (r.width === 0 && r.height === 0) continue;
                if (r.left < rc.left - 0.5 || r.right > rc.right + 0.5) {
                  sortie.push(`${d.getAttribute('data-testid') ?? d.tagName}.${(d as HTMLElement).className} ${r.left.toFixed(1)}-${r.right.toFixed(1)} hors ${rc.left.toFixed(1)}-${rc.right.toFixed(1)}`);
                }
              }
            }
            return sortie;
          });
          if (debordements.length > 0) tousDebordements.push(`${contexte} : ${debordements.length} descendant(s) hors de la carte, dont ${debordements[0]}`);

          if (largeur < 400) {
            const cartes = await mesurerCartes(page, '');
            expect(distincts(cartes.map((c) => c.left)), `${contexte} : 1 colonne`).toHaveLength(1);
          }
          const nom = page.getByTestId('course-nom').filter({ hasText: NOM_SANS_ESPACE }).first();
          expect(await nom.evaluate((el) => getComputedStyle(el).overflowWrap)).toBe('anywhere');
          expect(((await nom.textContent()) ?? '').trim()).toHaveLength(100);
        }
      }
      expect(tousDebordements, 'descendants qui dépassent de leur carte (texte à 200 %)').toEqual([]);
      expect([sansEspace.nom, avecEspaces.nom, a.nom]).toHaveLength(3);

      // sans le texte à 200 % : carte de la page à 16 px des bords
      for (const largeur of [320, 360]) {
        await page.setViewportSize({ width: largeur, height: 640 });
        await page.goto('/administration/courses');
        await expect(page.getByTestId('liste-courses')).toBeVisible();
        const mp = await mesures(page);
        expect(mp.cartes).toHaveLength(1);
        expect(Math.abs(mp.cartes[0].largeur - (mp.clientWidth - 32)), `${largeur}px : carte de page`).toBeLessThanOrEqual(1);
        expect(Math.round(mp.cartes[0].gauche)).toBe(16);
        await pasDeScrollHorizontal(page);
      }

      // sans le texte à 200 %, à 360 px : cibles et tailles de texte
      await page.setViewportSize({ width: 360, height: 640 });
      await page.goto('/administration/courses');
      await expect(carte(page, a.nom)).toBeVisible();
      const m = await mesures(page);
      expect(m.cibles, 'cibles < 44 px').toEqual([]);
      expect(m.petits, 'textes < 13 px').toEqual([]);

    } finally {
      // Le nom de 100 « W » ressemblerait à un jeton QR dans la page des courses ouvertes : on retire la Course.
      await avecApi(playwright, (ctx) => supprimerCourseParApi(ctx, sansEspace.id));
    }
  });

  test('CA7 - l\'état vide affiche le visuel, le message et l\'aide, puis cède la place à la première carte', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    await ouvrirSessionMaster(page);
    let declaree = false;
    await page.route(MOTIF_LISTE, async (route) => {
      const methode = route.request().method();
      if (methode === 'GET' && !declaree) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      }
      if (methode === 'POST') {
        const reponse = await route.fetch();
        declaree = true;
        return route.fulfill({ response: reponse });
      }
      return route.continue();
    });

    const verifier = async (contexte: string): Promise<void> => {
      await page.goto('/administration/courses');
      const vide = page.getByTestId('courses-vide');
      await expect(vide).toBeVisible();
      await expect(vide).toHaveText('Aucune course pour le moment.');
      await expect(page.getByTestId('liste-courses')).toHaveCount(0);
      const aide = page.getByText('Utilisez le formulaire ci-dessous pour déclarer la première course.', { exact: true });
      await expect(aide).toBeVisible();
      await expect(vide).not.toContainText('Utilisez');
      const bloc = page.locator('.etat-vide').filter({ has: vide });
      const s = await bloc.evaluate((el) => {
        const cs = getComputedStyle(el);
        const visuel = el.querySelector('.etat-vide__visuel') as HTMLElement;
        const rv = visuel.getBoundingClientRect();
        const rb = el.getBoundingClientRect();
        const debord = Array.from(el.querySelectorAll('*')).filter((e) => {
          const r = e.getBoundingClientRect();
          return r.left < rb.left - 0.5 || r.right > rb.right + 0.5;
        }).length;
        return {
          fond: cs.backgroundColor, style: cs.borderTopStyle, largeur: cs.borderTopWidth, couleur: cs.borderTopColor,
          visuelL: rv.width, visuelH: rv.height, image: getComputedStyle(visuel).backgroundImage, debord,
        };
      });
      expect(s.fond, contexte).toBe(SABLE);
      expect(s.style).toBe('dashed');
      expect(s.largeur).toBe('1px');
      expect(s.couleur).toBe(TRAIT);
      expect(Math.abs(s.visuelL / s.visuelH - 16 / 9)).toBeLessThan(0.02);
      expect(s.visuelL).toBeLessThanOrEqual(192.5);
      expect(s.image).toContain('data:image/svg+xml');
      expect(s.debord, `${contexte} : rien ne déborde du bloc`).toBe(0);
      await pasDeScrollHorizontal(page);
      const carteDePage = await page.evaluate(() => {
        const c = document.querySelector('section.carte')!.getBoundingClientRect();
        return { g: c.left, d: c.right, w: document.documentElement.clientWidth };
      });
      expect(carteDePage.d).toBeLessThanOrEqual(carteDePage.w + 0.5);
      await expect(page.getByTestId('course-champ-nom')).toBeEnabled();
    };

    await page.setViewportSize({ width: 1280, height: 800 });
    await verifier('1280');
    await page.setViewportSize({ width: 360, height: 640 });
    await verifier('360');
    await page.setViewportSize({ width: 320, height: 640 });
    await page.goto('/administration/courses');
    await page.addStyleTag({ content: AGRANDIR });
    await expect(page.getByTestId('courses-vide')).toBeVisible();
    await pasDeScrollHorizontal(page);
    await expect(page.getByText('Utilisez le formulaire ci-dessous pour déclarer la première course.', { exact: true })).toBeVisible();

    // contraste et palette sur la scène dédiée du harnais
    const scene = ETATS.find((e) => e.nom === 'gestion des courses vide');
    expect(scene, 'scène « gestion des courses vide » présente').toBeDefined();
    const jeu = await preparerJeuReference(playwright);
    const contexte2 = await page.context().browser()!.newContext({ viewport: { width: 1280, height: 800 } });
    try {
      const page2 = await contexte2.newPage();
      await ouvrirScene(page2, scene!, jeu);
      await page2.mouse.move(0, 0);
      const ecarts = await page2.evaluate((x) => (window as unknown as { __t: { horsPalette(a: string[]): EcartCouleur[] } }).__t.horsPalette(x), AUTORISEES);
      expect(ecarts, 'couleurs hors palette').toEqual([]);
      const ms = (await page2.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
      expect(ms.length).toBeGreaterThan(0);
      expect(ms.filter((m) => m.ratio + 1e-9 < m.seuil).map((m) => `${m.element} ${m.ratio.toFixed(2)} < ${m.seuil}`)).toEqual([]);
    } finally {
      await contexte2.close();
    }

    // première déclaration : la carte remplace l'état vide
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/administration/courses');
    await expect(page.getByTestId('courses-vide')).toBeVisible();
    const nom = nomSansQr('R7v');
    await page.getByTestId('course-champ-nom').fill(nom);
    await page.getByTestId('course-champ-date').fill(dateAffichee(dateLointaine()));
    await page.getByTestId('course-champ-distance').fill('1000');
    await page.getByTestId('course-champ-duree').fill('2');
    await page.getByTestId('course-champ-denivele').fill('10');
    await page.getByTestId('course-champ-participants-max').fill('20');
    await page.getByTestId('course-champ-boucles-max').fill('5');
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été déclarée.`);
    await expect(page.getByTestId('courses-vide')).toHaveCount(0);
    await expect(carte(page, nom)).toBeVisible();
  });

  test('CA8 - l\'ordre de Tab suit les cartes, contours entiers visibles, Entrée sur Fiche et Modifier', async ({ page, playwright }) => {
    test.setTimeout(180_000);
    // Dates tout en haut de la plage admise (5 ans) pour que les trois cartes ouvrent la liste : peu d'arrêts de Tab avant elles.
    const base = dateDansJours(1760 + Math.floor(Math.random() * 40));
    const d = await creerCourseAvecLogo(playwright, { date: base });
    const a = await creerCourse(playwright, { date: jourPrecedent(base, 1) });
    const b = await creerCourse(playwright, { date: jourPrecedent(base, 2) }, 'EN_COURS');
    await ouvrirListe(page);
    await page.evaluate(AIDES_PAGE);
    const supprimableB = (await carte(page, b.nom).getByTestId('course-bouton-supprimer').count()) === 1;

    await page.mouse.move(0, 0);
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    interface Arret {
      testid: string | null;
      nom: string | null;
      outline: string;
      conforme: boolean;
      visibleEntier: boolean;
      ancetreRogne: string | null;
    }
    const arrets: Arret[] = [];
    const noms = [d.nom, a.nom, b.nom];
    for (let i = 0; i < 400; i++) {
      await page.keyboard.press('Tab');
      const arret = await page.evaluate((): Arret | null => {
        const el = document.activeElement as HTMLElement | null;
        if (!el || el === document.body) return null;
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        let rogne: string | null = null;
        for (let p = el.parentElement; p && p.tagName !== 'MAIN'; p = p.parentElement) {
          const o = getComputedStyle(p);
          if (o.overflowX !== 'visible' || o.overflowY !== 'visible') rogne = `${p.tagName}.${p.className}`;
        }
        return {
          testid: el.getAttribute('data-testid'),
          nom: el.closest('[data-testid="ligne-course"]')?.querySelector('[data-testid="course-nom"]')?.textContent?.trim() ?? null,
          outline: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`,
          conforme: cs.outlineStyle === 'solid' && cs.outlineWidth === '3px' && cs.outlineColor === 'rgb(168, 67, 0)',
          visibleEntier: r.left - 5 >= 0 && r.right + 5 <= document.documentElement.clientWidth,
          ancetreRogne: rogne,
        };
      });
      if (!arret) break;
      arrets.push(arret);
      if (arret.nom === b.nom && arret.testid === 'course-lien-fiche' && !supprimableB) {
        // dernier arrêt attendu pour B (aucune autre action) : continuer d'un cran pour vérifier qu'il n'y en a pas d'autre
      }
      if (arret.nom !== null && !noms.includes(arret.nom) && arrets.some((x) => x.nom === b.nom)) break;
    }
    const miens = arrets.filter((x) => x.nom !== null && noms.includes(x.nom));
    const sequence = (nom: string) => miens.filter((x) => x.nom === nom).map((x) => x.testid);
    expect(sequence(d.nom)).toEqual(['course-bouton-logo', 'course-bouton-logo-supprimer', 'course-lien-fiche', 'course-bouton-modifier', 'course-bouton-supprimer']);
    expect(sequence(a.nom)).toEqual(['course-bouton-logo', 'course-lien-fiche', 'course-bouton-modifier', 'course-bouton-supprimer']);
    expect(sequence(b.nom)).toEqual(supprimableB ? ['course-lien-fiche', 'course-bouton-supprimer'] : ['course-lien-fiche']);
    expect(miens.map((x) => x.nom)).toEqual([
      ...Array(5).fill(d.nom), ...Array(4).fill(a.nom), ...Array(supprimableB ? 2 : 1).fill(b.nom),
    ]);
    for (const x of arrets) {
      expect(x.testid, 'ni le champ fichier, ni le li, ni le nom ne sont des arrêts').not.toMatch(/^(course-champ-logo|ligne-course|course-nom)$/);
    }
    for (const x of miens) {
      expect(x.outline, `${x.testid} : contour`).toBe(`solid 3px ${FOCUS}`);
      expect(x.visibleEntier, `${x.testid} : contour entier dans la page`).toBe(true);
      expect(x.ancetreRogne, `${x.testid} : ancêtre avec overflow`).toBeNull();
    }

    // Entrée sur Fiche
    const la = carte(page, a.nom);
    await la.getByTestId('course-lien-fiche').focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/administration/courses/${a.id}$`));

    // Entrée sur Modifier
    await page.goto('/administration/courses');
    await expect(page.getByTestId('liste-courses')).toBeVisible();
    await carte(page, a.nom).getByTestId('course-bouton-modifier').focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('course-champ-nom')).toBeFocused();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(a.nom);
  });

  test('CA9 - mouvement réduit et contraste forcé', async ({ page, playwright }) => {
    const a = await creerCourse(playwright);
    const d = await creerCourseAvecLogo(playwright);
    await ouvrirListe(page);
    const la = carte(page, a.nom);
    const ld = carte(page, d.nom);

    expect(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme)).toBe('light');
    await page.emulateMedia({ reducedMotion: 'reduce' });
    expect(await la.getByTestId('course-lien-fiche').evaluate((el) => getComputedStyle(el).transitionDuration)).toBe('0s');
    expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);

    await page.emulateMedia({ reducedMotion: 'no-preference', forcedColors: 'active' });
    const absent = la.getByTestId('course-logo-absent');
    await expect(absent).toBeVisible();
    await expect(absent).toHaveText('Aucun logo');
    expect(await absent.evaluate((el) => getComputedStyle(el).backgroundImage)).toBe('none');
    const forces = await la.evaluate((el) => {
      const sonde = document.createElement('div');
      sonde.style.color = 'CanvasText';
      document.body.appendChild(sonde);
      const canvasText = getComputedStyle(sonde).color;
      sonde.remove();
      const fonds = Array.from(el.querySelectorAll('.indicateur')).map((i) => getComputedStyle(i, '::before').backgroundColor);
      const cs = getComputedStyle(el);
      const fiche = getComputedStyle(el.querySelector('[data-testid="course-lien-fiche"]')!);
      return {
        canvasText, fonds, bordure: cs.borderTopWidth, styleBordure: cs.borderTopStyle,
        bordureFiche: fiche.borderTopWidth, styleFiche: fiche.borderTopStyle,
      };
    });
    expect(forces.fonds).toHaveLength(5);
    for (const f of forces.fonds) {
      expect(f).not.toBe(RGB_TRANSPARENT);
      expect(f).toBe(forces.canvasText);
    }
    expect(forces.bordure).toBe('1px');
    expect(forces.styleBordure).not.toBe('none');
    expect(forces.bordureFiche).not.toBe('0px');
    expect(forces.styleFiche).not.toBe('none');
    await expect(la.getByTestId('course-statut')).toHaveText('En préparation');
    await expect(la.getByTestId('course-lien-fiche')).toBeVisible();
    await expect(ld.getByTestId('course-logo')).toBeVisible();
  });

  test('CA10 - parcours nominal à 360 x 640 : déclarer, envoyer un logo, modifier, ouvrir la fiche', async ({ page }) => {
    test.setTimeout(120_000);
    await page.setViewportSize({ width: 360, height: 640 });
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto('/administration/courses');
    await expect(page.getByTestId('liste-courses')).toBeVisible();
    await expect(page.getByTestId('ligne-course').first()).toBeVisible();

    const nom = `essai-r7-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`.replace(/qr/gi, 'q9');
    await page.getByTestId('course-champ-nom').fill(nom);
    await page.getByTestId('course-champ-date').fill(dateAffichee(dateLointaine()));
    await page.getByTestId('course-champ-distance').fill('1000');
    await page.getByTestId('course-champ-duree').fill('2');
    await page.getByTestId('course-champ-denivele').fill('10');
    await page.getByTestId('course-champ-participants-max').fill('20');
    await page.getByTestId('course-champ-boucles-max').fill('5');
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été déclarée.`);

    const l = carte(page, nom);
    await expect(l).toBeVisible();
    await expect(l.getByTestId('course-duree')).toHaveText('2 min');
    for (const id of ['course-distance', 'course-duree', 'course-denivele', 'course-participants-max', 'course-boucles-max']) {
      await expect(l.getByTestId(id)).toBeVisible();
    }
    await expect(l.getByTestId('course-distance')).toHaveText('1000 m');
    await expect(l.getByTestId('course-denivele')).toHaveText('10 m');
    await expect(l.getByTestId('course-participants-max')).toHaveText('20');
    await expect(l.getByTestId('course-boucles-max')).toHaveText('5');

    await l.getByTestId('course-bouton-logo').click();
    await l.getByTestId('course-champ-logo').setInputFiles(fixture('logo.png'));
    await expect(l.getByTestId('course-logo-apercu')).toBeVisible();
    await l.getByTestId('course-bouton-logo-envoyer').click();
    await expect(l.getByTestId('course-logo')).toBeVisible();

    await l.getByTestId('course-bouton-modifier').click();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(nom);
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('2');
    await page.getByTestId('course-champ-duree').fill('3');
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été modifiée.`);
    await expect(carte(page, nom).getByTestId('course-duree')).toHaveText('3 min');

    await carte(page, nom).getByTestId('course-lien-fiche').click();
    await expect(page).toHaveURL(/\/administration\/courses\/[0-9a-f-]{36}$/);
    await expect(page.getByTestId('fiche-titre')).toBeVisible();
  });
});
