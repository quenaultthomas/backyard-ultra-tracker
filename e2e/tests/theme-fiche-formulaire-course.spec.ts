import { expect, test, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { PNG } from 'pngjs';
import {
  MOT_DE_PASSE_ADMIN_MASTER,
  PSEUDO_ADMIN_MASTER,
  affecterBenevolesParApi,
  connecterAdminMaster,
  connecterParApi,
  courseDeReference,
  creerBenevoleParApi,
  creerCourseParApi,
  dateAffichee,
  dateDansJours,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  supprimerCourseParApi,
  type DonneesCourse,
} from './aide-admin';
import { creerCompteParApi, pseudoUnique } from './aide-connexion';
import { placerStatutCourseEnBase, placerStatutInscriptionEnBase, sinscrireParApi } from './aide-coureur';
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

test.use({ viewport: { width: 1280, height: 800 } });
test.setTimeout(60_000);

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const SABLE = 'rgb(239, 230, 208)';
const TRAIT = 'rgb(217, 207, 182)';
const SURFACE = 'rgb(255, 252, 245)';
const FORET = 'rgb(20, 53, 42)';
const SUCCES_PALE = 'rgb(227, 239, 230)';
const DANGER = 'rgb(155, 28, 28)';
const FOCUS = 'rgb(168, 67, 0)';
const AGRANDIR = 'html{font-size:32px}';
const AUTORISEES = [...RGB_PALETTE, RGB_TRANSPARENT];
const CHAMPS_FORMULAIRE = ['nom', 'date', 'distance', 'duree', 'denivele', 'participants-max', 'boucles-max'];

const ecran = (nom: string) => ECRANS.find((e) => e.nom === nom)!;
const etat = (nom: string) => ETATS.find((e) => e.nom === nom)!;

/* ------------------------------------------------------------------ aides */

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: import('@playwright/test').APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

/** Nom de Course unique sans la suite « qr » (la page des courses ouvertes interdit les logos qui ressemblent à un QR). */
const nomSansQr = (prefixe: string): string => nomCourseUnique(prefixe).replace(/qr/gi, 'q9');

/** Date lointaine (J+1700 à J+1818), pour ne pas encombrer le haut des listes de la base partagée. */
const dateLointaine = (): string => dateDansJours(1700 + Math.floor(Math.random() * 119));

interface CourseCreee { id: string; nom: string }

async function creerCourse(playwright: PlaywrightLib, surcharge: Partial<DonneesCourse> = {}): Promise<CourseCreee> {
  const donnees = courseDeReference({ nom: nomSansQr('Course R9a'), date: dateLointaine(), ...surcharge });
  const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, donnees));
  return { id: course['id'] as string, nom: donnees.nom };
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

async function ajouterLogo(playwright: PlaywrightLib, courseId: string): Promise<void> {
  const contenu = pngLarge();
  await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, courseId, { nom: 'large.png', type: 'image/png', contenu }));
}

/** Pseudo de 30 caractères, unique (le suffixe aléatoire reste dans les 22 premiers caractères). */
const pseudoLong = (prefixe: string): string => pseudoUnique(prefixe).padEnd(30, 'z').slice(0, 30);

async function creerBenevole(playwright: PlaywrightLib, pseudo: string): Promise<void> {
  await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, pseudo));
}

async function creerCoureur(playwright: PlaywrightLib, pseudo: string): Promise<void> {
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
}

async function affecter(playwright: PlaywrightLib, courseId: string, pseudos: string[]): Promise<void> {
  await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, courseId, pseudos));
}

async function inscrire(playwright: PlaywrightLib, pseudo: string, courseId: string): Promise<{ id: string; dossard: number }> {
  return avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, courseId));
}

/** Retire un Compte en base (le bénévole affecté devient « inconnu »). Le pseudo est contrôlé. */
function retirerCompteEnBase(pseudo: string): void {
  if (!/^[A-Za-z0-9._-]+$/.test(pseudo)) throw new Error('pseudo invalide');
  execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'base', 'psql', '-U', process.env.E2E_BASE_UTILISATEUR ?? 'backyard', '-d', process.env.E2E_BASE_NOM ?? 'backyard',
      '-c', `delete from compte where pseudo = '${pseudo}'`],
    { cwd: join(__dirname, '..', '..'), stdio: 'pipe' },
  );
}

async function ouvrirSessionMaster(page: Page): Promise<void> {
  await connecterParApi(page.request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
}

/** Ouvre la fiche (session admin master) et attend les trois sections chargées. */
async function ouvrirFiche(page: Page, courseId: string): Promise<void> {
  await page.goto(`/administration/courses/${courseId}`);
  await attendreFiche(page);
}

async function attendreFiche(page: Page): Promise<void> {
  await expect(page.getByTestId('fiche-titre')).toBeVisible();
  await expect(page.getByTestId('fiche-inscrits-compteur')).toBeVisible();
  await expect(
    page.getByTestId('fiche-bouton-enregistrer').or(page.getByTestId('fiche-benevoles-verrouille')).or(page.getByTestId('fiche-benevoles-erreur')),
  ).toBeVisible();
}

async function ouvrirGestion(page: Page): Promise<void> {
  await page.goto('/administration/courses');
  await expect(page.getByTestId('titre-courses')).toBeVisible();
  await expect(page.getByTestId('liste-courses')).toBeVisible();
}

const carteCourse = (page: Page, nom: string) =>
  page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });

const formulaireCourse = (page: Page) => page.locator('form').filter({ has: page.getByTestId('course-formulaire-titre') });

const tuile = (page: Page, pseudo: string) => page.getByTestId('fiche-ligne-benevole').filter({ hasText: pseudo });

const boite = async (l: Locator) => (await l.boundingBox())!;

/** Nombre de colonnes d'après les positions `left` (regroupées à 1 px près) et largeur moyenne des éléments. */
async function colonnes(l: Locator): Promise<{ nombre: number; largeurs: number[] }> {
  const positions = await l.evaluateAll((els) => els.map((e) => ({ left: e.getBoundingClientRect().left, width: e.getBoundingClientRect().width })));
  const lefts = positions.map((p) => p.left).sort((a, b) => a - b);
  let nombre = 0;
  let precedent = Number.NEGATIVE_INFINITY;
  for (const x of lefts) {
    if (x - precedent > 1) nombre++;
    precedent = x;
  }
  return { nombre, largeurs: positions.map((p) => p.width) };
}

