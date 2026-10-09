import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique } from './aide-connexion';
import {
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
  MOT_DE_PASSE_ADMIN_MASTER,
  affecterBenevolesParApi,
  connecterParApi,
  courseDeReference,
  creerBenevoleParApi,
  creerCourseParApi,
  dateAffichee,
  dateDansJours,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoBenevoleUnique,
  supprimerCourseParApi,
} from './aide-admin';
import { placerStatutCourseEnBase, sinscrireParApi } from './aide-coureur';
import { seDeconnecterParLeMenu } from './aide-entete';
import { deplierChangementMotDePasse } from './aide-mon-compte';
import {
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

test.beforeEach(({}, testInfo) => {
  testInfo.setTimeout(60_000);
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const AGRANDIR = 'html{font-size:32px}';
const FORET = 'rgb(20, 53, 42)';
const ENCRE = 'rgb(27, 42, 34)';
const SABLE = 'rgb(239, 230, 208)';
const FOCUS = 'rgb(168, 67, 0)';
const FOCUS_ENTETE = 'rgb(242, 140, 40)';
const NOM_100_W = 'W'.repeat(100);
const AUTORISEES = [...RGB_PALETTE, RGB_TRANSPARENT];
const SCENES = [...ECRANS, ...ETATS];

/* ------------------------------------------------------------------ préparation par l'API */

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

/** Nom de Course unique sans la suite « qr » (voir `inscription-course.spec.ts`). */
const nomSansQr = (prefixe: string): string => nomCourseUnique(prefixe).replace(/qr/gi, 'q9');

/** Jour lointain (J+1700 à J+1779) pour ne pas croiser les Courses des autres tests. */
const joursLointains = (): number => 1700 + Math.floor(Math.random() * 80);

async function creerCoureur(playwright: PlaywrightLib, prefixe = 'alice'): Promise<string> {
  const pseudo = pseudoUnique(prefixe);
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  return pseudo;
}

async function creerBenevole(playwright: PlaywrightLib): Promise<string> {
  const pseudo = pseudoBenevoleUnique();
  await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, pseudo));
  return pseudo;
}

interface CourseCreee {
  id: string;
  nom: string;
}

async function creerCourse(playwright: PlaywrightLib, prefixe = 'R9b'): Promise<CourseCreee> {
  const nom = nomSansQr(prefixe);
  const course = await avecApi(playwright, (ctx) =>
    creerCourseParApi(ctx, courseDeReference({ nom, date: dateDansJours(joursLointains()) })),
  );
  return { id: course['id'] as string, nom };
}

/* ------------------------------------------------------------------ navigation et mesures */

interface Identifiants {
  pseudo: string;
  motDePasse: string;
}

const MASTER: Identifiants = { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER };
const coureurDe = (pseudo: string): Identifiants => ({ pseudo, motDePasse: MOT_DE_PASSE });
const benevoleDe = (pseudo: string): Identifiants => ({ pseudo, motDePasse: MOT_DE_PASSE_BENEVOLE_CREE });

const ECRANS_EN_CARTES = [
  { nom: 'Espace coureur', chemin: '/coureur', titre: 'coureur-titre', grille: 'coureur-liste-courses' },
  { nom: 'Mes inscriptions', chemin: '/coureur/inscriptions', titre: 'inscriptions-titre', grille: 'inscriptions-liste' },
  { nom: 'Espace bénévole', chemin: '/benevole', titre: 'accueil-benevole-titre', grille: 'benevole-liste-courses' },
  { nom: 'Gestion des courses', chemin: '/administration/courses', titre: 'titre-courses', grille: 'liste-courses' },
] as const;

async function ouvrir(page: Page, ident: Identifiants, chemin: string, titre: string, largeur = 1280, hauteur = 800, grand = false): Promise<void> {
  await page.setViewportSize({ width: largeur, height: hauteur });
  await page.context().clearCookies();
  await connecterParApi(page.request, ident.pseudo, ident.motDePasse);
  await page.goto(chemin);
  await expect(page.getByTestId(titre)).toBeVisible();
  await expect(page.getByText('Chargement…')).toHaveCount(0);
  if (grand) await page.addStyleTag({ content: AGRANDIR });
}

async function css(element: Locator, propriete: string, pseudo?: string): Promise<string> {
  return element.evaluate((el, [p, ps]) => (getComputedStyle(el, ps || null) as unknown as Record<string, string>)[p as string], [propriete, pseudo ?? '']);
}

function proche(obtenu: number, attendu: number, tolerance: number, message: string): void {
  expect(Math.abs(obtenu - attendu), `${message} : obtenu ${obtenu}, attendu ${attendu} (+/- ${tolerance})`).toBeLessThanOrEqual(tolerance);
}

async function sansDefilementHorizontal(page: Page): Promise<void> {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  expect(m.s, 'défilement horizontal').toBeLessThanOrEqual(m.c);
}

interface MesureEcran {
  classes: string;
  nomConteneur: string;
  carteLargeur: number;
  carteGauche: number;
  pistes: number[];
}

/** Mesures du `main`, de la carte de la page et des pistes de la grille (`grid-template-columns`). */
async function mesurerEcran(page: Page, grille: string): Promise<MesureEcran> {
  return page.evaluate((g) => {
    const main = document.querySelector('main') as HTMLElement;
    const carte = document.querySelector('main > section.carte, .page > section.carte') as HTMLElement;
    const r = carte.getBoundingClientRect();
    const liste = document.querySelector(`[data-testid="${g}"]`) as HTMLElement;
    const pistes = getComputedStyle(liste)
      .gridTemplateColumns.split(' ')
      .map((v) => parseFloat(v))
      .filter((v) => !Number.isNaN(v));
    return {
      classes: main.className,
      nomConteneur: getComputedStyle(main).containerName,
      carteLargeur: r.width,
      carteGauche: r.left,
      pistes,
    };
  }, grille);
}

/** Descendants visibles de `racine` qui sortent de ses bords. */
async function debordements(racine: Locator): Promise<string[]> {
  return racine.evaluate((conteneur) => {
    const sortie: string[] = [];
    const c = conteneur.getBoundingClientRect();
    for (const el of Array.from(conteneur.querySelectorAll('*'))) {
      if (!(el as HTMLElement).checkVisibility()) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) continue;
      if (r.left < c.left - 0.5 || r.right > c.right + 0.5) {
        sortie.push(`${el.getAttribute('data-testid') ?? el.tagName} [${r.left.toFixed(1)}; ${r.right.toFixed(1)}] hors de [${c.left.toFixed(1)}; ${c.right.toFixed(1)}]`);
      }
    }
    return sortie;
  });
}

