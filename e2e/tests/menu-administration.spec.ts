import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_ADMIN_MASTER,
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
  courseDeReference,
  dateDansJours,
  demarrerCourseParApi,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { sinscrireParApi } from './aide-coureur';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const ORANGE = 'rgb(242, 140, 40)'; // --couleur-orange #F28C28
const ORANGE_FONCE = 'rgb(168, 67, 0)'; // --couleur-orange-fonce #A84300
const TRANSPARENT = 'rgba(0, 0, 0, 0)';

test.beforeAll(() => exigerIdentifiantsAdminMaster());

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

async function ouvrirSession(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}

const connecterPatron = (page: Page) => ouvrirSession(page, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);

async function connecterNouvelAdmin(page: Page, playwright: PlaywrightLib): Promise<void> {
  const pseudo = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, pseudo));
  await ouvrirSession(page, pseudo, MOT_DE_PASSE_ADMIN_CREE);
}

const menu = (page: Page) => page.getByTestId('menu-administration');
const entree = (page: Page, nom: 'courses' | 'admins' | 'benevoles') => page.getByTestId(`menu-administration-${nom}`);
const COURANTE = /menu-administration__entree--courante/;

async function attendreSansEntreeCourante(page: Page): Promise<void> {
  for (const nom of ['courses', 'admins', 'benevoles'] as const) {
    await expect(entree(page, nom)).not.toHaveClass(COURANTE);
    await expect(entree(page, nom)).not.toHaveAttribute('aria-current', /.*/);
  }
}

async function verifierCourante(page: Page, courante: 'courses' | 'admins' | 'benevoles', ariaCurrent: 'page' | 'true'): Promise<void> {
  for (const nom of ['courses', 'admins', 'benevoles'] as const) {
    const lien = entree(page, nom);
    if (nom === courante) {
      await expect(lien).toHaveClass(COURANTE);
      await expect(lien).toHaveAttribute('aria-current', ariaCurrent);
      await expect(lien).toHaveCSS('font-weight', '700');
      await expect(lien).toHaveCSS('border-bottom-color', ORANGE_FONCE);
      await expect(lien).toHaveCSS('border-bottom-width', '4px');
      await expect(lien).not.toHaveCSS('border-bottom-color', ORANGE);
    } else {
      await expect(lien).not.toHaveClass(COURANTE);
      await expect(lien).not.toHaveAttribute('aria-current', /.*/);
      await expect(lien).toHaveCSS('font-weight', '600');
      await expect(lien).toHaveCSS('border-bottom-color', TRANSPARENT);
    }
  }
}

async function libellesDuMenu(page: Page): Promise<string[]> {
  return (await menu(page).getByRole('link').allInnerTexts()).map((t) => t.trim());
}