async function pasDeScrollHorizontal(page: Page): Promise<void> {
  const mesure = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
  expect(mesure.scroll, 'défilement horizontal').toBeLessThanOrEqual(mesure.client);
}

/** Descendants visibles (hors éléments de 1 px) qui dépassent horizontalement de leur conteneur. */
async function debordements(racine: Locator): Promise<string[]> {
  return racine.evaluate((conteneur) => {
    const r = conteneur.getBoundingClientRect();
    return Array.from(conteneur.querySelectorAll('*'))
      .filter((e) => e.checkVisibility())
      .filter((e) => {
        const b = e.getBoundingClientRect();
        return b.width > 1 && b.height > 1 && (b.left < r.left - 0.5 || b.right > r.right + 0.5);
      })
      .map((e) => `${e.tagName.toLowerCase()}[${e.getAttribute('data-testid') ?? e.className}]`);
  });
}

async function mesuresResponsive(page: Page): Promise<MesuresResponsive> {
  await page.evaluate(AIDES_PAGE);
  return page.evaluate(() => (window as unknown as { __t: { responsive(): unknown } }).__t.responsive()) as Promise<MesuresResponsive>;
}

interface Attendu { classe: string; fond: string; texte: string; libelle: string }
const PASTILLES: Record<string, Attendu> = {
  EN_PREPARATION: { classe: 'pastille-statut--en-preparation', fond: SABLE, texte: 'rgb(74, 90, 80)', libelle: 'En préparation' },
  EN_COURS: { classe: 'pastille-statut--en-cours', fond: 'rgb(251, 233, 207)', texte: FOCUS, libelle: 'En cours' },
  TERMINEE: { classe: 'pastille-statut--terminee', fond: FORET, texte: 'rgb(247, 241, 227)', libelle: 'Terminée' },
};

async function verifierPastille(pastille: Locator, attendu: Attendu): Promise<void> {
  await expect(pastille).toHaveClass(/\bpastille-statut\b/);
  await expect(pastille).toHaveClass(new RegExp(`\\b${attendu.classe}\\b`));
  await expect(pastille).toHaveText(attendu.libelle);
  await expect(pastille).toHaveCSS('background-color', attendu.fond);
  await expect(pastille).toHaveCSS('color', attendu.texte);
  const mesures = await pastille.evaluate((e) => ({
    rayon: parseFloat(getComputedStyle(e).borderTopLeftRadius),
    puce: getComputedStyle(e, '::before').content,
  }));
  expect(mesures.rayon).toBeGreaterThanOrEqual(12);
  expect(mesures.puce).not.toBe('none');
}

/* ------------------------------------------------------------------ CA1 */