/** Réécrit les noms des Courses données (par identifiant) dans les réponses de la liste interceptée. */
async function reecrireNoms(page: Page, glob: string, champId: string, champNom: string, noms: Record<string, string>): Promise<void> {
  await page.route(glob, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    const reponse = await route.fetch();
    const liste = (await reponse.json()) as Array<Record<string, unknown>>;
    for (const element of liste) {
      const nom = noms[element[champId] as string];
      if (nom) element[champNom] = nom;
    }
    await route.fulfill({ response: reponse, json: liste });
  });
}

const ligneGestion = (page: Page, nom: string): Locator =>
  page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });

/* ------------------------------------------------------------------ CA1 */

test.describe('Conteneur unique des écrans en cartes', () => {
  test('CA1 - les quatre écrans en cartes ont page--cartes, le conteneur « cartes », une carte de 1024 px et les bonnes colonnes', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const leo = await creerBenevole(playwright);
    const a = await creerCourse(playwright);
    const b = await creerCourse(playwright);
    const c = await creerCourse(playwright);
    for (const course of [a, b, c]) await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [leo]));
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, a.id));
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, b.id));

    const identifiants: Record<string, Identifiants> = {
      '/coureur': coureurDe(alice),
      '/coureur/inscriptions': coureurDe(alice),
      '/benevole': benevoleDe(leo),
      '/administration/courses': MASTER,
    };
    const configurations = [
      { largeur: 1280, hauteur: 800, carte: 1024, gauche: 128, colonnes: 3 },
      { largeur: 768, hauteur: 1024, carte: 736, gauche: 16, colonnes: 2 },
      { largeur: 360, hauteur: 640, carte: 328, gauche: 16, colonnes: 1 },
    ];
    for (const ecran of ECRANS_EN_CARTES) {
      for (const cfg of configurations) {
        await ouvrir(page, identifiants[ecran.chemin], ecran.chemin, ecran.titre, cfg.largeur, cfg.hauteur);
        await expect(page.getByTestId(ecran.grille)).toBeVisible();
        const m = await mesurerEcran(page, ecran.grille);
        const etiquette = `${ecran.nom} à ${cfg.largeur} px`;
        expect(m.classes.split(/\s+/), `${etiquette} : classes du main`).toEqual(expect.arrayContaining(['page', 'page--cartes']));
        expect(m.nomConteneur, `${etiquette} : container-name`).toBe('cartes');
        expect(m.nomConteneur).not.toBe('page-gestion-courses');
        proche(m.carteLargeur, cfg.carte, 1, `${etiquette} : largeur de la carte de la page`);
        proche(m.carteGauche, cfg.gauche, 1, `${etiquette} : bord gauche de la carte de la page`);
        if (ecran.chemin !== '/administration/courses') {
          expect(m.pistes.length, `${etiquette} : colonnes`).toBe(cfg.colonnes);
          if (cfg.largeur === 1280) for (const piste of m.pistes) proche(piste, 314, 1, `${etiquette} : largeur de colonne`);
        }
      }
    }
  });

  /* -------------------------------------------------------------- CA2 */

  test('CA2 - date et nom de carte partagent carte-liste__date et carte-liste__titre sur les trois écrans', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const leo = await creerBenevole(playwright);
    const course = await creerCourse(playwright, 'R9bW');
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [leo]));
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, course.id));
    const reecritures = { [course.id]: NOM_100_W };

    const cas = [
      { chemin: '/coureur', ident: coureurDe(alice), titre: 'coureur-titre', prefixe: 'coureur', ligne: 'coureur-ligne-course' },
      { chemin: '/coureur/inscriptions', ident: coureurDe(alice), titre: 'inscriptions-titre', prefixe: 'inscriptions', ligne: 'inscriptions-ligne' },
      { chemin: '/benevole', ident: benevoleDe(leo), titre: 'accueil-benevole-titre', prefixe: 'benevole', ligne: 'benevole-ligne-course' },
    ];
    for (const ecran of cas) {
      await page.unrouteAll({ behavior: 'ignoreErrors' });
      if (ecran.chemin === '/coureur') await reecrireNoms(page, '**/api/coureur/courses', 'id', 'nom', reecritures);
      if (ecran.chemin === '/coureur/inscriptions') await reecrireNoms(page, '**/api/coureur/inscriptions', 'courseId', 'courseNom', reecritures);
      if (ecran.chemin === '/benevole') await reecrireNoms(page, '**/api/benevole/courses', 'id', 'nom', reecritures);
      await ouvrir(page, ecran.ident, ecran.chemin, ecran.titre);
      const nom = page.getByTestId(`${ecran.prefixe}-course-nom`).getByText(NOM_100_W, { exact: true });
      await expect(nom, `${ecran.chemin} : nom complet dans le DOM`).toHaveCount(1);
      const ligne = page.getByTestId(ecran.ligne).filter({ has: nom });

      const date = ligne.getByTestId(`${ecran.prefixe}-course-date`);
      expect(await date.evaluate((e) => e.tagName)).toBe('TIME');
      expect(await css(date, 'fontSize')).toBe('20px');
      expect(await css(date, 'fontWeight')).toBe('700');
      expect(await css(date, 'color')).toBe(FORET);
      await expect(date).toHaveClass(/\bcarte-liste__date\b/);

      const titre = ligne.getByTestId(`${ecran.prefixe}-course-nom`);
      expect(await titre.evaluate((e) => e.tagName)).toBe('H2');
      expect(await css(titre, 'fontSize')).toBe('18px');
      expect(await css(titre, 'fontWeight')).toBe('700');
      expect(await css(titre, 'color')).toBe(FORET);
      for (const marge of ['marginTop', 'marginRight', 'marginBottom', 'marginLeft']) expect(await css(titre, marge)).toBe('0px');
      await expect(titre).toHaveClass(/\bcarte-liste__titre\b/);
      const boiteLigne = (await ligne.boundingBox())!;
      const boiteTitre = (await titre.boundingBox())!;
      expect(boiteTitre.x).toBeGreaterThanOrEqual(boiteLigne.x - 0.5);
      expect(boiteTitre.x + boiteTitre.width).toBeLessThanOrEqual(boiteLigne.x + boiteLigne.width + 0.5);

      const anciennes = await page.evaluate(() => document.querySelectorAll('.date, .nom, .course-entete').length);
      expect(anciennes, `${ecran.chemin} : classes date, nom, course-entete`).toBe(0);
    }
  });

  /* -------------------------------------------------------------- CA3 */

  test('CA3 - Gestion des courses à 320 px et texte à 200 % : marges réduites, rien ne déborde, Modifier donne le focus au nom', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const a = await creerCourse(playwright, 'R9bA');
    const b = await creerCourse(playwright, 'R9bB');

    await ouvrir(page, MASTER, '/administration/courses', 'titre-courses', 320, 640, true);
    await expect(ligneGestion(page, a.nom)).toBeVisible();
    await expect(ligneGestion(page, b.nom)).toBeVisible();
    const cartePage = page.locator('main > section.carte, .page > section.carte').first();
    expect(await css(cartePage, 'paddingTop')).toBe('24px');
    expect(await css(cartePage, 'paddingLeft')).toBe('24px');

    const ligneA = ligneGestion(page, a.nom);
    await ligneA.getByTestId('course-bouton-supprimer').click();
    await expect(ligneA.getByTestId('course-confirmation-suppression')).toBeVisible();
    await sansDefilementHorizontal(page);
    expect(await debordements(ligneA), 'carte ouverte avec confirmation').toEqual([]);

    await ligneGestion(page, b.nom).getByTestId('course-bouton-modifier').click();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await expect(page.getByTestId('course-champ-nom')).toBeFocused();
    const encart = page.locator('form.encart');
    await expect(encart).toBeVisible();
    expect(await css(encart, 'paddingTop')).toBe('24px');
    expect(await css(encart, 'paddingLeft')).toBe('24px');
    await sansDefilementHorizontal(page);
    expect(await debordements(encart), 'encart du formulaire').toEqual([]);
    expect(await debordements(ligneGestion(page, b.nom)), 'carte de la Course modifiée').toEqual([]);

    await ouvrir(page, MASTER, '/administration/courses', 'titre-courses', 1280, 800);
    await expect(ligneGestion(page, a.nom)).toBeVisible();
    expect(await css(page.locator('main > section.carte, .page > section.carte').first(), 'paddingTop')).toBe('24px');
    expect(await css(ligneGestion(page, a.nom), 'paddingTop')).toBe('16px');
  });
});

