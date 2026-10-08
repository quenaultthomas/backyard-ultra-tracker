import { expect, type APIRequestContext, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
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
  nomCourseUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { deplierChangementMotDePasse } from './aide-mon-compte';
import { placerStatutCourseEnBase, sinscrireParApi, type Inscription } from './aide-coureur';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export const BASE_URL = process.env.BASE_URL ?? 'http://localhost';

/** Palette de la RG3 : variable -> hexadécimal (casse de la spec). */
export const PALETTE: Record<string, string> = {
  '--couleur-foret': '#14352A',
  '--couleur-foret-survol': '#1F5A43',
  '--couleur-creme': '#F7F1E3',
  '--couleur-surface': '#FFFCF5',
  '--couleur-sable': '#EFE6D0',
  '--couleur-trait': '#D9CFB6',
  '--couleur-encre': '#1B2A22',
  '--couleur-encre-douce': '#4A5A50',
  '--couleur-orange': '#F28C28',
  '--couleur-orange-fonce': '#A84300',
  '--couleur-orange-pale': '#FBE9CF',
  '--couleur-danger': '#9B1C1C',
  '--couleur-danger-survol': '#7F1D1D',
  '--couleur-danger-pale': '#FBE9E4',
  '--couleur-succes-pale': '#E3EFE6',
  '--couleur-bordure-champ': '#6F7A6E',
  '--couleur-desactive-fond': '#DDD6C3',
  '--couleur-desactive-texte': '#5F6A62',
};

/** Alias sémantiques de la RG3 (valeur `var()`). */
export const ALIAS: Record<string, string> = {
  '--couleur-fond-page': 'var(--couleur-creme)',
  '--couleur-texte': 'var(--couleur-encre)',
  '--couleur-lien': 'var(--couleur-foret-survol)',
  '--couleur-focus': 'var(--couleur-orange-fonce)',
  '--couleur-focus-sur-fonce': 'var(--couleur-orange)',
};

/** Variables de typographie (RG6), d'espacement, de rayon, d'ombre et de cible (RG8). */
export const AUTRES_VARIABLES: Record<string, string> = {
  '--police-texte': "'Inter', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
  '--interligne-texte': '1.5',
  '--interligne-titre': '1.2',
  '--poids-normal': '400',
  '--poids-moyen': '500',
  '--poids-gras': '700',
  '--taille-petite': '0.8125rem',
  '--taille-secondaire': '0.875rem',
  '--taille-base': '1rem',
  '--taille-moyenne': '1.125rem',
  '--taille-grande': '1.25rem',
  '--taille-titre': '1.75rem',
  '--espace-1': '0.25rem',
  '--espace-2': '0.5rem',
  '--espace-3': '0.75rem',
  '--espace-4': '1rem',
  '--espace-5': '1.25rem',
  '--espace-6': '1.5rem',
  '--espace-8': '2rem',
  '--espace-12': '3rem',
  '--rayon-s': '0.375rem',
  '--rayon-m': '0.75rem',
  '--rayon-pastille': '999px',
  '--ombre-carte': '0 1px 2px rgb(20 53 42 / 0.10), 0 4px 12px rgb(20 53 42 / 0.08)',
  '--cible-min': '2.75rem',
  '--largeur-carte': '26rem',
  '--duree-transition': '150ms',
};

export function hexVersRgb(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
}

export const RGB_PALETTE: string[] = Object.values(PALETTE).map(hexVersRgb);
export const RGB_TRANSPARENT = 'rgba(0, 0, 0, 0)';

/** Anciennes valeurs qui ne doivent plus apparaître (CA5). */
export const ANCIENNES_VALEURS = [
  'rgb(17, 24, 39)',
  'rgb(249, 250, 251)',
  'rgb(29, 78, 216)',
  'rgb(107, 114, 128)',
  'rgb(185, 28, 28)',
  'rgb(245, 158, 11)',
];

/**
 * Normalise une valeur de variable CSS pour comparer ce que la spec écrit avec ce que le CSS minifié
 * (esbuild) restitue : sans guillemets, longueurs en px (rem x 16), durées en secondes, zéros de tête
 * supprimés, espaces réduits, casse ignorée. Les codes hexadécimaux sont comparés tels quels (minuscules).
 */
export function normaliserValeurCss(valeur: string): string {
  const v = valeur.trim().replace(/["']/g, '').replace(/\s+/g, ' ').toLowerCase();
  if (v.startsWith('#')) return v;
  return v.replace(/(\d*\.?\d+)(rem|px|ms|s)?(?![\w.])/g, (_m, nombre: string, unite?: string) => {
    const n = parseFloat(nombre);
    if (unite === 'rem') return `${n * 16}px`;
    if (unite === 'ms') return `${n / 1000}s`;
    return `${n}${unite ?? ''}`;
  });
}

/* ------------------------------------------------------------------ contraste WCAG 2.x */

export function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const canaux = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * canaux[0] + 0.7152 * canaux[1] + 0.0722 * canaux[2];
}

export function ratioContraste(hexA: string, hexB: string): number {
  const a = luminance(hexA);
  const b = luminance(hexB);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

/* ------------------------------------------------------------------ jeu de référence */

export interface JeuReference {
  coureur: string;
  benevole: string;
  courseA: { id: string; nom: string };
  courseB: { id: string; nom: string };
  inscriptionA: Inscription;
  inscriptionB: Inscription;
}

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

/**
 * Nom de course de 60 caractères, unique, avec deux mots longs de 29 caractères (cas limite de débordement).
 * Aucune suite de 43 caractères sans espace : ils ne ressemblent pas à un jeton QR (voir `inscription-course.spec.ts`).
 */
export function nomCourseLong(): string {
  const unique = nomCourseUnique('').replace(/\s+/g, '');
  const premier = `Ultra${unique}`.padEnd(29, 'z').slice(0, 29);
  return `${premier} ${'y'.repeat(30)}`;
}

/**
 * Jeu de référence de la spec : une Course A `EN_PREPARATION`, une Course B passée `EN_COURS` (SQL),
 * une coureuse inscrite aux deux (dossard 1 pour chacune), un bénévole affecté aux deux.
 */
export async function preparerJeuReference(playwright: PlaywrightLib): Promise<JeuReference> {
  const coureur = pseudoUnique('alice');
  const benevole = pseudoBenevoleUnique();
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, coureur));
  await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
  const a = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom: nomCourseLong() })));
  const b = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom: nomCourseLong() })));
  const idA = a['id'] as string;
  const idB = b['id'] as string;
  await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, idA, [benevole]));
  await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, idB, [benevole]));
  const inscriptionA = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, coureur, idA));
  const inscriptionB = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, coureur, idB));
  placerStatutCourseEnBase(idB, 'EN_COURS');
  return {
    coureur,
    benevole,
    courseA: { id: idA, nom: a['nom'] as string },
    courseB: { id: idB, nom: b['nom'] as string },
    inscriptionA,
    inscriptionB,
  };
}

