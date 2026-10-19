import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { MOTIF_API_SUIVI, compterRequetesSuivi, demarrer, lire, poserMarqueur, reponseSuivi, urlSuivi, verifierSansRechargement } from './aide-suivi';
import { preparerCourseDeScan } from './aide-scan';
import {
  CINQ_SECONDES,
  SECTION_ABANDONS,
  SECTION_EN_COURSE,
  abandonManuelParApi,
  courseDemarree,
  laisserPasser,
  reintegrerCoureurParApi,
  section,
  supprimerCompteCoureurParApi,
} from './aide-suivi-coureurs';

/*
 * Incrément 6.2 : listes des coureurs en course, des abandons et des vainqueurs sur le suivi public (CA15, CA16, CA20 à CA22,
 * CA25, CA26). CA17 à CA19, CA23 et CA24 (horloge pilotable) : suivi-coureurs.horloge.spec.ts.
 * Chaque test ouvre la page dans le contexte neuf de Playwright (aucune session).
 */

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA15 - un visiteur sans session voit les coureurs en course (dossard puis pseudo, par dossard) et « Aucun abandon. », sans le jeton QR', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright);
  const [alice, bruno] = course.coureurs;
  await page.goto(urlSuivi(course.id));

  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toContainText(`Dossard ${alice.dossard}`);
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toContainText(alice.pseudo);
  await expect(lire(page, 'suivi-coureur-' + bruno.dossard)).toContainText(bruno.pseudo);
  const lignes = section(page, SECTION_EN_COURSE).getByRole('listitem');
  await expect(lignes).toHaveCount(2);
  await expect(lignes.nth(0)).toHaveAttribute('data-testid', 'suivi-coureur-' + alice.dossard);
  await expect(lignes.nth(1)).toHaveAttribute('data-testid', 'suivi-coureur-' + bruno.dossard);
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('0');
  await expect(lire(page, 'suivi-message-aucun-abandon')).toHaveText('Aucun abandon.');
  await expect(lire(page, 'suivi-vainqueurs')).toHaveCount(0);

  // Aucun jeton QR (lu en base, référence indépendante de l'API) ni dans le texte ni dans les attributs.
  const dom = await page.content();
  for (const c of course.coureurs) expect(dom).not.toContain(c.jeton);
  expect(await page.locator('body').innerText()).not.toContain(alice.jeton);
});

test('CA16 - abandon manuel puis réintégration par l\'administration : la ligne change de section sans rechargement, sans annonce', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright);
  const [alice, bruno] = course.coureurs;
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');
  await poserMarqueur(page);

  await abandonManuelParApi(playwright, course, bruno, 'MANUEL');
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('1', CINQ_SECONDES);
  const ligne = lire(page, 'suivi-abandon-' + bruno.dossard);
  await expect(ligne).toContainText(`Dossard ${bruno.dossard}`);
  await expect(ligne).toContainText(bruno.pseudo);
  await expect(ligne).toContainText('Boucle 1');
  await expect(ligne).toContainText('Manuel');
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('1');
  await expect(lire(page, 'suivi-message-aucun-abandon')).toHaveCount(0);
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toBeVisible();
  await expect(lire(page, 'suivi-coureur-' + bruno.dossard)).toHaveCount(0);
  await expect(lire(page, 'suivi-annonce-fin')).toHaveText('');

  await reintegrerCoureurParApi(playwright, course, bruno);
  await expect(lire(page, 'suivi-coureur-' + bruno.dossard)).toBeVisible(CINQ_SECONDES);
  await expect(lire(page, 'suivi-abandon-' + bruno.dossard)).toHaveCount(0);
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('0');
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');
  await expect(lire(page, 'suivi-message-aucun-abandon')).toHaveText('Aucun abandon.');
  await expect(lire(page, 'suivi-annonce-fin')).toHaveText('');
  await verifierSansRechargement(page);
});

test('CA20 - l\'abandon manuel des deux derniers coureurs termine la course sans vainqueur', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright);
  const [alice, bruno] = course.coureurs;
  const requetes = compterRequetesSuivi(page);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');

  await abandonManuelParApi(playwright, course, alice, 'VOLONTAIRE');
  await expect(lire(page, 'suivi-abandon-' + alice.dossard)).toBeVisible(CINQ_SECONDES);
  await abandonManuelParApi(playwright, course, bruno, 'VOLONTAIRE');

  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée', CINQ_SECONDES);
  await expect(lire(page, 'suivi-message-aucun-vainqueur')).toHaveText('Course terminée sans vainqueur.');
  await expect(lire(page, 'suivi-vainqueurs')).toHaveCount(0);
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('2');
  await expect(lire(page, 'suivi-abandon-' + alice.dossard)).toContainText('Volontaire');
  await expect(lire(page, 'suivi-abandon-' + bruno.dossard)).toContainText('Volontaire');
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveCount(0);
  await expect(lire(page, 'suivi-annonce-fin')).toHaveText('Course terminée sans vainqueur.');

  const avant = requetes.nombre();
  await laisserPasser(page, 7000);
  expect(requetes.nombre()).toBe(avant);
});