/* ------------------------------------------------------------------ CA4 */

interface AttenduMessage {
  fond: string;
  texte: string;
  graisse?: string;
}

const ATTENDUS: Record<'message--erreur' | 'message--succes' | 'message--info', AttenduMessage> = {
  'message--erreur': { fond: 'rgb(251, 233, 228)', texte: 'rgb(155, 28, 28)', graisse: '500' },
  'message--succes': { fond: 'rgb(227, 239, 230)', texte: FORET, graisse: '500' },
  'message--info': { fond: SABLE, texte: ENCRE },
};

async function verifierMessage(
  element: Locator,
  type: keyof typeof ATTENDUS,
  texte: string | RegExp,
  role: 'alert' | 'status' | null,
): Promise<void> {
  await expect(element).toBeVisible();
  await expect(element).toHaveText(texte);
  await expect(element).toHaveClass(new RegExp(`\\b${type}\\b`));
  if (role === null) await expect(element).not.toHaveAttribute('role', /.+/);
  else await expect(element).toHaveAttribute('role', role);
  const attendu = ATTENDUS[type];
  const etiquette = await element.getAttribute('data-testid');
  expect(await css(element, 'backgroundColor'), `${etiquette} : fond`).toBe(attendu.fond);
  expect(await css(element, 'color'), `${etiquette} : texte`).toBe(attendu.texte);
  if (attendu.graisse) expect(await css(element, 'fontWeight'), `${etiquette} : graisse`).toBe(attendu.graisse);
  expect(await css(element, 'borderLeftWidth'), `${etiquette} : bordure gauche`).toBe('4px');
  for (const p of ['paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft']) expect(await css(element, p), `${etiquette} : ${p}`).toBe('12px');
  expect(await css(element, 'borderTopLeftRadius'), `${etiquette} : rayon`).toBe('6px');
}