test('CA1 - la fiche affiche le visuel (logo ou substitution), le titre, la date et la pastille de statut', async ({ page, playwright }) => {
  test.setTimeout(90_000);
  const a = await creerCourse(playwright);
  await ajouterLogo(playwright, a.id);
  const b = await creerCourse(playwright);
  placerStatutCourseEnBase(b.id, 'EN_COURS');
  await ouvrirSessionMaster(page);

  await ouvrirFiche(page, a.id);
  await expect(page.locator('main')).toHaveClass(/\bpage--cartes\b/);
  const carte = page.locator('section.carte');
  expect(Math.round((await boite(carte)).width)).toBe(704);
  const titre = page.getByTestId('fiche-titre');
  expect(await titre.evaluate((e) => e.tagName)).toBe('H1');
  await expect(titre).toHaveText(a.nom);

  const logo = page.getByTestId('fiche-logo');
  await expect(logo).toHaveAttribute('alt', `Logo de ${a.nom}`);
  await expect.poll(() => logo.evaluate((e) => (e as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  await expect(logo).toHaveCSS('object-fit', 'contain');
  expect(await logo.evaluate((e) => (e as HTMLImageElement).naturalWidth / (e as HTMLImageElement).naturalHeight)).toBe(2);
  const zone = logo.locator('..');
  await expect(zone).toHaveCSS('background-color', SABLE);
  const bz = await boite(zone);
  const bl = await boite(logo);
  expect(Math.abs(bz.width / bz.height - 16 / 9)).toBeLessThan(0.02);
  expect(bz.width).toBeLessThanOrEqual(192.5);
  expect(bl.x).toBeGreaterThanOrEqual(bz.x - 0.5);
  expect(bl.y).toBeGreaterThanOrEqual(bz.y - 0.5);
  expect(bl.x + bl.width).toBeLessThanOrEqual(bz.x + bz.width + 0.5);
  expect(bl.y + bl.height).toBeLessThanOrEqual(bz.y + bz.height + 0.5);
  expect(bl.width, 'le logo de 80 x 80 n\'existe plus').toBeGreaterThan(80);

  const date = page.getByTestId('fiche-date');
  expect(await date.evaluate((e) => e.tagName)).toBe('TIME');
  await expect(date).toHaveAttribute('datetime', /^\d{4}-\d{2}-\d{2}$/);
  await expect(date).toHaveText(/^\d{2}\/\d{2}\/\d{4}$/);
  const attrDate = (await date.getAttribute('datetime'))!;
  await expect(date).toHaveText(dateAffichee(attrDate));
  await expect(date).toHaveCSS('font-size', '20px');
  await expect(date).toHaveCSS('font-weight', '700');
  await expect(date).toHaveCSS('color', FORET);
  expect((await date.evaluate((e) => e.closest('dd')!.previousElementSibling!.textContent!)).trim()).toBe('Date');

  const statut = page.getByTestId('fiche-statut');
  await verifierPastille(statut, PASTILLES['EN_PREPARATION']);
  expect((await statut.evaluate((e) => e.closest('dd')!.previousElementSibling!.textContent!)).trim()).toBe('Statut');

  // Course B : sans logo, visuel de substitution décoratif de même taille.
  await ouvrirFiche(page, b.id);
  await expect(page.getByTestId('fiche-titre')).toHaveText(b.nom);
  await expect(page.getByTestId('fiche-logo')).toHaveCount(0);
  const substitution = page.locator('header .visuel-substitution');
  await expect(substitution).toHaveCount(1);
  await expect(substitution).toHaveAttribute('aria-hidden', 'true');
  await expect(substitution).toHaveCSS('background-image', /data:image\/svg\+xml/);
  expect(await substitution.evaluate((e) => ({ texte: (e.textContent ?? '').trim(), testid: e.getAttribute('data-testid'), enfants: e.children.length }))).toEqual({ texte: '', testid: null, enfants: 0 });
  const bs = await boite(substitution);
  expect(Math.abs(bs.width - bz.width)).toBeLessThanOrEqual(1);
  expect(Math.abs(bs.height - bz.height)).toBeLessThanOrEqual(1);
  await verifierPastille(page.getByTestId('fiche-statut'), PASTILLES['EN_COURS']);
});

/* ------------------------------------------------------------------ CA2 */

test('CA2 - cinq indicateurs avec pictogramme : valeurs, 5 colonnes de 118 px à 1280, 2 colonnes à 360 et 320', async ({ page, playwright }) => {
  const c = await creerCourse(playwright);
  await ouvrirSessionMaster(page);
  await ouvrirFiche(page, c.id);

  const indicateurs = page.locator('dl.indicateurs > .indicateur');
  await expect(indicateurs).toHaveCount(5);
  await expect(indicateurs.locator('dt')).toHaveText(['Distance', 'Durée', 'Dénivelé positif', 'Participants max.', 'Boucles max.']);
  await expect(page.getByTestId('fiche-distance')).toHaveText('6706 m');
  await expect(page.getByTestId('fiche-duree')).toHaveText('60 min');
  await expect(page.getByTestId('fiche-denivele')).toHaveText('120 m');
  await expect(page.getByTestId('fiche-participants-max')).toHaveText('50');
  await expect(page.getByTestId('fiche-boucles-max')).toHaveText('24');

  const pictos = await indicateurs.evaluateAll((els) =>
    els.map((el) => {
      const av = getComputedStyle(el, '::before');
      return { largeur: av.width, hauteur: av.height, masque: av.maskImage || av.getPropertyValue('-webkit-mask-image'), fond: av.backgroundColor };
    }),
  );
  for (const p of pictos) {
    expect(p.largeur).toBe('24px');
    expect(p.hauteur).toBe('24px');
    expect(p.masque).toContain('data:image/svg+xml');
    expect(p.fond).toBe(FORET);
  }
  expect(new Set(pictos.map((p) => p.masque)).size, 'pictogrammes tous différents').toBe(5);

  const grand = await colonnes(indicateurs);
  expect(grand.nombre).toBe(5);
  for (const l of grand.largeurs) expect(Math.abs(l - 118)).toBeLessThanOrEqual(1);

  await page.setViewportSize({ width: 360, height: 640 });
  expect((await colonnes(indicateurs)).nombre).toBe(2);
  await pasDeScrollHorizontal(page);
  await page.setViewportSize({ width: 320, height: 640 });
  expect((await colonnes(indicateurs)).nombre).toBe(2);
  await pasDeScrollHorizontal(page);
});

/* ------------------------------------------------------------------ CA3 */

test('CA3 - les bénévoles sont des tuiles cochables : styles, colonnes, compteur, enregistrement, inconnu et course terminée', async ({ page, playwright }) => {
  test.setTimeout(120_000);
  const b1 = pseudoUnique('benevole');
  const b2 = pseudoLong('benevole');
  const b3 = pseudoUnique('benevole');
  const b4 = pseudoUnique('benevole');
  for (const p of [b1, b2, b3, b4]) await creerBenevole(playwright, p);
  const c = await creerCourse(playwright);
  await affecter(playwright, c.id, [b1, b2]);
  await ouvrirSessionMaster(page);
  await ouvrirFiche(page, c.id);

  const compteur = page.getByTestId('fiche-benevoles-compteur');
  await expect(compteur).toHaveText('2 bénévoles affectés');
  for (const p of [b1, b2, b3]) await expect(tuile(page, p)).toHaveCount(1);
  await expect(tuile(page, b3).locator('input')).not.toBeChecked();
  for (const p of [b1, b2]) {
    await expect(tuile(page, p).locator('input')).toBeChecked();
    await expect(tuile(page, p)).toHaveCSS('border-top-color', FORET);
    await expect(tuile(page, p)).toHaveCSS('background-color', SUCCES_PALE);
  }
  const libre = tuile(page, b3);
  await expect(libre).toHaveCSS('background-color', SURFACE);
  await expect(libre).toHaveCSS('border-top-width', '1px');
  await expect(libre).toHaveCSS('border-top-color', TRAIT);
  await expect(libre).toHaveCSS('border-top-left-radius', '6px');
  expect((await boite(libre)).height).toBeGreaterThanOrEqual(44);

  const toutes = page.getByTestId('fiche-ligne-benevole');
  const grand = await colonnes(toutes);
  expect(grand.nombre).toBe(3);
  for (const l of grand.largeurs) expect(Math.abs(l - 210)).toBeLessThanOrEqual(1);
  await page.setViewportSize({ width: 360, height: 640 });
  expect((await colonnes(toutes)).nombre).toBe(1);
  await pasDeScrollHorizontal(page);
  await page.setViewportSize({ width: 1280, height: 800 });

  // Un clic sur le libellé coche la case, change le style et le compteur.
  await tuile(page, b3).getByText(b3, { exact: true }).click();
  await expect(libre.locator('input')).toBeChecked();
  await expect(libre).toHaveCSS('border-top-color', FORET);
  await expect(libre).toHaveCSS('background-color', SUCCES_PALE);
  await expect(compteur).toHaveText('3 bénévoles affectés');
  await page.getByTestId('fiche-bouton-enregistrer').click();
  await expect(page.getByTestId('fiche-message-succes')).toHaveText(`Les bénévoles de la course ${c.nom} ont été enregistrés.`);

  // Bénévole affecté devenu inconnu.
  const cInconnu = await creerCourse(playwright);
  await affecter(playwright, cInconnu.id, [b1, b2, b4]);
  retirerCompteEnBase(b4);
  await ouvrirFiche(page, cInconnu.id);
  await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('3 bénévoles affectés');
  const inconnu = page.getByTestId('fiche-ligne-benevole').filter({ hasText: 'Bénévole inconnu' });
  await expect(inconnu).toBeVisible();
  await expect(inconnu.locator('input')).toBeChecked();

  // Course terminée : tout est verrouillé.
  const cTerminee = await creerCourse(playwright);
  await affecter(playwright, cTerminee.id, [b1]);
  placerStatutCourseEnBase(cTerminee.id, 'TERMINEE');
  await ouvrirFiche(page, cTerminee.id);
  await expect(page.getByTestId('fiche-benevoles-verrouille')).toBeVisible();
  await expect(page.getByTestId('fiche-bouton-enregistrer')).toHaveCount(0);
  const cases = page.getByTestId('fiche-benevole-case');
  expect(await cases.count()).toBeGreaterThan(0);
  for (const caseBenevole of await cases.all()) await expect(caseBenevole).toBeDisabled();
});

/* ------------------------------------------------------------------ CA4 */

test('CA4 - sans bénévole : bloc d\'état vide avec lien, sur 3 largeurs et à 200 %, et erreur 500 distincte', async ({ page, playwright }) => {
  test.setTimeout(90_000);
  const c = await creerCourse(playwright);
  await ouvrirSessionMaster(page);
  await page.route('**/api/administration/benevoles', (route) =>
    route.request().method() === 'GET' ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }) : route.continue(),
  );

  const verifier = async (contexte: string): Promise<void> => {
    await ouvrirFiche(page, c.id);
    const vide = page.getByTestId('fiche-benevoles-vide');
    await expect(vide).toHaveText('Aucun bénévole n\'existe encore.');
    const bloc = page.locator('.etat-vide').filter({ has: vide });
    await expect(bloc).toHaveCSS('background-color', SABLE);
    await expect(bloc).toHaveCSS('border-top-style', 'dashed');
    await expect(bloc).toHaveCSS('border-top-width', '1px');
    await expect(bloc).toHaveCSS('border-top-color', TRAIT);
    const visuel = await boite(bloc.locator('.etat-vide__visuel'));
    expect(Math.abs(visuel.width / visuel.height - 16 / 9), contexte).toBeLessThan(0.02);
    expect(visuel.width).toBeLessThanOrEqual(192.5);
    const lien = page.getByTestId('fiche-lien-benevoles');
    await expect(lien).toHaveText('Gérer les bénévoles');
    await expect(lien).toHaveClass(/\bbouton\b/);
    await expect(lien).toHaveClass(/\bbouton--secondaire\b/);
    expect((await boite(lien)).height).toBeGreaterThanOrEqual(44);
    await expect(page.locator('section.carte fieldset')).toHaveCount(0);
    expect(await debordements(page.locator('section.carte')), contexte).toEqual([]);
    await pasDeScrollHorizontal(page);
  };

  await verifier('1280 px');
  await page.setViewportSize({ width: 360, height: 640 });
  await verifier('360 px');
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto('/administration/courses');
  await attendreSansFiche(page);
  await ouvrirFiche(page, c.id);
  await page.addStyleTag({ content: AGRANDIR });
  await expect(page.getByTestId('fiche-benevoles-vide')).toBeVisible();
  expect(await debordements(page.locator('section.carte')), '320 px à 200 %').toEqual([]);
  await pasDeScrollHorizontal(page);

  await page.setViewportSize({ width: 1280, height: 800 });
  await ouvrirFiche(page, c.id);
  await page.getByTestId('fiche-lien-benevoles').click();
  await expect(page).toHaveURL(/\/administration\/benevoles$/);

  // Réponse 500 : message d'erreur, pas d'état vide.
  await page.unroute('**/api/administration/benevoles');
  await page.route('**/api/administration/benevoles', (route) =>
    route.request().method() === 'GET' ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"status":500}' }) : route.continue(),
  );
  await page.goto(`/administration/courses/${c.id}`);
  await expect(page.getByTestId('fiche-benevoles-erreur')).toHaveText('Impossible de charger la liste des bénévoles. Réessayez plus tard.');
  await expect(page.getByTestId('fiche-benevoles-vide')).toHaveCount(0);
});