/* ------------------------------------------------------------------ écrans */

export type Role = 'anonyme' | 'master' | 'benevole' | 'coureur';

export interface Scene {
  nom: string;
  route: string | ((jeu: JeuReference) => string);
  role: Role;
  /** Attend que l'écran soit chargé avec ses données. */
  pret: (page: Page, jeu: JeuReference) => Promise<void>;
  /** Amène l'écran dans un état particulier (erreur, confirmation, envoi). Renvoie un nettoyage éventuel. */
  etat?: (page: Page, jeu: JeuReference) => Promise<(() => Promise<void>) | void>;
}

const visible = (testid: string) => async (page: Page) => {
  await expect(page.getByTestId(testid).first()).toBeVisible();
};

/** Les 13 écrans de la RG17. */
export const ECRANS: Scene[] = [
  { nom: 'accueil', route: '/', role: 'anonyme', pret: async (p) => { await expect(p.getByTestId('accroche')).toBeVisible(); } },
  { nom: 'connexion', route: '/connexion', role: 'anonyme', pret: visible('titre-connexion') },
  { nom: 'creation de compte', route: '/creer-compte', role: 'anonyme', pret: visible('titre-creer-compte') },
  { nom: 'mon compte', route: '/mon-compte', role: 'coureur', pret: async (p) => { await expect(p.getByTestId('mon-compte-suppression')).toBeVisible(); } },
  { nom: 'acces refuse', route: '/acces-refuse', role: 'anonyme', pret: visible('titre-acces-refuse') },
  { nom: 'administration', route: '/administration', role: 'master', pret: visible('titre-administration') },
  { nom: 'gestion des admins', route: '/administration/admins', role: 'master', pret: visible('titre-admins') },
  { nom: 'gestion des benevoles', route: '/administration/benevoles', role: 'master', pret: visible('titre-benevoles') },
  { nom: 'gestion des courses', route: '/administration/courses', role: 'master', pret: async (p, j) => { await expect(p.getByTestId('liste-courses')).toBeVisible(); await expect(p.getByTestId('course-nom').getByText(j.courseA.nom, { exact: true })).toBeVisible(); } },
  { nom: 'fiche de course', route: (j) => `/administration/courses/${j.courseA.id}`, role: 'master', pret: async (p) => { await expect(p.getByTestId('fiche-titre')).toBeVisible(); await expect(p.getByTestId('fiche-inscrits')).toBeVisible(); await expect(p.getByTestId('fiche-inscrits-ligne').first()).toBeVisible(); } },
  { nom: 'espace benevole', route: '/benevole', role: 'benevole', pret: async (p) => { await expect(p.getByTestId('benevole-ligne-course').first()).toBeVisible(); } },
  { nom: 'courses ouvertes', route: '/coureur', role: 'coureur', pret: async (p) => { await expect(p.getByTestId('coureur-ligne-course').first()).toBeVisible(); } },
  { nom: 'mes inscriptions', route: '/coureur/inscriptions', role: 'coureur', pret: async (p) => { await expect(p.getByTestId('inscriptions-ligne').first()).toBeVisible(); await expect(p.getByTestId('inscriptions-qr').first()).toBeVisible(); } },
];