test.describe('Messages migrés vers message--erreur, message--succes et message--info', () => {
  test('CA4 - Espace coureur et Mes inscriptions : erreur 409, succès d\'inscription, succès de désinscription, erreur de chargement', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const complete = await creerCourse(playwright, 'R9bC');
    const libre = await creerCourse(playwright, 'R9bL');

    // erreur : 409 d'inscription
    await page.route(
      (url) => url.pathname === `/api/coureur/courses/${complete.id}/inscriptions`,
      (route) =>
        route.request().method() !== 'POST'
          ? route.fallback()
          : route.fulfill({
              status: 409,
              contentType: 'application/problem+json',
              body: JSON.stringify({ type: 'about:blank', title: 'Conflit', status: 409, detail: 'Conflit', code: 'COURSE_COMPLETE' }),
            }),
    );
    await ouvrir(page, coureurDe(alice), '/coureur', 'coureur-titre');
    const ligneComplete = page.getByTestId('coureur-ligne-course').filter({ has: page.getByTestId('coureur-course-nom').getByText(complete.nom, { exact: true }) });
    await ligneComplete.getByTestId('coureur-bouton-sinscrire').click();
    await verifierMessage(page.getByTestId('coureur-erreur'), 'message--erreur', 'Cette course est complète.', 'alert');
    await page.unrouteAll({ behavior: 'ignoreErrors' });

    // succès : inscription
    const ligneLibre = page.getByTestId('coureur-ligne-course').filter({ has: page.getByTestId('coureur-course-nom').getByText(libre.nom, { exact: true }) });
    await ligneLibre.getByTestId('coureur-bouton-sinscrire').click();
    await verifierMessage(page.getByTestId('coureur-message-inscription'), 'message--succes', `Vous êtes inscrit à ${libre.nom} avec le dossard 1.`, 'status');

    // succès : désinscription
    await page.goto('/coureur/inscriptions');
    const ligne = page.getByTestId('inscriptions-ligne').filter({ has: page.getByTestId('inscriptions-course-nom').getByText(libre.nom, { exact: true }) });
    await ligne.getByTestId('inscriptions-bouton-desinscrire').click();
    await ligne.getByTestId('inscriptions-bouton-confirmer-desinscription').click();
    await verifierMessage(page.getByTestId('inscriptions-message-desinscription'), 'message--succes', `Vous êtes désinscrit de ${libre.nom}.`, 'status');

    // erreur : chargement en 500
    await page.route('**/api/coureur/inscriptions', (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'ERREUR_INTERNE' }) })
        : route.continue(),
    );
    await page.reload();
    await verifierMessage(page.getByTestId('inscriptions-erreur-chargement'), 'message--erreur', /\S/, 'alert');
  });

  test('CA4 - connexion, création de compte et Mon compte : erreur, succès et information', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    // erreur générale de connexion
    await page.goto('/connexion');
    await page.getByTestId('champ-pseudo').fill(pseudoUnique('inconnu'));
    await page.getByTestId('champ-mot-de-passe').fill('mauvais-mot-de-passe-1');
    await page.getByTestId('bouton-connexion').click();
    await verifierMessage(page.getByTestId('erreur-generale'), 'message--erreur', 'Pseudo ou mot de passe incorrect.', 'alert');

    // succès de création de compte
    const pseudo = pseudoUnique();
    await page.goto('/creer-compte');
    await page.getByTestId('champ-pseudo').fill(pseudo);
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
    await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
    await page.getByTestId('bouton-creer-compte').click();
    await verifierMessage(page.getByTestId('message-succes'), 'message--succes', new RegExp(`Compte créé pour ${pseudo}\\.`), 'status');

    // succès de Mon compte
    await page.context().clearCookies();
    const coureur = await creerCoureur(playwright);
    await ouvrir(page, coureurDe(coureur), '/mon-compte', 'titre-mon-compte');
    await deplierChangementMotDePasse(page);
    await page.getByTestId('mon-compte-champ-actuel').fill(MOT_DE_PASSE);
    await page.getByTestId('mon-compte-champ-nouveau').fill('nouveau-mot-de-passe-1');
    await page.getByTestId('mon-compte-champ-confirmation').fill('nouveau-mot-de-passe-1');
    await page.getByTestId('mon-compte-bouton-changer').click();
    await verifierMessage(page.getByTestId('mon-compte-message-succes'), 'message--succes', /\S/, 'status');

    // information après déconnexion
    await seDeconnecterParLeMenu(page);
    await verifierMessage(page.getByTestId('message-deconnexion'), 'message--info', 'Vous êtes déconnecté.', 'status');
  });

  test('CA4 - Gestion des courses et fiche : succès de déclaration, suppression en succès puis en information, bénévoles', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const leo = await creerBenevole(playwright);
    const aSupprimer = await creerCourse(playwright, 'R9bS');
    const disparue = await creerCourse(playwright, 'R9bD');
    const verrouillee = await creerCourse(playwright, 'R9bV');
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, verrouillee.id, [leo]));

    // succès de déclaration
    await ouvrir(page, MASTER, '/administration/courses', 'titre-courses');
    const nom = nomSansQr('R9bN');
    await page.getByTestId('course-champ-nom').fill(nom);
    await page.getByTestId('course-champ-date').fill(dateAffichee(dateDansJours(joursLointains())));
    await page.getByTestId('course-champ-distance').fill('1000');
    await page.getByTestId('course-champ-duree').fill('2');
    await page.getByTestId('course-champ-denivele').fill('10');
    await page.getByTestId('course-champ-participants-max').fill('20');
    await page.getByTestId('course-champ-boucles-max').fill('5');
    await page.getByTestId('course-bouton-creer').click();
    await verifierMessage(page.getByTestId('course-message-succes'), 'message--succes', `La course ${nom} a été déclarée.`, 'status');

    // suppression : succès
    const ligneA = ligneGestion(page, aSupprimer.nom);
    await ligneA.getByTestId('course-bouton-supprimer').click();
    await ligneA.getByTestId('course-bouton-confirmer-suppression').click();
    await verifierMessage(page.getByTestId('course-message-suppression'), 'message--succes', `La course ${aSupprimer.nom} a été supprimée.`, 'status');

    // suppression : information (la Course n'existe plus)
    const ligneD = ligneGestion(page, disparue.nom);
    await ligneD.getByTestId('course-bouton-supprimer').click();
    await avecApi(playwright, (ctx) => supprimerCourseParApi(ctx, disparue.id));
    await ligneD.getByTestId('course-bouton-confirmer-suppression').click();
    await verifierMessage(page.getByTestId('course-message-suppression'), 'message--info', "Cette course n'existe plus.", 'status');

    // fiche : succès à l'enregistrement des bénévoles
    await page.goto(`/administration/courses/${verrouillee.id}`);
    await expect(page.getByTestId('fiche-titre')).toBeVisible();
    await page.getByLabel(leo, { exact: true }).uncheck();
    await page.getByTestId('fiche-bouton-enregistrer').click();
    await verifierMessage(page.getByTestId('fiche-message-succes'), 'message--succes', `Les bénévoles de la course ${verrouillee.nom} ont été enregistrés.`, 'status');

    // fiche : information (Course terminée, affectations verrouillées)
    placerStatutCourseEnBase(verrouillee.id, 'TERMINEE');
    await page.reload();
    await expect(page.getByTestId('fiche-titre')).toBeVisible();
    await verifierMessage(page.getByTestId('fiche-benevoles-verrouille'), 'message--info', /\S/, null);

    // fiche : erreur sur la liste des bénévoles
    await page.route('**/api/administration/benevoles', (route) =>
      route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'ERREUR_INTERNE' }) }),
    );
    await page.reload();
    await verifierMessage(page.getByTestId('fiche-benevoles-erreur'), 'message--erreur', /\S/, 'alert');
  });

  test.describe('Absence des anciennes classes et contraste forcé', () => {
    let jeu: JeuReference;
    test.beforeAll(async ({ playwright }) => {
      jeu = await preparerJeuReference(playwright);
    });

    for (const scene of SCENES) {
      test(`CA4 - « ${scene.nom} » n'emploie plus .erreur--generale, .succes, .message-succes ni .message-info`, async ({ page }) => {
        test.setTimeout(90_000);
        const nettoyage = await ouvrirScene(page, scene, jeu);
        const trouves = await page.evaluate(() => document.querySelectorAll('.erreur--generale, .succes, .message-succes, .message-info').length);
        expect(trouves).toBe(0);
        await nettoyage?.();
      });
    }

    test('CA4 - en contraste forcé, le message garde sa bordure gauche et son texte', async ({ page }) => {
      await page.emulateMedia({ forcedColors: 'active' });
      const scene = ETATS.find((e) => e.nom === 'connexion avec erreur affichee')!;
      await ouvrirScene(page, scene, jeu);
      const message = page.getByTestId('erreur-generale');
      await expect(message).toBeVisible();
      await expect(message).toHaveText('Pseudo ou mot de passe incorrect.');
      expect(parseFloat(await css(message, 'borderLeftWidth'))).toBeGreaterThanOrEqual(1);
      expect(await css(message, 'borderLeftStyle')).not.toBe('none');
    });
  });
});