async function attendreSansFiche(page: Page): Promise<void> {
  await expect(page.getByTestId('titre-courses')).toBeVisible();
}

/* ------------------------------------------------------------------ CA5 */

test('CA5 - les inscrits : résumé, tableau en texte, Course complète, état vide et erreur', async ({ page, playwright }) => {
  test.setTimeout(120_000);
  const c = await creerCourse(playwright, { nombreMaxParticipants: 3 });
  const pseudos = [pseudoUnique('coureur'), pseudoUnique('coureur'), pseudoLong('coureur')];
  for (const p of pseudos) await creerCoureur(playwright, p);
  const inscriptions = [];
  for (const p of pseudos) inscriptions.push(await inscrire(playwright, p, c.id));
  placerStatutInscriptionEnBase(inscriptions[1].id, 'ABANDON');
  placerStatutInscriptionEnBase(inscriptions[2].id, 'VAINQUEUR');
  await ouvrirSessionMaster(page);
  await ouvrirFiche(page, c.id);

  const compteur = page.getByTestId('fiche-inscrits-compteur');
  await expect(compteur).toHaveText('3 inscrits sur 3');
  await expect(page.getByTestId('fiche-inscrits-places')).toHaveText('Course complète');
  await expect(compteur.locator('..')).toHaveCSS('background-color', SABLE);

  const lignes = page.getByTestId('fiche-inscrits-ligne');
  await expect(lignes).toHaveCount(3);
  await expect(page.getByTestId('fiche-inscrit-dossard')).toHaveText(['1', '2', '3']);
  await expect(page.getByTestId('fiche-inscrit-pseudo')).toHaveText(pseudos);
  await expect(page.getByTestId('fiche-inscrit-statut')).toHaveText(['En course', 'Abandon', 'Vainqueur']);
  for (const dossard of await page.getByTestId('fiche-inscrit-dossard').all()) {
    await expect(dossard).toHaveCSS('font-size', '18px');
    await expect(dossard).toHaveCSS('font-weight', '700');
    await expect(dossard).toHaveCSS('color', FORET);
  }
  for (const statut of await page.getByTestId('fiche-inscrit-statut').all()) {
    expect(await statut.evaluate((e) => e.classList.contains('pastille-statut'))).toBe(false);
  }
  await expect(page.getByTestId('fiche-inscrits').locator('svg, img, canvas, b')).toHaveCount(0);

  // 320 px, texte à 200 % : le pseudo de 30 caractères reste dans la carte.
  await page.setViewportSize({ width: 320, height: 640 });
  await page.addStyleTag({ content: AGRANDIR });
  expect(await debordements(page.locator('section.carte'))).toEqual([]);
  expect(await debordements(page.getByTestId('fiche-inscrits').locator('table'))).toEqual([]);
  await pasDeScrollHorizontal(page);
  await page.setViewportSize({ width: 1280, height: 800 });

  // Course sans inscrit.
  const vide = await creerCourse(playwright);
  await ouvrirFiche(page, vide.id);
  const messageVide = page.getByTestId('fiche-inscrits-vide');
  await expect(messageVide).toHaveText('Aucun inscrit pour le moment.');
  const bloc = page.locator('.etat-vide').filter({ has: messageVide });
  await expect(bloc.locator('img, svg')).toHaveCount(0);
  await expect(bloc).toHaveCSS('background-color', SABLE);

  // Erreur 500 des inscrits : isolée des autres sections.
  await page.route('**/api/administration/courses/*/inscriptions', (route) =>
    route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"status":500}' }),
  );
  await page.goto(`/administration/courses/${c.id}`);
  await expect(page.getByTestId('fiche-inscrits-erreur')).toHaveText('Impossible de charger les inscrits. Réessayez plus tard.');
  await expect(page.getByTestId('fiche-titre')).toHaveText(c.nom);
});