test('CA21 - course non démarrée : aucune section ni pseudo ; au démarrage les coureurs apparaissent sans rechargement', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2, demarree: false });
  const [alice, bruno] = course.coureurs;
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-message-non-demarree')).toBeVisible();
  for (const id of ['suivi-nombre-en-course', 'suivi-nombre-abandons', 'suivi-vainqueurs', 'suivi-message-aucun-vainqueur']) {
    await expect(lire(page, id)).toHaveCount(0);
  }
  await expect(page.getByRole('heading', { level: 2 })).toHaveCount(0);
  const texte = await page.locator('body').innerText();
  expect(texte).not.toContain(alice.pseudo);
  expect(texte).not.toContain(bruno.pseudo);
  await poserMarqueur(page);

  await demarrer(playwright, course.id);
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toContainText(alice.pseudo, CINQ_SECONDES);
  await expect(lire(page, 'suivi-coureur-' + bruno.dossard)).toContainText(bruno.pseudo);
  await expect(lire(page, 'suivi-message-non-demarree')).toHaveCount(0);
  await verifierSansRechargement(page);
});

test('CA22 - la suppression du compte d\'un coureur est reflétée sans rechargement : « Coureur anonyme », même dossard', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright);
  const [alice, bruno] = course.coureurs;
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toContainText(alice.pseudo);
  await poserMarqueur(page);

  await supprimerCompteCoureurParApi(playwright, alice);
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toContainText('Coureur anonyme', CINQ_SECONDES);
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toContainText(`Dossard ${alice.dossard}`);
  expect(await page.locator('body').innerText()).not.toContain(alice.pseudo);
  await expect(lire(page, 'suivi-coureur-' + bruno.dossard)).toContainText(bruno.pseudo);
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');
  await verifierSansRechargement(page);
});

/* ---------- CA25 : charte, responsive, accessibilité ---------- */

const PSEUDO_LONG = 'A'.repeat(30);
const PSEUDO_HTML = '<b>x</b>';

function lignes(n: number, depart: number) {
  return Array.from({ length: n }, (_, i) => ({ dossard: depart + i, pseudo: `coureur-${depart + i}` }));
}

/** Réponse simulée : 40 coureurs en course (dont pseudo long et pseudo HTML) et 6 abandons ; le pseudo n'est jamais interprété. */
function reponseChargee(statut: 'EN_COURS' | 'TERMINEE'): string {
  const abandons = Array.from({ length: 6 }, (_, i) => ({
    dossard: 41 + i,
    pseudo: i === 0 ? PSEUDO_LONG : `abandon-${41 + i}`,
    boucleAbandon: 3 - Math.floor(i / 2),
    motifAbandon: ['HORS_DELAI', 'MANUEL', 'VOLONTAIRE', 'AUTRE', 'HORS_DELAI', 'HORS_DELAI'][i],
  }));
  if (statut === 'TERMINEE') {
    return reponseSuivi({
      statut,
      boucleCourante: null,
      derniereBoucle: null,
      boucleMaxTerminee: null,
      tempsAvantFinBoucleMillisecondes: null,
      dureeEcouleeMillisecondes: null,
      coureursEnCourse: [],
      vainqueurs: [{ dossard: 1, pseudo: PSEUDO_LONG }],
      abandons,
    });
  }
  const coureurs = lignes(40, 1);
  coureurs[0] = { dossard: 1, pseudo: PSEUDO_LONG };
  coureurs[1] = { dossard: 2, pseudo: PSEUDO_HTML };
  return reponseSuivi({ coureursEnCourse: coureurs, abandons, vainqueurs: [] });
}

async function defilementHorizontal(page: Page): Promise<number> {
  return page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
}

function rgb(couleur: string): number[] {
  const m = /rgba?\(([^)]+)\)/.exec(couleur);
  return (m ? m[1] : '0,0,0').split(/[ ,/]+/).filter(Boolean).map(Number);
}

