import { expect, test, type Page } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir, seConnecter } from './aide-connexion';
import { MOT_DE_PASSE_ADMIN_MASTER, PSEUDO_ADMIN_MASTER, exigerIdentifiantsAdminMaster } from './aide-admin';
import { ouvrirMenuCompte, ouvrirMenuVisiteur, seDeconnecterParLeMenu } from './aide-entete';
import { RGB_PALETTE, RGB_TRANSPARENT } from './aide-theme';

const CREME = 'rgb(247, 241, 227)';
const FORET = 'rgb(20, 53, 42)';
const FORET_SURVOL = 'rgb(31, 90, 67)';
const ORANGE = 'rgb(242, 140, 40)';

const PAGES_ANONYMES = [
  { chemin: '/', titre: 'titre' },
  { chemin: '/connexion', titre: 'titre-connexion' },
  { chemin: '/creer-compte', titre: 'titre-creer-compte' },
];

async function charger(page: Page, chemin: string, titre: string): Promise<void> {
  await page.goto(chemin);
  await expect(page.getByTestId(titre)).toBeVisible();
  await expect(page.getByTestId('bouton-menu')).toBeVisible();
}

const bouton = (page: Page) => page.getByTestId('bouton-menu');
const panneau = (page: Page) => page.getByTestId('menu-visiteur');

/** Élément qui a le focus : data-testid, ou balise si absent. */
async function focusActuel(page: Page): Promise<string> {
  return page.evaluate(() => {
    const e = document.activeElement;
    return e?.getAttribute('data-testid') ?? e?.tagName.toLowerCase() ?? 'aucun';
  });
}

async function rect(locator: ReturnType<Page['getByTestId']>) {
  return locator.evaluate((e) => {
    const r = e.getBoundingClientRect();
    return { gauche: r.left, droite: r.right, haut: r.top, bas: r.bottom, largeur: r.width, hauteur: r.height };
  });
}