/* ------------------------------------------------------------------ CA6 */

test('CA6 - le formulaire de déclaration est un encart : mesures, ordre, erreurs de champ, contrastes et création', async ({ page }) => {
  test.setTimeout(90_000);
  await ouvrirSessionMaster(page);
  await ouvrirGestion(page);
  const form = formulaireCourse(page);
  await expect(form).toHaveClass(/\bencart\b/);
  await expect(form).toHaveCSS('background-color', SABLE);
  await expect(form).toHaveCSS('border-top-width', '1px');
  await expect(form).toHaveCSS('border-top-color', TRAIT);
  await expect(form).toHaveCSS('border-top-left-radius', '12px');
  await expect(form).toHaveCSS('padding-top', '16px');
  await expect(form).toHaveCSS('padding-left', '16px');
  expect((await boite(form)).width).toBeLessThanOrEqual(640.5);

  const ordre = await form.evaluate((f) => Array.from(f.querySelectorAll('[data-testid]')).map((e) => e.getAttribute('data-testid')));
  const attendu = ['course-formulaire-titre', ...CHAMPS_FORMULAIRE.map((s) => `course-champ-${s}`), 'course-bouton-creer'];
  expect(ordre.filter((t) => attendu.includes(t!))).toEqual(attendu);
  await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
  const creer = page.getByTestId('course-bouton-creer');
  await expect(creer).toHaveText('Déclarer la course');
  await expect(creer).toHaveClass(/\bbouton\b/);
  await expect(creer).toHaveCSS('background-color', FORET);
  expect((await boite(creer)).height).toBeGreaterThanOrEqual(44);

  // Contraste de la bordure de champ sur le sable (état normal, avant toute erreur).
  await page.evaluate(AIDES_PAGE);
  const mesuresNormales = await page.evaluate(() => (window as unknown as { __t: { contrastes(): unknown } }).__t.contrastes()) as MesureContraste[];
  const bordureNom = mesuresNormales.find((m) => m.type === 'bordure-champ' && m.element.startsWith('course-champ-nom'));
  expect(bordureNom, 'mesure de la bordure du champ Nom').toBeTruthy();
  expect(Math.abs(bordureNom!.ratio - 3.61)).toBeLessThanOrEqual(0.05);

  // Validation à vide.
  await creer.click();
  for (const s of CHAMPS_FORMULAIRE) {
    await expect(page.getByTestId(`course-erreur-${s}`)).toBeVisible();
    await expect(page.getByTestId(`course-erreur-${s}`)).not.toHaveText('');
    const champ = page.getByTestId(`course-champ-${s}`);
    await expect(champ).toHaveAttribute('aria-invalid', 'true');
    await expect(champ).toHaveCSS('border-top-width', '2px');
    await expect(champ).toHaveCSS('border-top-color', DANGER);
  }

  // Contrastes (dont bordure de champ / sable).
  await page.evaluate(AIDES_PAGE);
  const mesures = await page.evaluate(() => (window as unknown as { __t: { contrastes(): unknown } }).__t.contrastes()) as MesureContraste[];
  expect(mesures.length).toBeGreaterThan(0);
  for (const m of mesures) expect(m.ratio, `${m.element} ${m.couleur} sur ${m.fond}`).toBeGreaterThanOrEqual(m.seuil);

  // Création réussie.
  const nom = nomSansQr('Course R9a creation');
  await page.getByTestId('course-champ-nom').fill(nom);
  await page.getByTestId('course-champ-date').fill(dateAffichee(dateLointaine()));
  await page.getByTestId('course-champ-distance').fill('1000');
  await page.getByTestId('course-champ-duree').fill('2');
  await page.getByTestId('course-champ-denivele').fill('10');
  await page.getByTestId('course-champ-participants-max').fill('20');
  await page.getByTestId('course-champ-boucles-max').fill('5');
  await creer.click();
  await expect(form.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été déclarée.`);
  await expect(carteCourse(page, nom)).toBeVisible();
});

/* ------------------------------------------------------------------ CA7 */

test('CA7 - la modification cerne l\'encart de vert ; Annuler le retire ; une Course en cours n\'est pas modifiable', async ({ page, playwright }) => {
  test.setTimeout(90_000);
  const a = await creerCourse(playwright);
  const b = await creerCourse(playwright);
  placerStatutCourseEnBase(b.id, 'EN_COURS');
  await ouvrirSessionMaster(page);
  await ouvrirGestion(page);
  const form = formulaireCourse(page);

  await carteCourse(page, a.nom).getByTestId('course-bouton-modifier').click();
  await expect(page.getByTestId('course-champ-nom')).toBeFocused();
  await expect(page.getByTestId('course-champ-nom')).toHaveValue(a.nom);
  await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
  await expect(form).toHaveClass(/\bencart--edition\b/);
  await expect(form).toHaveCSS('border-top-color', FORET);
  expect(await form.evaluate((e) => getComputedStyle(e).boxShadow)).not.toBe('none');
  const enregistrer = page.getByTestId('course-bouton-enregistrer');
  const annuler = page.getByTestId('course-bouton-annuler');
  await expect(enregistrer).toHaveClass(/\bbouton\b/);
  await expect(annuler).toHaveClass(/\bbouton--secondaire\b/);
  expect((await boite(enregistrer)).height).toBeGreaterThanOrEqual(44);
  expect((await boite(annuler)).height).toBeGreaterThanOrEqual(44);

  await annuler.click();
  await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
  await expect(form).not.toHaveClass(/\bencart--edition\b/);

  await carteCourse(page, a.nom).getByTestId('course-bouton-modifier').click();
  await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
  await page.getByTestId('course-champ-duree').fill('45');
  await enregistrer.click();
  await expect(page.getByTestId('course-message-succes')).toBeVisible();
  await expect(carteCourse(page, a.nom).getByTestId('course-duree')).toHaveText('45 min');

  await expect(carteCourse(page, b.nom)).toBeVisible();
  await expect(carteCourse(page, b.nom).getByTestId('course-bouton-modifier')).toHaveCount(0);
});

/* ------------------------------------------------------------------ CA8 */

test('CA8 - nom de 100 caractères, pseudos de 30, texte à 200 % : rien ne déborde sur la fiche et le formulaire', async ({ page, playwright }) => {
  test.setTimeout(120_000);
  const nom = nomSansQr('W').replace(/\s+/g, '').padEnd(100, 'W').slice(0, 100);
  const benevole = pseudoLong('benevole');
  const coureur = pseudoLong('coureur');
  await creerBenevole(playwright, benevole);
  await creerCoureur(playwright, coureur);
  const c = await creerCourse(playwright, { nom });
  try {
    await affecter(playwright, c.id, [benevole]);
    await inscrire(playwright, coureur, c.id);
    await ouvrirSessionMaster(page);

    for (const [largeur, petit] of [[320, true], [360, true], [1280, false]] as const) {
      await page.setViewportSize({ width: largeur, height: petit ? 640 : 800 });

      // Texte normal : cartes de page, cibles et tailles.
      if (petit) {
        for (const chemin of [`/administration/courses/${c.id}`, '/administration/courses']) {
          await page.goto(chemin);
          if (chemin.endsWith(c.id)) await attendreFiche(page);
          else await expect(page.getByTestId('liste-courses')).toBeVisible();
          const m = await mesuresResponsive(page);
          const carte = m.cartes[0];
          expect(Math.round(carte.largeur), chemin).toBe(m.clientWidth - 32);
          expect(Math.round(carte.gauche)).toBe(16);
          expect(m.cibles, `${chemin} ${largeur} cibles`).toEqual([]);
          expect(m.petits, `${chemin} ${largeur} petits`).toEqual([]);
        }
      }

      // Fiche à 200 %.
      await ouvrirFiche(page, c.id);
      await page.addStyleTag({ content: AGRANDIR });
      const titre = page.getByTestId('fiche-titre');
      await expect(titre).toHaveText(nom);
      await expect(titre).toHaveCSS('overflow-wrap', 'anywhere');
      const carte = page.locator('section.carte');
      expect(await debordements(carte), `fiche ${largeur}`).toEqual([]);
      for (const t of await page.getByTestId('fiche-ligne-benevole').all()) expect(await debordements(t), `tuile ${largeur}`).toEqual([]);
      expect(await debordements(page.getByTestId('fiche-inscrits').locator('table')), `tableau ${largeur}`).toEqual([]);
      await pasDeScrollHorizontal(page);
      if (petit) await expect(carte).toHaveCSS('padding-top', '24px'); // --espace-3 = 0,75 rem = 24 px à 200 % (la spec écrit 12 px, valeur à 100 %)

      // Formulaire en erreurs puis en modification, à 200 %.
      await page.goto('/administration/courses');
      await expect(page.getByTestId('liste-courses')).toBeVisible();
      await page.addStyleTag({ content: AGRANDIR });
      const form = formulaireCourse(page);
      await page.getByTestId('course-bouton-creer').click();
      await expect(page.getByTestId('course-erreur-nom')).toBeVisible();
      expect(await debordements(form), `formulaire en erreurs ${largeur}`).toEqual([]);
      await pasDeScrollHorizontal(page);
      await carteCourse(page, nom).getByTestId('course-bouton-modifier').click();
      await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
      await expect(page.getByTestId('course-bouton-enregistrer')).toBeVisible();
      expect(await debordements(form), `formulaire en modification ${largeur}`).toEqual([]);
      await pasDeScrollHorizontal(page);
      if (petit) await expect(form).toHaveCSS('padding-top', '24px');
    }
  } finally {
    // Le nom de 100 caractères sans espace ressemble à un jeton QR dans la page des courses ouvertes.
    await avecApi(playwright, (ctx) => supprimerCourseParApi(ctx, c.id));
  }
});

/* ------------------------------------------------------------------ CA9 */

interface ArretClavier {
  testid: string | null;
  id: string;
  dansEntete: boolean;
  outline: string;
  ancetreRogne: string | null;
}

async function tabulerJusqua(page: Page, maximum: number, fin: (a: ArretClavier) => boolean): Promise<ArretClavier[]> {
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  const arrets: ArretClavier[] = [];
  for (let i = 0; i < maximum; i++) {
    await page.keyboard.press('Tab');
    const arret = await page.evaluate((): ArretClavier | null => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return null;
      const cs = getComputedStyle(el);
      let rogne: string | null = null;
      for (let n = el.parentElement; n && n.tagName !== 'MAIN'; n = n.parentElement) {
        const o = getComputedStyle(n);
        if (o.overflowX !== 'visible' || o.overflowY !== 'visible') { rogne = `${n.tagName.toLowerCase()}.${n.className}`; break; }
      }
      return {
        testid: el.getAttribute('data-testid'),
        id: el.id,
        dansEntete: !!el.closest('.entete'),
        outline: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`,
        ancetreRogne: rogne,
      };
    });
    if (!arret) break;
    arrets.push(arret);
    if (fin(arret)) break;
  }
  return arrets;
}