/** États particuliers de la CA5 / CA6 (mêmes actions que les specs existantes). */
export const ETATS: Scene[] = [
  {
    nom: 'connexion avec erreur affichee',
    route: '/connexion',
    role: 'anonyme',
    pret: visible('titre-connexion'),
    etat: async (page) => {
      await page.getByTestId('champ-pseudo').fill(pseudoUnique('inconnu'));
      await page.getByTestId('champ-mot-de-passe').fill('mauvais-mot-de-passe-1');
      await page.getByTestId('bouton-connexion').click();
      await expect(page.getByTestId('erreur-generale')).toBeVisible();
    },
  },
  {
    nom: 'connexion avec bouton desactive pendant l envoi',
    route: '/connexion',
    role: 'anonyme',
    pret: visible('titre-connexion'),
    etat: async (page) => {
      let liberer: () => void = () => undefined;
      const attente = new Promise<void>((resolve) => (liberer = resolve));
      await page.route('**/api/connexion', async (route) => {
        await attente;
        await route.continue();
      });
      await page.getByTestId('champ-pseudo').fill(pseudoUnique('inconnu'));
      await page.getByTestId('champ-mot-de-passe').fill('mauvais-mot-de-passe-1');
      await page.getByTestId('bouton-connexion').click();
      await expect(page.getByTestId('bouton-connexion')).toBeDisabled();
      return async () => {
        liberer();
        await page.unrouteAll({ behavior: 'ignoreErrors' });
      };
    },
  },
  {
    nom: 'creation de compte avec champs invalides',
    route: '/creer-compte',
    role: 'anonyme',
    pret: visible('titre-creer-compte'),
    etat: async (page) => {
      await page.getByTestId('bouton-creer-compte').click();
      await expect(page.getByTestId('erreur-pseudo')).toBeVisible();
    },
  },
  {
    nom: 'gestion des courses avec confirmation de suppression',
    route: '/administration/courses',
    role: 'master',
    pret: ECRANS[8].pret,
    etat: async (page, jeu) => {
      const ligne = page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(jeu.courseA.nom, { exact: true }) });
      await ligne.getByTestId('course-bouton-supprimer').click();
      await expect(ligne.getByTestId('course-confirmation-suppression')).toBeVisible();
    },
  },
  {
    nom: 'gestion des courses vide',
    route: '/administration/courses',
    role: 'master',
    pret: async (page) => {
      // La liste est interceptée à `[]` puis la page est rechargée pour que l'état vide soit rendu.
      await page.route('**/api/administration/courses', (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
          : route.continue(),
      );
      await page.reload();
      await expect(page.getByTestId('courses-vide')).toBeVisible();
    },
  },
  {
    nom: 'mon compte avec confirmation de suppression',
    route: '/mon-compte',
    role: 'coureur',
    pret: ECRANS[3].pret,
    etat: async (page) => {
      await page.getByTestId('mon-compte-bouton-supprimer').click();
      await expect(page.getByTestId('mon-compte-confirmation-suppression')).toBeVisible();
    },
  },
  {
    nom: 'mon compte avec changement de mot de passe deplie',
    route: '/mon-compte',
    role: 'coureur',
    pret: async (page) => {
      await deplierChangementMotDePasse(page);
    },
  },
  {
    nom: 'mes inscriptions avec confirmation de desinscription',
    route: '/coureur/inscriptions',
    role: 'coureur',
    pret: ECRANS[12].pret,
    etat: async (page, jeu) => {
      const ligne = page.getByTestId('inscriptions-ligne').filter({ has: page.getByTestId('inscriptions-course-nom').getByText(jeu.courseA.nom, { exact: true }) });
      await ligne.getByTestId('inscriptions-bouton-desinscrire').click();
      await expect(ligne.getByTestId('inscriptions-confirmation-desinscription')).toBeVisible();
    },
  },
  {
    nom: 'courses ouvertes vide',
    route: '/coureur',
    role: 'coureur',
    pret: async (page) => {
      // La liste est interceptée à `[]` puis la page est rechargée pour que l'état vide soit rendu.
      await page.route('**/api/coureur/courses', (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
          : route.continue(),
      );
      await page.reload();
      await expect(page.getByTestId('coureur-vide')).toBeVisible();
    },
  },
  {
    nom: 'mes inscriptions vide',
    route: '/coureur/inscriptions',
    role: 'coureur',
    pret: async (page) => {
      await page.route('**/api/coureur/inscriptions', (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
          : route.continue(),
      );
      await page.reload();
      await expect(page.getByTestId('inscriptions-vide')).toBeVisible();
    },
  },
];

export function cheminDe(scene: Scene, jeu: JeuReference): string {
  return typeof scene.route === 'function' ? scene.route(jeu) : scene.route;
}

export function identifiantsDe(role: Role, jeu: JeuReference): { pseudo: string; motDePasse: string } | null {
  switch (role) {
    case 'master':
      return { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER };
    case 'benevole':
      return { pseudo: jeu.benevole, motDePasse: MOT_DE_PASSE_BENEVOLE_CREE };
    case 'coureur':
      return { pseudo: jeu.coureur, motDePasse: MOT_DE_PASSE };
    default:
      return null;
  }
}

/** Ouvre une session API dans le contexte du navigateur (cookies partagés avec la page). */
export async function ouvrirSession(page: Page, role: Role, jeu: JeuReference): Promise<void> {
  const ident = identifiantsDe(role, jeu);
  if (ident) await connecterParApi(page.request, ident.pseudo, ident.motDePasse);
}

/** Ouvre l'écran (session du rôle, navigation, attente de l'écran chargé, injection des aides de mesure). */
export async function ouvrirScene(page: Page, scene: Scene, jeu: JeuReference): Promise<(() => Promise<void>) | void> {
  await ouvrirSession(page, scene.role, jeu);
  await page.goto(cheminDe(scene, jeu));
  await scene.pret(page, jeu);
  const nettoyage = scene.etat ? await scene.etat(page, jeu) : undefined;
  await page.evaluate(AIDES_PAGE);
  return nettoyage;
}

/* ------------------------------------------------------------------ mesures dans la page */

/** Définit `window.__t` (mesures de couleur, contraste, taille). Exécuté par `page.evaluate` (non soumis à la CSP). */
export const AIDES_PAGE = `
window.__t = (() => {
  const TRAIT = { r: 217, g: 207, b: 182, a: 1 };
  const parse = (s) => {
    const m = s.match(/rgba?\\(([^)]+)\\)/);
    if (!m) return null;
    const p = m[1].split(/[ ,\\/]+/).filter(Boolean).map(Number);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  };
  const lum = (c) => {
    const f = (v) => { const s = v / 255; return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
  };
  const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
  const sur = (dessus, dessous) => ({
    r: dessus.r * dessus.a + dessous.r * (1 - dessus.a),
    g: dessus.g * dessus.a + dessous.g * (1 - dessus.a),
    b: dessus.b * dessus.a + dessous.b * (1 - dessus.a),
    a: 1,
  });
  const fondEffectif = (el) => {
    const couches = [];
    let n = el;
    let opaque = false;
    while (n && n.nodeType === 1) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c && c.a > 0) { couches.push(c); if (c.a >= 1) { opaque = true; break; } }
      n = n.parentElement;
    }
    let fond = opaque ? couches.pop() : TRAIT;
    while (couches.length) fond = sur(couches.pop(), fond);
    return fond;
  };
  const hex = (c) => '#' + [c.r, c.g, c.b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
  const desc = (el) => {
    const t = el.closest('[data-testid]');
    return (el.getAttribute('data-testid') || (t ? t.getAttribute('data-testid') + ' > ' : '') ) + '<' + el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\\s+/).join('.') : '') + '>';
  };
  const dansQr = (el) => !!el.closest('[data-testid="inscriptions-qr"]');
  const visible = (el) => {
    if (['HEAD','META','LINK','SCRIPT','STYLE','TITLE','TEMPLATE','NOSCRIPT'].includes(el.tagName)) return false;
    if (!el.checkVisibility()) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  const aTexte = (el) => Array.from(el.childNodes).some((n) => n.nodeType === 3 && n.textContent.trim().length > 0);
  const estChamp = (el) => {
    if (!['INPUT','SELECT','TEXTAREA'].includes(el.tagName)) return false;
    return !['checkbox','radio','hidden','file'].includes((el.getAttribute('type') || 'text'));
  };
  const interactifs = () => Array.from(document.querySelectorAll('a[href], button, input, select, textarea'))
    .filter((el) => visible(el) && !el.disabled && el.getAttribute('type') !== 'hidden' && el.tabIndex >= 0 && el.getAttribute('aria-hidden') !== 'true');

  return {
    visible, desc, hex, interactifs,
    /** Couleurs calculées hors palette (CA5). */
    horsPalette(autorisees) {
      const ok = new Set(autorisees);
      const props = ['color','backgroundColor','borderTopColor','borderRightColor','borderBottomColor','borderLeftColor','outlineColor'];
      const ecarts = [];
      const vues = new Set();
      for (const el of document.querySelectorAll('*')) {
        if (el !== document.documentElement && !visible(el)) continue;
        if (dansQr(el)) continue;
        const cibles = [[el, null]];
        for (const pseudo of ['::before', '::after']) {
          if (getComputedStyle(el, pseudo).content !== 'none') cibles.push([el, pseudo]);
        }
        for (const [e, pseudo] of cibles) {
          const cs = getComputedStyle(e, pseudo);
          for (const p of props) {
            const v = cs[p];
            if (!ok.has(v)) {
              const cle = desc(e) + (pseudo || '') + '|' + p + '|' + v;
              if (!vues.has(cle)) { vues.add(cle); ecarts.push({ element: desc(e) + (pseudo || ''), propriete: p, valeur: v }); }
            }
          }
        }
      }
      return ecarts;
    },
    /** Ratios de contraste des textes visibles (CA6). */
    contrastes() {
      const mesures = [];
      for (const el of document.querySelectorAll('*')) {
        if (!visible(el) || dansQr(el)) continue;
        const texte = aTexte(el);
        const champ = estChamp(el);
        if (!texte && !champ) continue;
        if (el.closest(':disabled')) continue;
        const cs = getComputedStyle(el);
        const couleur = parse(cs.color);
        const fond = fondEffectif(el);
        const px = parseFloat(cs.fontSize);
        const gras = parseInt(cs.fontWeight, 10) >= 700;
        const grand = px >= 24 || (px >= 18.66 && gras);
        const seuil = grand ? 3 : 4.5;
        const premier = sur(couleur, fond);
        mesures.push({ element: desc(el), type: 'texte', couleur: hex(premier), fond: hex(fond), ratio: ratio(premier, fond), seuil });
        if (champ) {
          const bord = parse(cs.borderTopColor);
          const fondParent = fondEffectif(el.parentElement);
          mesures.push({ element: desc(el), type: 'bordure-champ', couleur: hex(bord), fond: hex(fondParent), ratio: ratio(bord, fondParent), seuil: 3 });
        }
      }
      return mesures;
    },
    /** Information sur l'élément qui a le focus : position dans l'ordre du DOM, contour, contraste. */
    etatFocus() {
      const el = document.activeElement;
      if (!el || el === document.body) return null;
      const liste = interactifs();
      const cs = getComputedStyle(el);
      const fondDuContour = fondEffectif(el.parentElement || el);
      const contour = parse(cs.outlineColor);
      return {
        index: liste.indexOf(el),
        total: liste.length,
        element: desc(el),
        outlineStyle: cs.outlineStyle,
        outlineWidth: cs.outlineWidth,
        outlineColor: cs.outlineColor,
        dansEntete: !!el.closest('.entete'),
        ratioContour: contour ? ratio(contour, fondDuContour) : 0,
      };
    },
    /** Mesures de responsive (CA15). */
    responsive() {
      const racine = document.documentElement;
      const cibles = [];
      for (const el of document.querySelectorAll('button, input, select, textarea, a.bouton, a.ligne__action')) {
        if (!visible(el)) continue;
        const type = el.getAttribute('type');
        if (['checkbox','radio','hidden'].includes(type || '')) continue;
        const h = el.getBoundingClientRect().height;
        if (h < 43.99) cibles.push({ element: desc(el), hauteur: h });
      }
      const petits = [];
      for (const el of document.querySelectorAll('*')) {
        if (!visible(el) || !aTexte(el) || dansQr(el)) continue;
        const px = parseFloat(getComputedStyle(el).fontSize);
        if (px < 12.99) petits.push({ element: desc(el), taille: px });
      }
      const cartes = Array.from(document.querySelectorAll('.page > .carte, .page > section.carte')).filter(visible)
        .map((el) => ({ element: desc(el), largeur: el.getBoundingClientRect().width, gauche: el.getBoundingClientRect().left }));
      return {
        scrollWidth: racine.scrollWidth,
        clientWidth: racine.clientWidth,
        cibles, petits, cartes,
      };
    },
  };
})();
`;

/** Types des mesures (miroir de `AIDES_PAGE`). */
export interface EcartCouleur { element: string; propriete: string; valeur: string }
export interface MesureContraste { element: string; type: string; couleur: string; fond: string; ratio: number; seuil: number }
export interface EtatFocus {
  index: number;
  total: number;
  element: string;
  outlineStyle: string;
  outlineWidth: string;
  outlineColor: string;
  dansEntete: boolean;
  ratioContour: number;
}
export interface MesuresResponsive {
  scrollWidth: number;
  clientWidth: number;
  cibles: { element: string; hauteur: number }[];
  petits: { element: string; taille: number }[];
  cartes: { element: string; largeur: number; gauche: number }[];
}

/** Parcourt la page au clavier (Tab depuis le haut de page) et renvoie l'état du focus à chaque arrêt. */
export async function parcourirAuTab(page: Page, maximum: number): Promise<EtatFocus[]> {
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  const arrets: EtatFocus[] = [];
  for (let i = 0; i < maximum; i++) {
    await page.keyboard.press('Tab');
    const etat = await page.evaluate(() => (window as unknown as { __t: { etatFocus(): unknown } }).__t.etatFocus());
    if (!etat) break;
    const courant = etat as EtatFocus;
    if (arrets.length > 0 && courant.index <= arrets[arrets.length - 1].index) break;
    arrets.push(courant);
  }
  return arrets;
}