function luminance(rgb: string): number {
  const [r, g, b] = (rgb.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number);
  const f = (v: number) => {
    const s = v / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function ratio(a: string, b: string): number {
  const x = luminance(a);
  const y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

test.describe('Menu burger du visiteur (R.2)', () => {
  for (const { chemin, titre } of PAGES_ANONYMES) {
    test(`CA1 - le bouton du menu est à droite de l'en-tête, accessible et menu fermé sur ${chemin}`, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 800 });
      await charger(page, chemin, titre);

      const largeur = await page.evaluate(() => document.documentElement.clientWidth);
      const b = await rect(bouton(page));
      const t = await rect(page.getByTestId('entete-titre'));
      const entete = await rect(page.locator('.entete'));
      expect(b.gauche + b.largeur / 2).toBeGreaterThan(t.gauche + t.largeur / 2);
      expect(Math.abs(b.droite - (largeur - 16))).toBeLessThanOrEqual(1);
      expect(b.haut).toBeGreaterThanOrEqual(entete.haut);
      expect(b.bas).toBeLessThanOrEqual(entete.bas);
      expect(b.largeur).toBeGreaterThanOrEqual(44);
      expect(b.hauteur).toBeGreaterThanOrEqual(44);

      expect(((await bouton(page).textContent()) ?? '').trim()).toBe('');
      await expect(bouton(page)).toHaveAttribute('aria-label', 'Menu du compte');
      await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
      await expect(bouton(page)).toHaveAttribute('aria-controls', 'entete-menu-panneau');
      await expect(bouton(page)).not.toHaveAttribute('aria-haspopup');
      await expect(bouton(page)).not.toHaveAttribute('role');

      const svg = bouton(page).locator('svg');
      await expect(svg).toHaveCount(1);
      await expect(svg).toHaveAttribute('aria-hidden', 'true');
      const s = await rect(svg);
      expect(Math.round(s.largeur)).toBe(24);
      expect(Math.round(s.hauteur)).toBe(24);

      await expect(panneau(page)).toHaveCount(1);
      await expect(panneau(page)).toHaveAttribute('id', 'entete-menu-panneau');
      await expect(panneau(page)).toHaveAttribute('aria-label', 'Menu visiteur');
      await expect(panneau(page)).toHaveAttribute('hidden', '');
      expect(await panneau(page).evaluate((e) => e.tagName.toLowerCase())).toBe('nav');
      await expect(panneau(page)).toBeHidden();

      await expect(page.getByTestId('lien-se-connecter')).toHaveCount(0);
      await expect(page.getByTestId('menu-lien-se-connecter')).toBeHidden();
    });
  }

  test('CA2 - le clic sur le bouton ouvre le panneau, avec focus sur la première entrée, et un second clic le referme', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await charger(page, '/', 'titre');

    await bouton(page).click();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(panneau(page)).toBeVisible();

    const liens = panneau(page).locator('ul > li > a');
    await expect(liens).toHaveCount(2);
    await expect(liens.nth(0)).toHaveText('Se connecter');
    await expect(liens.nth(0)).toHaveAttribute('href', '/connexion');
    await expect(liens.nth(1)).toHaveText('Créer un compte');
    await expect(liens.nth(1)).toHaveAttribute('href', '/creer-compte');
    await expect(page.getByTestId('menu-lien-se-connecter')).toBeFocused();

    const p = await rect(panneau(page));
    const b = await rect(bouton(page));
    const entete = await rect(page.locator('.entete'));
    expect(p.haut).toBeGreaterThanOrEqual(b.bas);
    expect(Math.abs(p.droite - entete.droite)).toBeLessThanOrEqual(1);
    for (let i = 0; i < 2; i++) {
      const l = await rect(liens.nth(i));
      expect(l.hauteur).toBeGreaterThanOrEqual(44);
      expect(Math.abs(l.largeur - p.largeur)).toBeLessThanOrEqual(1);
    }

    await bouton(page).click();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toHaveAttribute('hidden', '');
    await expect(panneau(page)).toBeHidden();
    await expect(bouton(page)).toBeFocused();
  });

  test('CA3 - choisir une entrée navigue, ferme le menu et marque l\'écran courant', async ({ page }) => {
    await charger(page, '/', 'titre');

    await ouvrirMenuVisiteur(page);
    await page.getByTestId('menu-lien-se-connecter').click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');

    await ouvrirMenuVisiteur(page);
    await page.getByTestId('menu-lien-creer-compte').click();
    await expect(page).toHaveURL(/\/creer-compte$/);
    await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');

    await page.goto('/connexion');
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await ouvrirMenuVisiteur(page);
    const connexion = page.getByTestId('menu-lien-se-connecter');
    const creation = page.getByTestId('menu-lien-creer-compte');
    await expect(connexion).toHaveAttribute('aria-current', 'page');
    await expect(connexion).toHaveCSS('border-left-width', '4px');
    await expect(connexion).toHaveCSS('border-left-style', 'solid');
    await expect(connexion).toHaveCSS('border-left-color', ORANGE);
    await expect(creation).not.toHaveAttribute('aria-current');

    await connexion.click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();
  });

  test('CA4 - Échap ferme le menu et rend le focus au bouton, sans effet menu fermé', async ({ page }) => {
    await charger(page, '/', 'titre');
    await ouvrirMenuVisiteur(page);
    await expect(page.getByTestId('menu-lien-se-connecter')).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();
    await expect(bouton(page)).toBeFocused();

    await page.goto('/connexion');
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await page.getByTestId('champ-pseudo').click();
    await expect(page.getByTestId('champ-pseudo')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('champ-pseudo')).toBeFocused();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
  });

  test('CA5 - un appui extérieur ferme le menu sans perdre le geste, un appui dans le panneau le laisse ouvert', async ({ page }) => {
    await charger(page, '/', 'titre');
    await ouvrirMenuVisiteur(page);
    await page.getByTestId('titre').click();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();

    await page.goto('/connexion');
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await ouvrirMenuVisiteur(page);
    await page.getByTestId('champ-pseudo').click();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByTestId('champ-pseudo')).toBeFocused();

    await ouvrirMenuVisiteur(page);
    await panneau(page).click({ position: { x: 100, y: 1 } });
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(panneau(page)).toBeVisible();

    await bouton(page).click();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await bouton(page).dblclick();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('bouton-menu')).toHaveCount(1);

    await bouton(page).click({ clickCount: 3 });
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(panneau(page)).toBeVisible();
  });

  test('CA6 - le menu se parcourt au clavier seul, sans piège de focus', async ({ page }) => {
    await charger(page, '/', 'titre');
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());

    await page.keyboard.press('Tab');
    await expect(page.getByTestId('entete-titre')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(bouton(page)).toBeFocused();
    await expect(bouton(page)).toHaveCSS('outline-style', 'solid');
    await expect(bouton(page)).toHaveCSS('outline-width', '3px');
    await expect(bouton(page)).toHaveCSS('outline-color', ORANGE);

    // Menu fermé : les entrées masquées sont sautées.
    await page.keyboard.press('Tab');
    await expect.poll(async () => (await focusActuel(page)).startsWith('menu-')).toBe(false);
    await expect.poll(() => focusActuel(page)).not.toBe('bouton-menu');
    await page.keyboard.press('Shift+Tab');
    await expect(bouton(page)).toBeFocused();

    await page.keyboard.press('Enter');
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('menu-lien-se-connecter')).toBeFocused();

    await page.keyboard.press('Tab');
    const creation = page.getByTestId('menu-lien-creer-compte');
    await expect(creation).toBeFocused();
    await expect(creation).toHaveCSS('outline-style', 'solid');
    await expect(creation).toHaveCSS('outline-width', '3px');
    await expect(creation).toHaveCSS('outline-color', ORANGE);
    await expect(creation).toHaveCSS('background-color', FORET_SURVOL);

    await page.keyboard.press('Tab');
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect.poll(() => focusActuel(page)).not.toMatch(/^(menu-lien|bouton-menu)/);
    await expect(panneau(page)).toBeHidden();

    // Ré-ouverture par Espace, Maj+Tab depuis la première entrée.
    await page.keyboard.press('Shift+Tab');
    await expect(bouton(page)).toBeFocused();
    await page.keyboard.press('Space');
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('menu-lien-se-connecter')).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(bouton(page)).toBeFocused();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');

    await page.keyboard.press('Shift+Tab');
    await expect(page.getByTestId('entete-titre')).toBeFocused();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');

    await page.keyboard.press('Tab');
    await expect(bouton(page)).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('menu-lien-se-connecter')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(creation).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/creer-compte$/);
    await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
  });

  test('CA7 - le retour arrière et le titre ferment le menu, le redimensionnement le conserve', async ({ page }) => {
    await charger(page, '/', 'titre');
    await ouvrirMenuVisiteur(page);
    await page.getByTestId('menu-lien-se-connecter').click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();

    await ouvrirMenuVisiteur(page);
    await page.goBack();
    await expect(page).toHaveURL(/\/$/);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();

    await page.goForward();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await ouvrirMenuVisiteur(page);
    await page.getByTestId('entete-titre').click();
    await expect(page).toHaveURL(/\/$/);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();

    await page.setViewportSize({ width: 1280, height: 800 });
    await ouvrirMenuVisiteur(page);
    await page.setViewportSize({ width: 360, height: 640 });
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(panneau(page)).toBeVisible();
    const p = await rect(panneau(page));
    expect(p.gauche).toBeGreaterThanOrEqual(0);
    expect(p.droite).toBeLessThanOrEqual(360);
  });

  test('CA8 - le visiteur connecté garde l\'en-tête d\'avant, sans menu, et retrouve un menu fermé après déconnexion', async ({ page, request }) => {
    exigerIdentifiantsAdminMaster();
    const contenu = () =>
      page.locator('.entete').evaluate((entete) =>
        Array.from(entete.querySelectorAll('[data-testid]')).map((e) => ({
          testid: e.getAttribute('data-testid'),
          texte: (e.textContent ?? '').trim().replace(/\s+/g, ' '),
        })),
      );

    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);
    await expect(page.getByTestId('menu-compte')).toHaveCount(1);
    await expect(panneau(page)).toHaveCount(0);
    expect(await contenu()).toEqual([
      { testid: 'entete-titre', texte: 'Backyard Ultra Tracker' },
      { testid: 'bouton-menu', texte: '' },
      { testid: 'menu-compte', texte: `Connecté en tant que ${pseudo} Espace coureur Mes inscriptions Mon compte Se déconnecter` },
      { testid: 'entete-pseudo', texte: pseudo },
      { testid: 'menu-lien-espace-coureur', texte: 'Espace coureur' },
      { testid: 'menu-lien-mes-inscriptions', texte: 'Mes inscriptions' },
      { testid: 'menu-lien-mon-compte', texte: 'Mon compte' },
      { testid: 'bouton-deconnexion', texte: 'Se déconnecter' },
    ]);

    await ouvrirMenuCompte(page);
    await page.getByTestId('menu-lien-mes-inscriptions').click();
    await expect(page).toHaveURL(/\/coureur\/inscriptions$/);
    await expect(page.getByTestId('menu-compte')).toHaveCount(1);
    await expect(panneau(page)).toHaveCount(0);
    expect((await contenu()).map((c) => c.testid)).toEqual([
      'entete-titre', 'bouton-menu', 'menu-compte', 'entete-pseudo', 'menu-lien-espace-coureur', 'menu-lien-mes-inscriptions', 'menu-lien-mon-compte', 'bouton-deconnexion',
    ]);

    await seDeconnecterParLeMenu(page);
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(bouton(page)).toBeVisible();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);

    // Administrateur master.
    await ouvrirConnexion(page);
    await saisir(page, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
    await page.getByTestId('bouton-connexion').click();
    await expect(page.getByTestId('entete-pseudo')).toHaveText(PSEUDO_ADMIN_MASTER);
    await expect(page.getByTestId('menu-compte')).toHaveCount(1);
    await expect(panneau(page)).toHaveCount(0);
    expect(await contenu()).toEqual([
      { testid: 'entete-titre', texte: 'Backyard Ultra Tracker' },
      { testid: 'bouton-menu', texte: '' },
      { testid: 'menu-compte', texte: `Connecté en tant que ${PSEUDO_ADMIN_MASTER} Administration Mon compte Se déconnecter` },
      { testid: 'entete-pseudo', texte: PSEUDO_ADMIN_MASTER },
      { testid: 'menu-lien-administration', texte: 'Administration' },
      { testid: 'menu-lien-mon-compte', texte: 'Mon compte' },
      { testid: 'bouton-deconnexion', texte: 'Se déconnecter' },
    ]);
    await seDeconnecterParLeMenu(page);
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(bouton(page)).toBeVisible();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
  });

  test('CA9 - état inconnu sans menu, connexion en cours menu ouvert, session expirée menu fermé', async ({ page, request, context }) => {
    // État INCONNU : réponse de /api/comptes/moi retardée de 3 s.
    await page.route('**/api/comptes/moi', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 3000));
      await route.continue();
    });
    await page.goto('/');
    await expect(page.getByTestId('entete-titre')).toBeVisible();
    await expect(bouton(page)).toHaveCount(0);
    await expect(page.getByTestId('lien-se-connecter')).toHaveCount(0);
    await expect(bouton(page)).toBeVisible({ timeout: 15000 });
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await page.unroute('**/api/comptes/moi');

    // Connexion lancée, menu ouvert avant la réponse.
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await page.route('**/api/connexion', async (route) => {
      if (route.request().method() === 'POST') {
        await new Promise((resolve) => setTimeout(resolve, 2000));
      }
      await route.continue();
    });
    await ouvrirConnexion(page);
    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await ouvrirMenuVisiteur(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
    await expect(page.getByTestId('menu-compte')).toHaveCount(1);
    await expect(panneau(page)).toHaveCount(0);
    await expect(page).toHaveURL(/\/$/);
    await page.unroute('**/api/connexion');

    // Session expirée : le cookie disparaît, une action protégée reçoit 401.
    await context.clearCookies({ name: 'JSESSIONID' });
    await ouvrirMenuCompte(page);
    await page.getByTestId('menu-lien-mes-inscriptions').click();
    await expect(bouton(page)).toBeVisible();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
  });

  const VIEWPORTS = [
    { largeur: 320, hauteur: 640 },
    { largeur: 360, hauteur: 640 },
    { largeur: 1280, hauteur: 800 },
  ];

  async function verifierGeometrie(page: Page, largeur: number, ouvert: boolean, contexte = '', zoom = false): Promise<void> {
    if (zoom) {
      // À 200 % de texte : mesure limitée à l'en-tête (R.2, point ouvert 13), jamais à la page entière.
      const entete = await page.locator('.entete').evaluate((e) => ({ scroll: e.scrollWidth, client: e.clientWidth }));
      expect(entete.scroll, `débordement de l'en-tête ${contexte}`).toBeLessThanOrEqual(entete.client);
      expect((await rect(bouton(page))).droite).toBeLessThanOrEqual(largeur);
    }
    const defilement = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
    if (!zoom) expect(defilement.scroll, `défilement horizontal ${contexte} (menu ${ouvert ? 'ouvert' : 'fermé'})`).toBeLessThanOrEqual(defilement.client);
    const b = await rect(bouton(page));
    const t = await rect(page.getByTestId('entete-titre'));
    expect(b.haut).toBeLessThan(t.bas);
    expect(t.haut).toBeLessThan(b.bas);
    expect(b.hauteur).toBeGreaterThanOrEqual(44);
    if (!ouvert) return;
    const p = await rect(panneau(page));
    expect(p.gauche).toBeGreaterThanOrEqual(0);
    expect(p.droite).toBeLessThanOrEqual(largeur);
    const liens = panneau(page).locator('a');
    for (let i = 0; i < 2; i++) {
      expect((await rect(liens.nth(i))).hauteur).toBeGreaterThanOrEqual(44);
      const taille = await liens.nth(i).evaluate((e) => parseFloat(getComputedStyle(e).fontSize));
      expect(taille).toBeGreaterThanOrEqual(13);
    }
  }

  for (const { largeur, hauteur } of VIEWPORTS) {
    test(`CA10 - aucun débordement, bouton sur la ligne du titre et panneau dans l'écran à ${largeur} px`, async ({ page }) => {
      await page.setViewportSize({ width: largeur, height: hauteur });
      for (const { chemin, titre } of PAGES_ANONYMES) {
        await charger(page, chemin, titre);
        await verifierGeometrie(page, largeur, false);
        await ouvrirMenuVisiteur(page);
        await verifierGeometrie(page, largeur, true);
      }
    });
  }

  test('CA10 - à 320 px avec le texte agrandi à 200 %, l\'en-tête et le panneau restent dans l\'écran sans débordement', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 640 });
    for (const { chemin, titre } of PAGES_ANONYMES) {
      await charger(page, chemin, titre);
      await page.addStyleTag({ content: 'html{font-size:32px}' });
      await verifierGeometrie(page, 320, false, chemin, true);
      await ouvrirMenuVisiteur(page);
      await verifierGeometrie(page, 320, true, chemin, true);
    }
  });

  test('CA11 - le bouton et le panneau suivent la charte (couleurs, rayons, ombre, sans animation)', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await charger(page, '/', 'titre');

    await expect(bouton(page)).toHaveCSS('border-top-width', '1px');
    await expect(bouton(page)).toHaveCSS('border-top-style', 'solid');
    await expect(bouton(page)).toHaveCSS('border-top-color', CREME);
    await expect(bouton(page)).toHaveCSS('background-color', RGB_TRANSPARENT);
    await expect(bouton(page)).toHaveCSS('border-top-left-radius', '6px');
    await expect(bouton(page)).toHaveCSS('cursor', 'pointer');
    await bouton(page).hover();
    await expect(bouton(page)).toHaveCSS('background-color', FORET_SURVOL);
    await page.mouse.move(600, 600);
    await expect(bouton(page)).toHaveCSS('background-color', RGB_TRANSPARENT);

    await ouvrirMenuVisiteur(page);
    await page.mouse.move(600, 600);
    await expect(panneau(page)).toHaveCSS('background-color', FORET);
    await expect(panneau(page)).not.toHaveCSS('box-shadow', 'none');
    await expect(panneau(page)).toHaveCSS('border-top-left-radius', '12px');

    const liens = panneau(page).locator('a');
    const creation = liens.nth(1);
    await expect(creation).toHaveCSS('color', CREME);
    expect(await creation.evaluate((e) => getComputedStyle(e).textDecorationLine)).toBe('none');
    for (let i = 0; i < 2; i++) {
      const poids = await liens.nth(i).evaluate((e) => parseInt(getComputedStyle(e).fontWeight, 10));
      expect(poids).toBeGreaterThanOrEqual(600);
      await expect(liens.nth(i)).toHaveCSS('color', CREME);
    }
    expect(ratio(CREME, FORET)).toBeGreaterThanOrEqual(4.5);
    await creation.hover();
    await expect(creation).toHaveCSS('background-color', FORET_SURVOL);
    await expect(creation).toHaveCSS('color', CREME);
    expect(await creation.evaluate((e) => getComputedStyle(e).textDecorationLine)).toBe('underline');
    expect(ratio(CREME, FORET_SURVOL)).toBeGreaterThanOrEqual(4.5);

    const svg = bouton(page).locator('svg');
    await expect(svg).toHaveAttribute('stroke', 'currentColor');
    await expect(svg).toHaveCSS('stroke', CREME);

    const couleurs = await page.evaluate(() => {
      const racines = [document.querySelector('[data-testid="bouton-menu"]'), document.querySelector('[data-testid="menu-visiteur"]')];
      const trouvees: string[] = [];
      const durees: string[] = [];
      for (const racine of racines) {
        for (const e of [racine, ...Array.from(racine!.querySelectorAll('*'))] as Element[]) {
          const s = getComputedStyle(e);
          trouvees.push(s.color, s.backgroundColor, s.borderTopColor, s.borderRightColor, s.borderBottomColor, s.borderLeftColor, s.outlineColor);
          if (e.namespaceURI === 'http://www.w3.org/2000/svg') trouvees.push(s.stroke);
          durees.push(s.transitionDuration, s.animationDuration);
        }
      }
      return { trouvees, durees };
    });
    const autorisees = new Set([...RGB_PALETTE, RGB_TRANSPARENT]);
    for (const c of new Set(couleurs.trouvees)) {
      expect(autorisees.has(c), `couleur hors palette : ${c}`).toBe(true);
    }
    for (const d of new Set(couleurs.durees)) {
      expect(d).toBe('0s');
    }
  });

  test('CA12 - les liens des écrans sont conservés et distincts de ceux du menu', async ({ page }) => {
    await charger(page, '/', 'titre');
    await expect(page.getByTestId('lien-creer-compte')).toHaveCount(1);
    await expect(page.getByTestId('lien-creer-compte')).toBeVisible();
    await ouvrirMenuVisiteur(page);
    await expect(page.getByTestId('menu-lien-creer-compte')).toHaveCount(1);
    await expect(page.getByTestId('menu-lien-creer-compte')).toBeVisible();
    await expect(page.getByTestId('lien-creer-compte')).toHaveCount(1);
    await expect(page.locator('.entete').getByTestId('lien-se-connecter')).toHaveCount(0);
    await expect(page.getByTestId('lien-se-connecter')).toHaveCount(0);

    await page.goto('/connexion');
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await expect(page.getByTestId('lien-creer-compte-connexion')).toBeVisible();

    const pseudo = pseudoUnique();
    await page.goto('/creer-compte');
    await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
    await page.getByTestId('champ-pseudo').fill(pseudo);
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
    await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('message-succes')).toBeVisible();
    await expect(page.getByTestId('lien-se-connecter-succes')).toBeVisible();
    await expect(page.getByTestId('lien-se-connecter')).toHaveCount(0);
  });
});