test('CA9 - la fiche et le formulaire se parcourent au clavier dans l\'ordre du DOM, contours entiers, titres ordonnés', async ({ page, playwright }) => {
  test.setTimeout(90_000);
  const b1 = pseudoUnique('benevole');
  const b2 = pseudoUnique('benevole');
  for (const p of [b1, b2]) await creerBenevole(playwright, p);
  const c = await creerCourse(playwright);
  await affecter(playwright, c.id, [b1, b2]);
  await ouvrirSessionMaster(page);
  await ouvrirFiche(page, c.id);

  const idsDom = await page.getByTestId('fiche-benevole-case').evaluateAll((els) => els.map((e) => e.id));
  expect(idsDom.length).toBeGreaterThanOrEqual(2);
  expect(idsDom).toContain(`fiche-benevole-${await idDe(page, b1)}`);
  const arrets = (await tabulerJusqua(page, 120, (a) => a.testid === 'fiche-lien-retour')).filter((a) => !a.dansEntete);
  // 4.1 : la section « Démarrage » (course en préparation) précède les cases des bénévoles dans l'ordre du DOM.
  expect(arrets.map((a) => a.testid)).toEqual(['fiche-bouton-demarrer', ...idsDom.map(() => 'fiche-benevole-case'), 'fiche-bouton-enregistrer', 'fiche-lien-retour']);
  expect(arrets.slice(1, idsDom.length + 1).map((a) => a.id)).toEqual(idsDom);
  for (const a of arrets) {
    expect(a.outline, a.testid ?? '').toBe(`solid 3px ${FOCUS}`);
    expect(a.ancetreRogne, `${a.testid} : ancêtre avec overflow`).toBeNull();
  }

  const titres = await page.locator('main').evaluate((m) => Array.from(m.querySelectorAll('h1, h2, h3')).map((h) => `${h.tagName} ${h.textContent!.trim()}`));
  expect(titres).toEqual([`H1 ${c.nom}`, 'H2 Démarrage', 'H2 Bénévoles', 'H2 Inscrits']);

  // Sans bénévole : « Gérer les bénévoles » remplace les cases.
  await page.route('**/api/administration/benevoles', (route) =>
    route.request().method() === 'GET' ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }) : route.continue(),
  );
  const nue = await creerCourse(playwright);
  await ouvrirFiche(page, nue.id);
  const sans = (await tabulerJusqua(page, 60, (a) => a.testid === 'fiche-lien-retour')).filter((a) => !a.dansEntete);
  expect(sans.map((a) => a.testid)).toEqual(['fiche-bouton-demarrer', 'fiche-lien-benevoles', 'fiche-bouton-enregistrer', 'fiche-lien-retour']);
  await page.unroute('**/api/administration/benevoles');

  // Formulaire en modification : champs puis boutons.
  await ouvrirGestion(page);
  await carteCourse(page, c.nom).getByTestId('course-bouton-modifier').click();
  await expect(page.getByTestId('course-champ-nom')).toBeFocused();
  const suite = await tabulerSansRepartir(page, 8);
  expect(suite).toEqual([
    'course-champ-date', 'course-champ-distance', 'course-champ-duree', 'course-champ-denivele',
    'course-champ-participants-max', 'course-champ-boucles-max', 'course-bouton-enregistrer', 'course-bouton-annuler',
  ]);
});