/* ------------------------------------------------------------------ CA5 et CA6 */

function feuillesDe(dossier: string): string[] {
  const sortie: string[] = [];
  for (const entree of readdirSync(dossier)) {
    const chemin = join(dossier, entree);
    if (statSync(chemin).isDirectory()) sortie.push(...feuillesDe(chemin));
    else if (/\.(css|scss)$/.test(entree)) sortie.push(chemin);
  }
  return sortie;
}

test.describe('Passe de cohérence', () => {
  let jeu: JeuReference;
  test.beforeAll(async ({ playwright }) => {
    jeu = await preparerJeuReference(playwright);
  });

  test('CA5 - aucune feuille de style de frontend/src ne contient outline: none', () => {
    const racine = join(__dirname, '..', '..', 'frontend', 'src');
    const feuilles = feuillesDe(racine);
    expect(feuilles.length).toBeGreaterThan(0);
    const fautives = feuilles.filter((f) => /outline\s*:\s*none/.test(readFileSync(f, 'utf8')));
    expect(fautives).toEqual([]);
  });

  for (const scene of SCENES) {
    test(`CA5 - « ${scene.nom} » : contrastes, palette, titres et focus clavier`, async ({ page }) => {
      test.setTimeout(90_000);
      await page.emulateMedia({ reducedMotion: 'reduce' });
      const nettoyage = await ouvrirScene(page, scene, jeu);
      await page.mouse.move(0, 0);

      const mesures = (await page.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
      expect(mesures.length).toBeGreaterThan(0);
      expect(mesures.filter((m) => m.ratio + 1e-9 < m.seuil).map((m) => `${m.element} ${m.ratio.toFixed(2)} < ${m.seuil}`), 'contrastes').toEqual([]);
      const ecarts = (await page.evaluate((a) => (window as unknown as { __t: { horsPalette(a: string[]): EcartCouleur[] } }).__t.horsPalette(a), AUTORISEES)) as EcartCouleur[];
      expect(ecarts, 'couleurs hors palette').toEqual([]);

      const niveaux = await page.evaluate(() =>
        Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6'))
          .filter((h) => (h as HTMLElement).checkVisibility())
          .map((h) => Number(h.tagName.substring(1))),
      );
      expect(niveaux.filter((n) => n === 1), 'nombre de h1').toHaveLength(1);
      let maximum = 0;
      for (const n of niveaux) {
        expect(n, `saut de niveau dans ${niveaux.join(',')}`).toBeLessThanOrEqual(maximum + 1);
        maximum = Math.max(maximum, n);
      }

      // Tab depuis le haut de page, menu fermé, jusqu'à épuisement (comme `theme-palette-contraste` : pas pour les scènes
      // qui ont déjà placé le focus dans un état, le point de départ du Tab y serait celui de l'action).
      if (scene.etat) {
        await nettoyage?.();
        return;
      }
      await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
      let dernier = -1;
      let arrets = 0;
      for (let i = 0; i < 40; i++) {
        await page.keyboard.press('Tab');
        const etat = await page.evaluate(() => {
          const el = document.activeElement as HTMLElement | null;
          if (!el || el === document.body) return null;
          const t = (window as unknown as { __t: { etatFocus(): { index: number; element: string; outlineStyle: string; outlineWidth: string; outlineColor: string; dansEntete: boolean } | null } }).__t;
          const base = t.etatFocus()!;
          const ancetres: string[] = [];
          for (let n = el.parentElement; n && n.tagName !== 'MAIN' && n !== document.body; n = n.parentElement) {
            const cs = getComputedStyle(n);
            if (cs.overflowX !== 'visible' || cs.overflowY !== 'visible') ancetres.push(`${n.tagName}.${n.className} overflow ${cs.overflowX}/${cs.overflowY}`);
          }
          return { ...base, ancetres: base.dansEntete ? [] : ancetres };
        });
        if (!etat || etat.index <= dernier) break;
        dernier = etat.index;
        arrets++;
        expect(etat.outlineStyle, `${etat.element} : style du contour`).toBe('solid');
        expect(etat.outlineWidth, `${etat.element} : épaisseur du contour`).toBe('3px');
        expect(etat.outlineColor, `${etat.element} : couleur du contour`).toBe(etat.dansEntete ? FOCUS_ENTETE : FOCUS);
        expect(etat.ancetres, `${etat.element} : ancêtres avec overflow`).toEqual([]);
      }
      expect(arrets, 'au moins un arrêt de focus').toBeGreaterThan(0);
      await nettoyage?.();
    });
  }

  for (const largeur of [360, 320]) {
    for (const scene of SCENES) {
      test(`CA6 - « ${scene.nom} » à ${largeur} px, texte normal puis à 200 %`, async ({ page }) => {
        test.setTimeout(90_000);
        await page.setViewportSize({ width: largeur, height: 640 });
        const nettoyage = await ouvrirScene(page, scene, jeu);
        for (const grand of [false, true]) {
          if (grand) await page.addStyleTag({ content: AGRANDIR });
          const etiquette = grand ? '200 %' : 'texte normal';
          const m = (await page.evaluate(() => (window as unknown as { __t: { responsive(): MesuresResponsive } }).__t.responsive())) as MesuresResponsive;
          expect(m.scrollWidth, `${etiquette} : scrollWidth`).toBeLessThanOrEqual(m.clientWidth);
          expect(m.cibles, `${etiquette} : cibles de moins de 44 px`).toEqual([]);
          expect(m.petits, `${etiquette} : textes de moins de 13 px`).toEqual([]);
          if (!grand) for (const carte of m.cartes) proche(carte.largeur, m.clientWidth - 32, 1, `${scene.nom} : carte de la page`);
          const sorties = await page.evaluate(() => {
            const resultat: string[] = [];
            for (const carte of Array.from(document.querySelectorAll('.page > .carte, .page > section.carte'))) {
              if (!(carte as HTMLElement).checkVisibility()) continue;
              const c = carte.getBoundingClientRect();
              for (const el of Array.from(carte.querySelectorAll('*'))) {
                if (!(el as HTMLElement).checkVisibility()) continue;
                const r = el.getBoundingClientRect();
                if (r.width === 0 && r.height === 0) continue;
                if (r.left < c.left - 0.5 || r.right > c.right + 0.5) {
                  resultat.push(`${el.getAttribute('data-testid') ?? el.tagName} [${r.left.toFixed(1)}; ${r.right.toFixed(1)}] hors de la carte [${c.left.toFixed(1)}; ${c.right.toFixed(1)}]`);
                }
              }
            }
            return resultat;
          });
          expect(sorties, `${etiquette} : descendants hors de la carte`).toEqual([]);
        }
        await nettoyage?.();
      });
    }
  }
});

/* ------------------------------------------------------------------ CA7 */

test.describe('Modes d\'affichage', () => {
  test('CA7 - mouvement réduit, contraste forcé et schéma clair sur les quatre écrans en cartes', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const leo = await creerBenevole(playwright);
    const enPreparation = await creerCourse(playwright, 'R9bP');
    const enCours = await creerCourse(playwright, 'R9bE');
    const terminee = await creerCourse(playwright, 'R9bT');
    for (const course of [enPreparation, enCours, terminee]) {
      await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [leo]));
      await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, course.id));
    }
    placerStatutCourseEnBase(enCours.id, 'EN_COURS');
    placerStatutCourseEnBase(terminee.id, 'TERMINEE');

    const identifiants: Record<string, Identifiants> = {
      '/coureur': coureurDe(alice),
      '/coureur/inscriptions': coureurDe(alice),
      '/benevole': benevoleDe(leo),
      '/administration/courses': MASTER,
    };

    // mouvement réduit
    await page.emulateMedia({ reducedMotion: 'reduce' });
    for (const ecran of ECRANS_EN_CARTES) {
      await ouvrir(page, identifiants[ecran.chemin], ecran.chemin, ecran.titre);
      await expect(page.getByTestId(ecran.grille)).toBeVisible();
      const transitions = await page.evaluate(() =>
        Array.from(document.querySelectorAll('button, a.bouton'))
          .filter((b) => (b as HTMLElement).checkVisibility())
          .map((b) => getComputedStyle(b).transitionDuration),
      );
      if (ecran.chemin !== '/benevole') expect(transitions.length, `${ecran.nom} : boutons`).toBeGreaterThan(0);
      for (const t of transitions) expect(t, `${ecran.nom} : transition-duration`).toBe('0s');
      expect(await page.evaluate(() => document.getAnimations().length), `${ecran.nom} : animations`).toBe(0);
    }
    expect(await css(page.locator('html'), 'colorScheme')).toBe('light');

    // contraste forcé
    await page.emulateMedia({ reducedMotion: 'no-preference', forcedColors: 'active' });
    const cartes: Record<string, string> = {
      '/coureur': 'coureur-ligne-course',
      '/coureur/inscriptions': 'inscriptions-ligne',
      '/benevole': 'benevole-ligne-course',
      '/administration/courses': 'ligne-course',
    };
    const statuts: Record<string, string[]> = {
      '/coureur': ['coureur-statut-inscription'],
      '/coureur/inscriptions': ['inscriptions-statut', 'inscriptions-course-statut'],
      '/benevole': ['benevole-course-statut'],
      '/administration/courses': ['course-statut'],
    };
    for (const ecran of ECRANS_EN_CARTES) {
      await ouvrir(page, identifiants[ecran.chemin], ecran.chemin, ecran.titre);
      const lignes = page.getByTestId(cartes[ecran.chemin]);
      await expect(lignes.first()).toBeVisible();
      for (const ligne of await lignes.all()) {
        expect(parseFloat(await css(ligne, 'borderTopWidth')), `${ecran.nom} : bordure de carte`).toBeGreaterThanOrEqual(1);
        expect(await css(ligne, 'borderTopStyle')).not.toBe('none');
      }
      for (const id of statuts[ecran.chemin]) {
        const textes = await page.getByTestId(id).allInnerTexts();
        expect(textes.length, `${ecran.nom} : ${id}`).toBeGreaterThan(0);
        for (const t of textes) expect(t.trim().length).toBeGreaterThan(0);
      }
      for (const visuel of await page.locator('.visuel-substitution').all()) {
        if (await visuel.isVisible()) expect(await css(visuel, 'backgroundImage'), `${ecran.nom} : visuel de substitution`).toBe('none');
      }
      if (ecran.chemin === '/administration/courses') {
        const encart = page.locator('form.encart');
        expect(parseFloat(await css(encart, 'borderTopWidth'))).toBeGreaterThanOrEqual(1);
        expect(await css(encart, 'borderTopStyle')).not.toBe('none');
      }
    }
    await page.emulateMedia({ forcedColors: 'none' });
    expect(await css(page.locator('html'), 'colorScheme')).toBe('light');
  });
});