function luminance([r, g, b]: number[]): number {
  const c = [r, g, b].map((x) => {
    const s = x / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

const ratio = (a: string, b: string) => {
  const l1 = luminance(rgb(a));
  const l2 = luminance(rgb(b));
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
};

test('CA25 - listes responsives et accessibles : 40 lignes, 6 abandons, pseudo long coupé, HTML littéral, focus conservé, aucun défilement horizontal', async ({ page }) => {
  await page.route(MOTIF_API_SUIVI, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: reponseChargee('EN_COURS') }));
  const requetes = compterRequetesSuivi(page);
  const profils = [
    { nom: '1280 px', largeur: 1280, police: null },
    { nom: '375 px', largeur: 375, police: null },
    { nom: '320 px', largeur: 320, police: null },
    { nom: '320 px texte 200 %', largeur: 320, police: '32px' },
  ];
  for (const p of profils) {
    await page.setViewportSize({ width: p.largeur, height: 800 });
    await page.goto(urlSuivi('00000000-0000-0000-0000-000000000001'));
    if (p.police) await page.addStyleTag({ content: `html{font-size:${p.police}}` });
    await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('40');
    await expect(section(page, SECTION_EN_COURSE).getByRole('listitem')).toHaveCount(40);
    await expect(section(page, SECTION_ABANDONS).getByRole('listitem')).toHaveCount(6);
    await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('6');
    expect(await defilementHorizontal(page), `défilement horizontal à ${p.nom}`).toBeLessThanOrEqual(0);
    // Pseudo long : lisible (texte intégral présent) et dans la carte
    await expect(lire(page, 'suivi-coureur-1')).toContainText(PSEUDO_LONG);
    const debord = await page.evaluate(() => {
      const ligne = document.querySelector('[data-testid="suivi-coureur-1"] .ligne__pseudo') as HTMLElement;
      const carte = document.querySelector('main section') as HTMLElement;
      return ligne.getBoundingClientRect().right - carte.getBoundingClientRect().right;
    });
    expect(debord, `pseudo long débordant à ${p.nom}`).toBeLessThanOrEqual(1);
  }

  // HTML littéral, jamais interprété
  await expect(lire(page, 'suivi-coureur-2')).toContainText(PSEUDO_HTML);
  await expect(page.locator('main b')).toHaveCount(0);

  // Structure : sections en h2, listes en ul
  await expect(page.getByRole('heading', { level: 2 })).toHaveCount(2);
  await expect(page.getByRole('list')).toHaveCount(2);
  expect(await page.locator('main ul').count()).toBe(2);
  expect(await page.locator('main [aria-live]').count()).toBe(0);

  // Le focus clavier posé sur l'en-tête survit à 2 rafraîchissements
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto(urlSuivi('00000000-0000-0000-0000-000000000001'));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('40');
  await lire(page, 'entete-titre').focus();
  await expect(lire(page, 'entete-titre')).toBeFocused();
  const avant = requetes.nombre();
  await expect.poll(() => requetes.nombre() - avant, { timeout: 9000 }).toBeGreaterThanOrEqual(2);
  await expect(lire(page, 'entete-titre')).toBeFocused();
  await expect(section(page, SECTION_EN_COURSE).getByRole('listitem')).toHaveCount(40);

  // Contrastes AA des textes de lignes, aucune animation avec prefers-reduced-motion
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const mesures = await page.evaluate(() => {
    const fond = (el: Element): string => {
      let e: Element | null = el;
      while (e) {
        const c = getComputedStyle(e).backgroundColor;
        if (c && c !== 'rgba(0, 0, 0, 0)' && c !== 'transparent') return c;
        e = e.parentElement;
      }
      return 'rgb(255, 255, 255)';
    };
    const texte = ['suivi-coureur-1', 'suivi-abandon-41'].flatMap((id) =>
      [...document.querySelectorAll(`[data-testid="${id}"] span`)].map((e) => ({ couleur: getComputedStyle(e).color, fond: fond(e), id })),
    );
    const animations = [...document.querySelectorAll('main, main *')]
      .map((e) => getComputedStyle(e))
      .filter((s) => s.animationName !== 'none' && parseFloat(s.animationDuration) > 0.001).length;
    return { texte, animations };
  });
  expect(mesures.texte.length).toBeGreaterThan(0);
  for (const t of mesures.texte) expect(ratio(t.couleur, t.fond), `contraste ${t.id} ${t.couleur} sur ${t.fond}`).toBeGreaterThanOrEqual(4.5);
  expect(mesures.animations).toBe(0);
});

test('CA25 - bandeau vainqueur d\'une course terminée : mot « Vainqueur » écrit, forêt/crème par jetons, filet orange sans texte orange, charte sans valeur en dur', async ({ page }) => {
  await page.route(MOTIF_API_SUIVI, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: reponseChargee('TERMINEE') }));
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto(urlSuivi('00000000-0000-0000-0000-000000000001'));
  const bandeau = lire(page, 'suivi-vainqueurs');
  await expect(bandeau).toBeVisible();
  await expect(bandeau.getByRole('heading', { level: 2 })).toHaveText('Vainqueur');
  await expect(lire(page, 'suivi-vainqueur-1')).toContainText('Dossard 1');
  await expect(lire(page, 'suivi-vainqueur-1')).toContainText(PSEUDO_LONG);
  await expect(page.getByRole('region', { name: SECTION_EN_COURSE })).toHaveCount(0);
  expect(await defilementHorizontal(page)).toBeLessThanOrEqual(0);

  const m = await page.evaluate(() => {
    const jeton = (propriete: string, nom: string) => {
      const sonde = document.createElement('span');
      sonde.style.setProperty(propriete, `var(${nom})`);
      document.body.appendChild(sonde);
      const v = getComputedStyle(sonde).getPropertyValue(propriete) || (getComputedStyle(sonde) as unknown as Record<string, string>)[propriete];
      sonde.remove();
      return v;
    };
    const b = document.querySelector('[data-testid="suivi-vainqueurs"]') as HTMLElement;
    const s = getComputedStyle(b);
    const textes = [...b.querySelectorAll('*')].map((e) => getComputedStyle(e).color);
    return {
      fond: s.backgroundColor,
      couleur: s.color,
      filet: s.borderTopColor,
      textes,
      foret: jeton('background-color', '--couleur-foret'),
      creme: jeton('color', '--couleur-creme'),
      orange: jeton('background-color', '--couleur-orange'),
    };
  });
  expect(m.fond).toBe(m.foret);
  expect(m.couleur).toBe(m.creme);
  expect(m.filet).toBe(m.orange);
  expect(m.textes).not.toContain(m.orange);
  expect(ratio(m.couleur, m.fond)).toBeGreaterThanOrEqual(4.5);

  // Aucune couleur ni taille en dur, aucun style en ligne dans les styles et gabarits ajoutés
  const racine = join(__dirname, '..', '..', 'frontend', 'src', 'app', 'suivi', 'coureurs-suivi');
  const css = readFileSync(join(racine, 'coureurs-suivi.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  expect(css, 'couleur en dur').not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/);
  expect(css, 'taille de police en dur').not.toMatch(/font-size:\s*\d+(\.\d+)?(px|rem|em)/);
  expect(css, 'animation ou transition').not.toMatch(/animation|transition/);
  const html = readFileSync(join(racine, 'coureurs-suivi.html'), 'utf8');
  expect(html).not.toMatch(/style=|aria-live/);
});

/* ---------- CA26 : coupure réseau ---------- */

test('CA26 - coupure des requêtes de suivi : avertissement, listes inchangées ; après retour, l\'abandon survenu pendant la coupure apparaît', async ({ page, playwright }) => {
  const course = await courseDemarree(playwright);
  const [alice, bruno] = course.coureurs;
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');
  await expect(lire(page, 'suivi-avertissement-actualisation')).toHaveCount(0);

  await page.route(MOTIF_API_SUIVI, (route) => route.abort());
  await expect(lire(page, 'suivi-avertissement-actualisation')).toBeVisible(CINQ_SECONDES);
  await abandonManuelParApi(playwright, course, bruno, 'AUTRE');
  await laisserPasser(page, 8000);
  await expect(lire(page, 'suivi-avertissement-actualisation')).toBeVisible();
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('2');
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('0');
  await expect(lire(page, 'suivi-coureur-' + alice.dossard)).toContainText(alice.pseudo);
  await expect(lire(page, 'suivi-coureur-' + bruno.dossard)).toContainText(bruno.pseudo);
  await expect(lire(page, 'suivi-message-aucun-abandon')).toBeVisible();

  await page.unroute(MOTIF_API_SUIVI);
  await expect(lire(page, 'suivi-avertissement-actualisation')).toHaveCount(0, { timeout: 4000 });
  await expect(lire(page, 'suivi-abandon-' + bruno.dossard)).toContainText('Autre', { timeout: 4000 });
  await expect(lire(page, 'suivi-nombre-abandons')).toHaveText('1');
  await expect(lire(page, 'suivi-nombre-en-course')).toHaveText('1');
});