async function tabulerSansRepartir(page: Page, nombre: number): Promise<(string | null)[]> {
  const testids: (string | null)[] = [];
  for (let i = 0; i < nombre; i++) {
    await page.keyboard.press('Tab');
    testids.push(await page.evaluate(() => document.activeElement?.getAttribute('data-testid') ?? null));
  }
  return testids;
}

/** Identifiant du Compte bénévole (lu dans la liste de l'API) : l'identifiant de la case est `fiche-benevole-<id>`. */
async function idDe(page: Page, pseudo: string): Promise<string> {
  const reponse = await page.request.get('/api/administration/benevoles');
  const comptes = (await reponse.json()) as Array<{ id: string; pseudo: string }>;
  return comptes.find((x) => x.pseudo === pseudo)!.id;
}

/* ------------------------------------------------------------------ CA10 */

test('CA10 - les scènes de la fiche et du formulaire respectent contrastes et palette', async ({ page, playwright }) => {
  test.setTimeout(120_000);
  const jeu = await preparerJeuReference(playwright);
  const scenes = [
    ecran('fiche de course'), ecran('gestion des courses'),
    etat('fiche de course sans benevole'), etat('fiche de course sans inscrit'), etat('gestion des courses en modification'),
  ];
  for (const scene of scenes) {
    await page.context().clearCookies();
    await ouvrirScene(page, scene, jeu);
    const mesures = await page.evaluate(() => (window as unknown as { __t: { contrastes(): unknown } }).__t.contrastes()) as MesureContraste[];
    for (const m of mesures) expect(m.ratio, `${scene.nom} : ${m.element} ${m.couleur} sur ${m.fond}`).toBeGreaterThanOrEqual(m.seuil);
    const hors = await page.evaluate((a) => (window as unknown as { __t: { horsPalette(x: string[]): unknown } }).__t.horsPalette(a), AUTORISEES) as EcartCouleur[];
    expect(hors, `${scene.nom} : couleurs hors palette`).toEqual([]);

    if (scene.nom === 'fiche de course') {
      const tuileCochee = mesures.find((m) => m.fond === '#e3efe6' && m.couleur === '#1b2a22');
      expect(tuileCochee, 'texte de la tuile cochée').toBeTruthy();
      expect(Math.abs(tuileCochee!.ratio - 12.67)).toBeLessThanOrEqual(0.05);
    }
    if (scene.nom === 'gestion des courses en modification') {
      const bordure = mesures.find((m) => m.type === 'bordure-champ' && m.element.startsWith('course-champ-nom'));
      expect(bordure, 'bordure du champ Nom').toBeTruthy();
      expect(Math.abs(bordure!.ratio - 3.61)).toBeLessThanOrEqual(0.05);
    }
  }
});

/* ------------------------------------------------------------------ CA11 */

test('CA11 - mouvement réduit et contraste forcé sur la fiche (avec et sans logo) et le formulaire en modification', async ({ page, playwright }) => {
  test.setTimeout(120_000);
  const benevole = pseudoUnique('benevole');
  await creerBenevole(playwright, benevole);
  const coureur = pseudoUnique('coureur');
  await creerCoureur(playwright, coureur);
  const avecLogo = await creerCourse(playwright);
  await ajouterLogo(playwright, avecLogo.id);
  const sansLogo = await creerCourse(playwright);
  for (const c of [avecLogo, sansLogo]) {
    await affecter(playwright, c.id, [benevole]);
  }
  await inscrire(playwright, coureur, sansLogo.id);
  await ouvrirSessionMaster(page);

  await page.emulateMedia({ reducedMotion: 'reduce' });
  for (const c of [avecLogo, sansLogo]) {
    await ouvrirFiche(page, c.id);
    await expect(page.getByTestId('fiche-bouton-enregistrer')).toHaveCSS('transition-duration', '0s');
    expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
  }
  await ouvrirGestion(page);
  await carteCourse(page, sansLogo.nom).getByTestId('course-bouton-modifier').click();
  await expect(page.getByTestId('course-bouton-annuler')).toHaveCSS('transition-duration', '0s');
  expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  expect(await page.locator('html').evaluate((e) => getComputedStyle(e).colorScheme)).toBe('light');

  await page.emulateMedia({ forcedColors: 'active' });
  await ouvrirFiche(page, sansLogo.id);
  await expect(page.locator('header .visuel-substitution')).toHaveCSS('background-image', 'none');
  const t = tuile(page, benevole);
  await expect(t).toBeVisible();
  expect(parseFloat(await t.evaluate((e) => getComputedStyle(e).borderTopWidth))).toBeGreaterThanOrEqual(1);
  await verifierLibelle(page.getByTestId('fiche-statut'), 'En préparation');
  await ouvrirFiche(page, avecLogo.id);
  await expect(page.getByTestId('fiche-logo')).toBeVisible();
  await verifierLibelle(page.getByTestId('fiche-statut'), 'En préparation');

  await ouvrirGestion(page);
  await carteCourse(page, sansLogo.nom).getByTestId('course-bouton-modifier').click();
  const form = formulaireCourse(page);
  await expect(form).toHaveClass(/\bencart--edition\b/);
  expect(parseFloat(await form.evaluate((e) => getComputedStyle(e).borderTopWidth))).toBeGreaterThanOrEqual(1);
});