/* ------------------------------------------------------------------ CA10 */

test.describe('Parcours de non-régression sur téléphone', () => {
  test('CA10 - déclarer, affecter, s\'inscrire, se désinscrire et ouvrir l\'Espace bénévole à 360 px', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const alice = await creerCoureur(playwright);
    const leo = await creerBenevole(playwright);
    const nom = nomSansQr('R9bP');

    // admin : déclaration d'une Course (Gestion des courses)
    await ouvrir(page, MASTER, '/administration/courses', 'titre-courses', 360, 640);
    await page.getByTestId('course-champ-nom').fill(nom);
    await page.getByTestId('course-champ-date').fill(dateAffichee(dateDansJours(joursLointains())));
    await page.getByTestId('course-champ-distance').fill('1000');
    await page.getByTestId('course-champ-duree').fill('2');
    await page.getByTestId('course-champ-denivele').fill('10');
    await page.getByTestId('course-champ-participants-max').fill('20');
    await page.getByTestId('course-champ-boucles-max').fill('5');
    await page.getByTestId('course-bouton-creer').click();
    const succes = page.getByTestId('course-message-succes');
    await expect(succes).toHaveText(`La course ${nom} a été déclarée.`);
    await expect(succes).toHaveClass(/\bmessage--succes\b/);
    await sansDefilementHorizontal(page);

    // admin : fiche, affectation du bénévole
    await ligneGestion(page, nom).getByTestId('course-lien-fiche').click();
    await expect(page.getByTestId('fiche-titre')).toBeVisible();
    await page.getByLabel(leo, { exact: true }).check();
    await page.getByTestId('fiche-bouton-enregistrer').click();
    const succesFiche = page.getByTestId('fiche-message-succes');
    await expect(succesFiche).toHaveText(`Les bénévoles de la course ${nom} ont été enregistrés.`);
    await expect(succesFiche).toHaveClass(/\bmessage--succes\b/);
    await sansDefilementHorizontal(page);

    // coureur : inscription puis désinscription
    await ouvrir(page, coureurDe(alice), '/coureur', 'coureur-titre', 360, 640);
    const ligne = page.getByTestId('coureur-ligne-course').filter({ has: page.getByTestId('coureur-course-nom').getByText(nom, { exact: true }) });
    await ligne.getByTestId('coureur-bouton-sinscrire').click();
    const inscription = page.getByTestId('coureur-message-inscription');
    await expect(inscription).toHaveText(`Vous êtes inscrit à ${nom} avec le dossard 1.`);
    await expect(inscription).toHaveClass(/\bmessage--succes\b/);
    await sansDefilementHorizontal(page);

    await page.goto('/coureur/inscriptions');
    await expect(page.getByTestId('inscriptions-titre')).toBeVisible();
    const ins = page.getByTestId('inscriptions-ligne').filter({ has: page.getByTestId('inscriptions-course-nom').getByText(nom, { exact: true }) });
    await sansDefilementHorizontal(page);
    await ins.getByTestId('inscriptions-bouton-desinscrire').click();
    await ins.getByTestId('inscriptions-bouton-confirmer-desinscription').click();
    const desinscription = page.getByTestId('inscriptions-message-desinscription');
    await expect(desinscription).toHaveText(`Vous êtes désinscrit de ${nom}.`);
    await expect(desinscription).toHaveClass(/\bmessage--succes\b/);
    await sansDefilementHorizontal(page);

    // bénévole : la Course dans son espace
    await ouvrir(page, benevoleDe(leo), '/benevole', 'accueil-benevole-titre', 360, 640);
    await expect(page.getByTestId('benevole-course-nom').getByText(nom, { exact: true })).toBeVisible();
    await sansDefilementHorizontal(page);
  });
});