test.describe('Menu d\'administration', () => {
  test('CA1 - l\'admin master voit les trois entrées du menu, dans l\'ordre, avec leurs liens et une liste', async ({ page }) => {
    await connecterPatron(page);
    await page.goto('/administration');
    await expect(page.getByTestId('titre-administration')).toBeVisible();

    await expect(page.getByRole('navigation', { name: 'Menu d\'administration' })).toHaveCount(1);
    await expect(menu(page)).toHaveAttribute('aria-label', 'Menu d\'administration');
    await expect(menu(page).getByRole('link')).toHaveCount(3);
    expect(await libellesDuMenu(page)).toEqual(['Courses', 'Admins', 'Bénévoles']);
    await expect(menu(page).getByRole('link').nth(0)).toHaveAttribute('data-testid', 'menu-administration-courses');
    await expect(menu(page).getByRole('link').nth(1)).toHaveAttribute('data-testid', 'menu-administration-admins');
    await expect(menu(page).getByRole('link').nth(2)).toHaveAttribute('data-testid', 'menu-administration-benevoles');
    await expect(entree(page, 'courses')).toHaveAttribute('href', '/administration/courses');
    await expect(entree(page, 'admins')).toHaveAttribute('href', '/administration/admins');
    await expect(entree(page, 'benevoles')).toHaveAttribute('href', '/administration/benevoles');
    await expect(menu(page).getByRole('list')).toHaveCount(1);
    await expect(menu(page).getByRole('listitem')).toHaveCount(3);
  });

  test('CA2 - l\'admin ne voit que Courses et Bénévoles, sans entrée ni carte Admins', async ({ page, playwright }) => {
    await connecterNouvelAdmin(page, playwright);

    for (const chemin of ['/administration', '/administration/courses', '/administration/benevoles']) {
      await page.goto(chemin);
      await expect(menu(page)).toBeVisible();
      await expect(menu(page).getByRole('link')).toHaveCount(2);
      expect(await libellesDuMenu(page)).toEqual(['Courses', 'Bénévoles']);
      await expect(page.getByTestId('menu-administration-admins')).toHaveCount(0);
      await expect(page.getByTestId('lien-gestion-admins')).toHaveCount(0);
    }
  });

  test('CA3 - la navigation entre Courses, Admins et Bénévoles se fait sans rechargement et met en évidence l\'entrée courante', async ({ page }) => {
    await connecterPatron(page);
    await page.goto('/administration/courses');
    await expect(page.getByTestId('titre-courses')).toBeVisible();
    await verifierCourante(page, 'courses', 'page');
    await page.evaluate(() => ((window as unknown as Record<string, string>)['marqueurE2E'] = 'present'));

    // Contraste du filet (orange foncé) contre la surface : >= 3 (référence 5,91).
    const ratio = await entree(page, 'courses').evaluate((el) => {
      const lum = (c: string) => {
        const [r, g, b] = c.match(/\d+(\.\d+)?/g)!.slice(0, 3).map(Number).map((v) => {
          const x = v / 255;
          return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
      };
      const surface = getComputedStyle(document.documentElement).getPropertyValue('--couleur-surface').trim();
      const sonde = document.createElement('span');
      sonde.style.color = surface;
      document.body.appendChild(sonde);
      const fond = getComputedStyle(sonde).color;
      sonde.remove();
      const a = lum(getComputedStyle(el).borderBottomColor);
      const b = lum(fond);
      return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    });
    expect(ratio).toBeGreaterThanOrEqual(3);

    const marqueur = () => page.evaluate(() => (window as unknown as Record<string, string>)['marqueurE2E']);

    await entree(page, 'admins').click();
    await expect(page).toHaveURL(/\/administration\/admins$/);
    await expect(page.getByTestId('titre-admins')).toBeVisible();
    await verifierCourante(page, 'admins', 'page');
    expect(await marqueur()).toBe('present');

    await entree(page, 'benevoles').click();
    await expect(page).toHaveURL(/\/administration\/benevoles$/);
    await expect(page.getByTestId('titre-benevoles')).toBeVisible();
    await verifierCourante(page, 'benevoles', 'page');
    expect(await marqueur()).toBe('present');

    await entree(page, 'courses').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await expect(page.getByTestId('titre-courses')).toBeVisible();
    await verifierCourante(page, 'courses', 'page');
    expect(await marqueur()).toBe('present');

    // Retour arrière : la mise en évidence suit l'URL réelle.
    await page.goBack();
    await expect(page).toHaveURL(/\/administration\/benevoles$/);
    await verifierCourante(page, 'benevoles', 'page');
  });

  test('CA4 - la fiche et le pilotage d\'une course mettent Courses en évidence et les retours fonctionnent', async ({ page, playwright }) => {
    const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ date: dateDansJours(0) })));
    const id = course['id'] as string;
    const coureur = pseudoUnique();
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, coureur));
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, coureur, id));
    await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, id));
    await connecterPatron(page);

    await page.goto(`/administration/courses/${id}`);
    await expect(menu(page)).toBeVisible();
    await verifierCourante(page, 'courses', 'true');

    await page.goto(`/administration/courses/${id}/pilotage`);
    await expect(page.getByTestId('pilotage-titre')).toBeVisible();
    await verifierCourante(page, 'courses', 'true');

    await page.getByTestId('pilotage-retour').click();
    await expect(page).toHaveURL(new RegExp(`/administration/courses/${id}$`));
    await expect(menu(page)).toBeVisible();
    await page.getByTestId('fiche-lien-pilotage').click();
    await expect(page).toHaveURL(new RegExp(`/administration/courses/${id}/pilotage$`));
    await expect(page.getByTestId('pilotage-titre')).toBeVisible();

    await entree(page, 'benevoles').click();
    await expect(page).toHaveURL(/\/administration\/benevoles$/);
    await expect(page.getByTestId('titre-benevoles')).toBeVisible();
    await verifierCourante(page, 'benevoles', 'page');

    await page.goto(`/administration/courses/${id}`);
    await page.getByTestId('fiche-lien-retour').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await expect(page.getByTestId('titre-courses')).toBeVisible();
  });

  test('CA5 - l\'accueil Administration porte le menu avant le titre, sans entrée courante, et garde ses cartes', async ({ page, playwright }) => {
    await connecterPatron(page);
    await page.goto('/administration');
    await expect(page.getByTestId('titre-administration')).toBeVisible();
    await expect(menu(page)).toBeVisible();
    await attendreSansEntreeCourante(page);
    await expect(menu(page).locator('.menu-administration__entree--courante')).toHaveCount(0);
    await expect(menu(page).locator('[aria-current]')).toHaveCount(0);

    const nav_avant_titre = await page.evaluate(() => {
      const nav = document.querySelector('[data-testid="menu-administration"]')!;
      const h1 = document.querySelector('[data-testid="titre-administration"]')!;
      return Boolean(nav.compareDocumentPosition(h1) & Node.DOCUMENT_POSITION_FOLLOWING);
    });
    expect(nav_avant_titre).toBe(true);

    await expect(page.getByTestId('lien-gestion-courses')).toBeVisible();
    await expect(page.getByTestId('lien-gestion-benevoles')).toBeVisible();
    await expect(page.getByTestId('lien-gestion-admins')).toBeVisible();
    await page.getByTestId('lien-gestion-courses').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await page.goto('/administration');
    await page.getByTestId('lien-gestion-benevoles').click();
    await expect(page).toHaveURL(/\/administration\/benevoles$/);
    await page.goto('/administration');
    await page.getByTestId('lien-gestion-admins').click();
    await expect(page).toHaveURL(/\/administration\/admins$/);

    // Erreur de vérification d'accès : le menu reste, aucune carte.
    await page.route('**/api/administration/acces', (route) => route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{}' }));
    await page.goto('/administration');
    await expect(page.getByTestId('administration-erreur')).toBeVisible();
    await expect(menu(page)).toBeVisible();
    await expect(page.locator('ul.grille-cartes--navigation li')).toHaveCount(0);
    await page.unroute('**/api/administration/acces');

    // Admin : deux cartes seulement.
    const pageAdmin = await page.context().browser()!.newContext();
    const p = await pageAdmin.newPage();
    try {
      await connecterNouvelAdmin(p, playwright);
      await p.goto('/administration');
      await expect(p.getByTestId('titre-administration')).toBeVisible();
      await expect(p.getByTestId('lien-gestion-courses')).toBeVisible();
      await expect(p.getByTestId('lien-gestion-benevoles')).toBeVisible();
      await expect(p.getByTestId('lien-gestion-admins')).toHaveCount(0);
    } finally {
      await pageAdmin.close();
    }
  });

  test('CA6 - le menu se parcourt au clavier, dans l\'ordre, avant le contenu, avec un contour visible et sans piège', async ({ page }) => {
    await connecterPatron(page);
    await page.goto('/administration/benevoles');
    await expect(page.getByTestId('titre-benevoles')).toBeVisible();

    await page.getByTestId('entete-titre').focus();
    await page.keyboard.press('Tab');
    await expect(page.getByTestId('bouton-menu')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(entree(page, 'courses')).toBeFocused();

    const contour = async (l: Locator) =>
      l.evaluate((el) => {
        const s = getComputedStyle(el);
        return { style: s.outlineStyle, largeur: parseFloat(s.outlineWidth) };
      });
    const c1 = await contour(entree(page, 'courses'));
    expect(c1.style).not.toBe('none');
    expect(c1.largeur).toBeGreaterThanOrEqual(3);

    await page.keyboard.press('Tab');
    await expect(entree(page, 'admins')).toBeFocused();
    const c2 = await contour(entree(page, 'admins'));
    expect(c2.style).not.toBe('none');
    expect(c2.largeur).toBeGreaterThanOrEqual(3);

    await page.keyboard.press('Tab');
    await expect(entree(page, 'benevoles')).toBeFocused();
    await expect(page.getByTestId('lien-retour-administration')).not.toBeFocused();

    // Maj+Tab revient en arrière.
    await page.keyboard.press('Shift+Tab');
    await expect(entree(page, 'admins')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(entree(page, 'benevoles')).toBeFocused();

    // Échap n'a aucun effet.
    await page.keyboard.press('Escape');
    await expect(entree(page, 'benevoles')).toBeFocused();
    await expect(page).toHaveURL(/\/administration\/benevoles$/);

    // Pas de piège : Tab sort du menu vers le contenu.
    await page.keyboard.press('Tab');
    await expect(menu(page).locator(':focus')).toHaveCount(0);
    await expect(page.locator(':focus')).toHaveCount(1);

    // Entrée sur « Admins » navigue.
    await entree(page, 'admins').focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/administration\/admins$/);
    await expect(page.getByTestId('titre-admins')).toBeVisible();
  });

  for (const viewport of [
    { width: 320, height: 640 },
    { width: 360, height: 740 },
  ]) {
    test(`CA7 - sur téléphone (${viewport.width} px) les entrées sont visibles, tactiles, non tronquées et sans défilement horizontal`, async ({ browser }) => {
      const contexte = await browser.newContext({ viewport, hasTouch: true, baseURL: BASE_URL, serviceWorkers: 'block' });
      const page = await contexte.newPage();
      try {
        await connecterPatron(page);
        for (const chemin of ['/administration/courses', '/administration/admins', '/administration/benevoles']) {
          await page.goto(chemin);
          await expect(menu(page)).toBeVisible();
          for (const nom of ['courses', 'admins', 'benevoles'] as const) {
            const lien = entree(page, nom);
            await expect(lien).toBeVisible();
            const mesure = await lien.evaluate((el) => ({
              hauteur: el.getBoundingClientRect().height,
              scrollWidth: el.scrollWidth,
              clientWidth: el.clientWidth,
            }));
            expect(mesure.hauteur).toBeGreaterThanOrEqual(44);
            expect(mesure.scrollWidth).toBeLessThanOrEqual(mesure.clientWidth);
          }
          const defilement = await page.evaluate(() => document.documentElement.scrollWidth);
          expect(defilement).toBeLessThanOrEqual(viewport.width);
        }

        await page.goto('/administration/courses');
        await entree(page, 'benevoles').tap();
        await expect(page).toHaveURL(/\/administration\/benevoles$/);
        await expect(page.getByTestId('titre-benevoles')).toBeVisible();
      } finally {
        await contexte.close();
      }
    });
  }

  test('CA8 - l\'admin, le bénévole, le coureur et l\'anonyme n\'accèdent pas aux pages interdites et ne voient jamais le menu', async ({ page, browser, playwright }) => {
    // Admin : /administration/admins refusé, API 403 ACCES_REFUSE.
    await connecterNouvelAdmin(page, playwright);
    await page.goto('/administration/admins');
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(page.getByTestId('titre-acces-refuse')).toBeVisible();
    await expect(page.getByTestId('menu-administration')).toHaveCount(0);
    const reponse = await page.request.get('/api/administration/admins');
    expect(reponse.status()).toBe(403);
    expect(JSON.stringify(await reponse.json())).toContain('ACCES_REFUSE');

    // Bénévole et coureur.
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    const coureur = pseudoUnique();
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, coureur));
    for (const [pseudo, motDePasse] of [
      [benevole, MOT_DE_PASSE_BENEVOLE_CREE],
      [coureur, MOT_DE_PASSE],
    ]) {
      const contexte = await browser.newContext({ baseURL: BASE_URL, serviceWorkers: 'block' });
      const p = await contexte.newPage();
      try {
        await ouvrirSession(p, pseudo, motDePasse);
        for (const chemin of ['/administration/courses', '/administration/benevoles']) {
          await p.goto(chemin);
          await expect(p).toHaveURL(/\/acces-refuse$/);
          await expect(p.getByTestId('menu-administration')).toHaveCount(0);
        }
      } finally {
        await contexte.close();
      }
    }

    // Anonyme.
    const anonyme = await browser.newContext({ baseURL: BASE_URL, serviceWorkers: 'block' });
    const pa = await anonyme.newPage();
    try {
      await pa.goto('/administration/courses');
      await expect(pa).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fcourses$/);
      await expect(pa.getByTestId('titre-connexion')).toBeVisible();
      await expect(pa.getByTestId('menu-administration')).toHaveCount(0);
    } finally {
      await anonyme.close();
    }
  });

  test('CA9 - le menu n\'émet que des requêtes GET et aucune requête en revenant sur la page affichée', async ({ page }) => {
    await connecterPatron(page);
    await page.goto('/administration');
    await expect(menu(page)).toBeVisible();

    const requetes: Array<{ methode: string; url: string }> = [];
    page.on('request', (r) => requetes.push({ methode: r.method(), url: r.url() }));

    for (const [nom, titre, url] of [
      ['courses', 'titre-courses', /\/administration\/courses$/],
      ['admins', 'titre-admins', /\/administration\/admins$/],
      ['benevoles', 'titre-benevoles', /\/administration\/benevoles$/],
    ] as const) {
      await entree(page, nom).click();
      await expect(page).toHaveURL(url);
      await expect(page.getByTestId(titre)).toBeVisible();
    }

    expect(requetes.length).toBeGreaterThan(0);
    expect(requetes.filter((r) => r.methode !== 'GET')).toEqual([]);

    // Clic sur la page déjà affichée : ni requête ni changement d'URL.
    await expect(page.getByTestId('titre-benevoles')).toBeVisible();
    await page.evaluate(() => new Promise<void>((ok) => requestAnimationFrame(() => requestAnimationFrame(() => ok()))));
    const avant = requetes.length;
    await entree(page, 'benevoles').click();
    await page.evaluate(() => new Promise<void>((ok) => requestAnimationFrame(() => requestAnimationFrame(() => ok()))));
    await expect(page).toHaveURL(/\/administration\/benevoles$/);
    await expect(page.getByTestId('titre-benevoles')).toBeVisible();
    expect(requetes.length).toBe(avant);
  });

  test('CA11 - l\'écran dossard n\'affiche pas le menu d\'administration', async ({ page, playwright }) => {
    const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference()));
    const id = course['id'] as string;
    const pseudo = pseudoUnique();
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
    const inscription = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, id));

    await connecterPatron(page);
    await page.goto(`/administration/courses/${id}/inscriptions/${inscription.id}/dossard`);
    await expect(page.getByTestId('dossard-numero')).toHaveText(String(inscription.dossard));
    await expect(page.getByTestId('menu-administration')).toHaveCount(0);
    await page.getByTestId('dossard-lien-retour').click();
    await expect(page).toHaveURL(new RegExp(`/administration/courses/${id}$`));
    await expect(menu(page)).toBeVisible();
  });
});