async function verifierLibelle(pastille: Locator, libelle: string): Promise<void> {
  await expect(pastille).toBeVisible();
  await expect(pastille).toHaveText(libelle);
}

/* ------------------------------------------------------------------ CA12 */

test('CA12 - la factorisation garde le rendu de la liste : pictogrammes distincts et texte « Aucun logo » masqué', async ({ page, playwright }) => {
  const c = await creerCourse(playwright);
  await ouvrirSessionMaster(page);
  await ouvrirGestion(page);
  const carte = carteCourse(page, c.nom);
  const pictos = await carte.locator('.indicateur').evaluateAll((els) =>
    els.map((el) => {
      const av = getComputedStyle(el, '::before');
      return { largeur: av.width, masque: av.maskImage || av.getPropertyValue('-webkit-mask-image'), fond: av.backgroundColor };
    }),
  );
  expect(pictos).toHaveLength(5);
  for (const p of pictos) {
    expect(p.largeur).toBe('24px');
    expect(p.fond).toBe(FORET);
  }
  expect(new Set(pictos.map((p) => p.masque)).size).toBe(5);
  const absent = carte.getByTestId('course-logo-absent');
  const masque = await absent.evaluate((e) => {
    const texte = e.querySelector('.masque') as HTMLElement | null;
    const cible = texte ?? e;
    const b = cible.getBoundingClientRect();
    return { largeur: b.width, hauteur: b.height, clip: getComputedStyle(cible).clipPath, texte: (e.textContent ?? '').trim() };
  });
  expect(masque.texte).toBe('Aucun logo');
  expect(masque.largeur).toBeLessThanOrEqual(1.5);
  expect(masque.hauteur).toBeLessThanOrEqual(1.5);
  expect(masque.clip).toContain('inset');
});

/* ------------------------------------------------------------------ CA13 */

test('CA13 - parcours admin complet sur téléphone : déclarer, logo, modifier, fiche, inscrit, bénévole, retour', async ({ page, playwright }) => {
  test.setTimeout(120_000);
  await page.setViewportSize({ width: 360, height: 640 });
  const coureur = pseudoUnique('coureur');
  const benevole = pseudoUnique('benevole');
  await creerCoureur(playwright, coureur);
  await creerBenevole(playwright, benevole);
  const nom = nomSansQr('Course R9a parcours');

  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
  await page.goto('/');
  await ouvrirMenuCompte(page);
  await page.getByTestId('menu-lien-administration').click();
  await expect(page.getByTestId('titre-administration')).toBeVisible();
  await page.getByTestId('lien-gestion-courses').click();
  await expect(page.getByTestId('titre-courses')).toBeVisible();
  await expect(page.getByTestId('liste-courses')).toBeVisible();

  // Déclaration.
  await page.getByTestId('course-champ-nom').fill(nom);
  await page.getByTestId('course-champ-date').fill(dateAffichee(dateLointaine()));
  await page.getByTestId('course-champ-distance').fill('1000');
  await page.getByTestId('course-champ-duree').fill('60');
  await page.getByTestId('course-champ-denivele').fill('10');
  await page.getByTestId('course-champ-participants-max').fill('20');
  await page.getByTestId('course-champ-boucles-max').fill('5');
  await page.getByTestId('course-bouton-creer').click();
  await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été déclarée.`);
  const carte = carteCourse(page, nom);
  await expect(carte).toBeVisible();

  // Logo.
  await carte.getByTestId('course-champ-logo').setInputFiles(join(__dirname, '..', 'fixtures', 'logo.png'));
  await carte.getByTestId('course-bouton-logo-envoyer').click();
  await expect(carte.getByTestId('course-logo')).toBeVisible();

  // Modification de la durée.
  await carte.getByTestId('course-bouton-modifier').click();
  await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
  await page.getByTestId('course-champ-duree').fill('45');
  await page.getByTestId('course-bouton-enregistrer').click();
  await expect(page.getByTestId('course-message-succes')).toBeVisible();
  await expect(carteCourse(page, nom).getByTestId('course-duree')).toHaveText('45 min');

  // Fiche.
  await carteCourse(page, nom).getByTestId('course-lien-fiche').click();
  await expect(page).toHaveURL(/\/administration\/courses\/[0-9a-f-]{36}$/);
  const courseId = /courses\/([0-9a-f-]{36})$/.exec(page.url())![1];
  await attendreFiche(page);
  await expect(page.getByTestId('fiche-titre')).toHaveText(nom);
  await expect(page.getByTestId('fiche-logo')).toBeVisible();
  await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
  await expect(page.getByTestId('fiche-duree')).toHaveText('45 min');
  await expect(page.getByTestId('fiche-inscrits-vide')).toBeVisible();

  // Le coureur s'inscrit (API), l'admin recharge.
  await inscrire(playwright, coureur, courseId);
  await page.reload();
  await attendreFiche(page);
  await expect(page.getByTestId('fiche-inscrit-dossard')).toHaveText('1');
  await expect(page.getByTestId('fiche-inscrit-pseudo')).toHaveText(coureur);
  await expect(page.getByTestId('fiche-inscrit-statut')).toHaveText('En course');
  await expect(page.getByTestId('fiche-inscrits-compteur')).toHaveText('1 inscrit sur 20');

  // Affectation d'un bénévole.
  await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('0 bénévole affecté');
  await tuile(page, benevole).getByText(benevole, { exact: true }).click();
  await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('1 bénévole affecté');
  await page.getByTestId('fiche-bouton-enregistrer').click();
  await expect(page.getByTestId('fiche-message-succes')).toHaveText(`Les bénévoles de la course ${nom} ont été enregistrés.`);

  // Retour.
  await page.getByTestId('fiche-lien-retour').click();
  await expect(page).toHaveURL(/\/administration\/courses$/);
  await expect(page.getByTestId('titre-courses')).toBeVisible();
});
