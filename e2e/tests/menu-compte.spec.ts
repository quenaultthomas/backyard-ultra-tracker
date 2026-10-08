import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  connecterAdminMaster,
  creerAdminParApi,
  creerBenevoleParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { ouvrirMenuCompte, ouvrirMenuVisiteur } from './aide-entete';
import { RGB_PALETTE, RGB_TRANSPARENT } from './aide-theme';

const CREME = 'rgb(247, 241, 227)';
const FORET = 'rgb(20, 53, 42)';
const FORET_SURVOL = 'rgb(31, 90, 67)';
const ORANGE = 'rgb(242, 140, 40)';

const MESSAGE_SERVICE_INDISPONIBLE = 'Service indisponible, veuillez réessayer plus tard.';
const MESSAGE_PAGE_EXPIREE = 'La page a expiré, veuillez réessayer.';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const bouton = (page: Page) => page.getByTestId('bouton-menu');
const panneau = (page: Page) => page.getByTestId('menu-compte');
const pseudoMenu = (page: Page) => page.getByTestId('entete-pseudo');

type RoleTest = 'coureur' | 'admin' | 'benevole' | 'master';

interface EntreeAttendue {
  testid: string;
  libelle: string;
  href: string | null;
}

const ESPACE_COUREUR: EntreeAttendue = { testid: 'menu-lien-espace-coureur', libelle: 'Espace coureur', href: '/coureur' };
const MES_INSCRIPTIONS: EntreeAttendue = { testid: 'menu-lien-mes-inscriptions', libelle: 'Mes inscriptions', href: '/coureur/inscriptions' };
const MON_COMPTE: EntreeAttendue = { testid: 'menu-lien-mon-compte', libelle: 'Mon compte', href: '/mon-compte' };
const ADMINISTRATION: EntreeAttendue = { testid: 'menu-lien-administration', libelle: 'Administration', href: '/administration' };
const ESPACE_BENEVOLE: EntreeAttendue = { testid: 'menu-lien-espace-benevole', libelle: 'Espace bénévole', href: '/benevole' };
const DECONNEXION: EntreeAttendue = { testid: 'bouton-deconnexion', libelle: 'Se déconnecter', href: null };

const ENTREES: Record<RoleTest, EntreeAttendue[]> = {
  coureur: [ESPACE_COUREUR, MES_INSCRIPTIONS, MON_COMPTE, DECONNEXION],
  admin: [ADMINISTRATION, MON_COMPTE, DECONNEXION],
  master: [ADMINISTRATION, MON_COMPTE, DECONNEXION],
  benevole: [ESPACE_BENEVOLE, MON_COMPTE, DECONNEXION],
};

const TOUS_LES_TESTID_ENTREES = [ESPACE_COUREUR, MES_INSCRIPTIONS, MON_COMPTE, ADMINISTRATION, ESPACE_BENEVOLE].map((e) => e.testid);

async function rect(locator: ReturnType<Page['getByTestId']>) {
  return locator.evaluate((e) => {
    const r = e.getBoundingClientRect();
    return { gauche: r.left, droite: r.right, haut: r.top, bas: r.bottom, largeur: r.width, hauteur: r.height };
  });
}

/** Élément qui a le focus : data-testid, ou balise si absent. */
async function focusActuel(page: Page): Promise<string> {
  return page.evaluate(() => {
    const e = document.activeElement;
    return e?.getAttribute('data-testid') ?? e?.tagName.toLowerCase() ?? 'aucun';
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

/** Connexion par l'écran, puis arrivée sur `chemin` avec le pseudo affiché (menu fermé). */
async function connecter(page: Page, pseudo: string, motDePasse: string, chemin = '/'): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(pseudoMenu(page)).toHaveText(pseudo);
  await page.goto(chemin);
  await expect(pseudoMenu(page)).toHaveText(pseudo);
  await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
}

async function coureurConnecte(page: Page, request: APIRequestContext, prefixe = 'coureur', chemin = '/'): Promise<string> {
  const pseudo = pseudoUnique(prefixe);
  await creerCompteParApi(request, pseudo);
  await connecter(page, pseudo, MOT_DE_PASSE, chemin);
  return pseudo;
}

async function apiNeuve(): Promise<APIRequestContext> {
  return (await import('@playwright/test')).request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
}

/** Crée le compte du rôle par l'API et ouvre une page connectée dans un contexte isolé. */
async function pageConnectee(browser: Browser, _request: APIRequestContext, role: RoleTest, chemin = '/') {
  // Contexte d'API neuf à chaque appel : la session d'un compte précédent ferait refuser la connexion suivante (409).
  const request = await apiNeuve();
  const contexte = await browser.newContext();
  const page = await contexte.newPage();
  if (role === 'master') {
    await connecterAdminMaster(page);
    await expect(pseudoMenu(page)).toBeAttached();
    await page.goto(chemin);
  } else {
    const pseudo = role === 'coureur' ? pseudoUnique() : role === 'admin' ? pseudoAdminUnique() : pseudoBenevoleUnique();
    if (role === 'coureur') await creerCompteParApi(request, pseudo);
    else if (role === 'admin') await creerAdminParApi(request, pseudo);
    else await creerBenevoleParApi(request, pseudo);
    const motDePasse = role === 'coureur' ? MOT_DE_PASSE : role === 'admin' ? MOT_DE_PASSE_ADMIN_CREE : MOT_DE_PASSE_BENEVOLE_CREE;
    await connecter(page, pseudo, motDePasse, chemin);
  }
  await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
  await request.dispose();
  return { contexte, page };
}

test.describe('Menu burger de l\'utilisateur connecté (R.3)', () => {
  test('CA1 - connecté, l\'en-tête ne montre que le titre et le bouton ; le panneau est fermé, sans anciens liens', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await coureurConnecte(page, request);

    const ordre = await page.locator('.entete').evaluate((entete) => Array.from(entete.querySelectorAll('[data-testid]')).map((e) => e.getAttribute('data-testid')));
    expect(ordre.slice(0, 3)).toEqual(['entete-titre', 'bouton-menu', 'menu-compte']);
    expect(ordre.slice(3)).toEqual(['entete-pseudo', 'menu-lien-espace-coureur', 'menu-lien-mes-inscriptions', 'menu-lien-mon-compte', 'bouton-deconnexion']);

    const largeur = await page.evaluate(() => document.documentElement.clientWidth);
    const b = await rect(bouton(page));
    const t = await rect(page.getByTestId('entete-titre'));
    expect(b.gauche + b.largeur / 2).toBeGreaterThan(t.gauche + t.largeur / 2);
    expect(Math.abs(b.droite - (largeur - 16))).toBeLessThanOrEqual(1);
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
    expect(await panneau(page).evaluate((e) => e.tagName.toLowerCase())).toBe('nav');
    await expect(panneau(page)).toHaveAttribute('id', 'entete-menu-panneau');
    await expect(panneau(page)).toHaveAttribute('aria-label', 'Menu utilisateur');
    await expect(panneau(page)).toHaveAttribute('hidden', '');
    await expect(panneau(page)).toBeHidden();

    await expect(pseudoMenu(page)).toBeHidden();
    for (const testid of [...TOUS_LES_TESTID_ENTREES, 'bouton-deconnexion']) {
      await expect(page.getByTestId(testid).first()).toBeHidden();
    }
    for (const ancien of ['lien-mon-compte', 'lien-espace-coureur', 'lien-mes-inscriptions', 'lien-administration', 'lien-espace-benevole']) {
      await expect(page.getByTestId(ancien)).toHaveCount(0);
    }
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(0);
  });

  for (const role of ['coureur', 'admin', 'master', 'benevole'] as RoleTest[]) {
    test(`CA2 - le menu du rôle ${role} contient exactement ses entrées, dans l'ordre`, async ({ browser, request }) => {
      const { contexte, page } = await pageConnectee(browser, request, role);
      try {
        await page.setViewportSize({ width: 1280, height: 800 });
        await ouvrirMenuCompte(page);
        const attendues = ENTREES[role];

        const lignes = panneau(page).locator('ul > li');
        await expect(lignes).toHaveCount(attendues.length);
        const reelles = await panneau(page).locator('ul > li > a, ul > li > button').evaluateAll((els) =>
          els.map((e) => ({ testid: e.getAttribute('data-testid'), libelle: (e.textContent ?? '').trim(), href: e.getAttribute('href'), balise: e.tagName.toLowerCase() })),
        );
        expect(reelles.map((r) => ({ testid: r.testid, libelle: r.libelle, href: r.href }))).toEqual(attendues);
        expect(reelles.at(-1)?.balise).toBe('button');

        const p = await rect(panneau(page));
        for (const entree of attendues) {
          const e = page.getByTestId(entree.testid);
          const r = await rect(e);
          expect(r.hauteur, entree.testid).toBeGreaterThanOrEqual(44);
          expect(Math.abs(r.largeur - p.largeur), entree.testid).toBeLessThanOrEqual(1);
          const taille = await e.evaluate((x) => parseFloat(getComputedStyle(x).fontSize));
          expect(taille).toBeGreaterThanOrEqual(13);
        }
        for (const testid of TOUS_LES_TESTID_ENTREES.filter((t) => !attendues.some((a) => a.testid === t))) {
          await expect(page.getByTestId(testid), `entrée d'un autre rôle : ${testid}`).toHaveCount(0);
        }
      } finally {
        await contexte.close();
      }
    });
  }

  test('CA3 - le pseudo est un texte simple en tête du panneau, hors de la liste et non focusable', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const alice = await coureurConnecte(page, request, 'Alice');
    await ouvrirMenuCompte(page);

    await expect(pseudoMenu(page)).toBeVisible();
    await expect(pseudoMenu(page)).toHaveText(alice);
    expect((await rect(pseudoMenu(page))).haut).toBeLessThan((await rect(page.getByTestId('menu-lien-espace-coureur'))).haut);
    await expect(panneau(page).locator('ul > li')).toHaveCount(4);
    await expect(panneau(page).locator('ul').getByTestId('entete-pseudo')).toHaveCount(0);

    const nature = await pseudoMenu(page).evaluate((e) => ({
      balise: e.tagName.toLowerCase(),
      parent: e.parentElement?.tagName.toLowerCase(),
      parentTexte: e.parentElement?.textContent ?? '',
      dansLien: e.closest('a, button') !== null,
      tabindex: [e, e.parentElement].map((x) => x?.getAttribute('tabindex')),
      role: [e, e.parentElement].map((x) => x?.getAttribute('role')),
      curseur: getComputedStyle(e).cursor,
    }));
    expect(nature.balise).toBe('span');
    expect(nature.parent).toBe('p');
    expect(nature.parentTexte).toContain('Connecté en tant que');
    expect(nature.dansLien).toBe(false);
    expect(nature.tabindex).toEqual([null, null]);
    expect(nature.role).toEqual([null, null]);
    expect(nature.curseur).not.toBe('pointer');

    await expect(page.getByTestId('menu-lien-espace-coureur')).toBeFocused();
    await pseudoMenu(page).click();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(page).toHaveURL(/\/$/);

    // Tab depuis le bouton : les 4 entrées successivement, jamais le pseudo.
    await bouton(page).focus();
    for (const testid of ['menu-lien-espace-coureur', 'menu-lien-mes-inscriptions', 'menu-lien-mon-compte', 'bouton-deconnexion']) {
      await page.keyboard.press('Tab');
      await expect(page.getByTestId(testid)).toBeFocused();
    }
  });

  test('CA3 - un pseudo de 30 caractères passe à la ligne sans être tronqué ni déborder à 320 px', async ({ page, request }) => {
    const suffixe = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
    const pseudo = `ZZZZZZZZZZZZZZZZZZ.é_-${suffixe}`.slice(0, 30);
    expect(pseudo).toHaveLength(30);
    await creerCompteParApi(request, pseudo);
    await page.setViewportSize({ width: 320, height: 640 });
    await connecter(page, pseudo, MOT_DE_PASSE);
    await ouvrirMenuCompte(page);

    expect(await pseudoMenu(page).evaluate((e) => e.textContent)).toBe(pseudo);
    await expect(pseudoMenu(page)).toBeVisible();
    const p = await rect(panneau(page));
    expect(p.gauche).toBeGreaterThanOrEqual(0);
    expect(p.droite).toBeLessThanOrEqual(320);
    const defilement = await panneau(page).evaluate((e) => ({ scroll: e.scrollWidth, client: e.clientWidth }));
    expect(defilement.scroll).toBeLessThanOrEqual(defilement.client);
    const ps = await rect(pseudoMenu(page));
    expect(ps.gauche).toBeGreaterThanOrEqual(p.gauche);
    expect(ps.droite).toBeLessThanOrEqual(p.droite + 1);
  });

  test('CA4 - la page courante est marquée exactement, et chaque entrée mène à son écran', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await coureurConnecte(page, request);

    const courante = async (attendue: string | null, testids: string[]) => {
      await ouvrirMenuCompte(page);
      for (const testid of testids) {
        const e = page.getByTestId(testid);
        if (testid === attendue) {
          await expect(e).toHaveAttribute('aria-current', 'page');
          await expect(e).toHaveCSS('border-left-width', '4px');
          await expect(e).toHaveCSS('border-left-style', 'solid');
          await expect(e).toHaveCSS('border-left-color', ORANGE);
        } else {
          await expect(e).not.toHaveAttribute('aria-current');
          await expect(e).toHaveCSS('border-left-width', '0px');
        }
      }
      await expect(pseudoMenu(page)).not.toHaveAttribute('aria-current');
      await expect(page.getByTestId('bouton-deconnexion')).not.toHaveAttribute('aria-current');
    };
    const liens = ['menu-lien-espace-coureur', 'menu-lien-mes-inscriptions', 'menu-lien-mon-compte'];

    await page.goto('/coureur/inscriptions');
    await expect(page.getByTestId('inscriptions-titre')).toBeVisible();
    await courante('menu-lien-mes-inscriptions', liens);
    // Sélectionner l'entrée courante : URL inchangée, menu fermé.
    await page.getByTestId('menu-lien-mes-inscriptions').click();
    await expect(page).toHaveURL(/\/coureur\/inscriptions$/);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();

    await page.getByTestId('bouton-menu').click();
    await page.getByTestId('menu-lien-espace-coureur').click();
    await expect(page).toHaveURL(/\/coureur$/);
    await expect(page.getByTestId('coureur-titre')).toBeVisible();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await courante('menu-lien-espace-coureur', liens);

    await page.getByTestId('menu-lien-mon-compte').click();
    await expect(page).toHaveURL(/\/mon-compte$/);
    await expect(page.getByTestId('titre-mon-compte')).toBeVisible();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await courante('menu-lien-mon-compte', liens);
  });

  test('CA4 - admin : « Administration » courante sur /administration, aucune entrée courante sur /administration/courses', async ({ browser, request }) => {
    const { contexte, page } = await pageConnectee(browser, request, 'admin', '/administration');
    try {
      await expect(page.getByTestId('titre-administration')).toBeVisible();
      await ouvrirMenuCompte(page);
      await expect(page.getByTestId('menu-lien-administration')).toHaveAttribute('aria-current', 'page');
      await expect(page.getByTestId('menu-lien-mon-compte')).not.toHaveAttribute('aria-current');
      await page.getByTestId('bouton-menu').click();

      await page.goto('/administration/courses');
      await expect(page.getByTestId('liste-courses')).toBeVisible();
      await ouvrirMenuCompte(page);
      await expect(page.getByTestId('menu-lien-administration')).not.toHaveAttribute('aria-current');
      await expect(page.getByTestId('menu-lien-administration')).toHaveCSS('border-left-width', '0px');
      await expect(page.getByTestId('menu-lien-mon-compte')).not.toHaveAttribute('aria-current');

      await page.getByTestId('menu-lien-administration').click();
      await expect(page).toHaveURL(/\/administration$/);
      await expect(page.getByTestId('titre-administration')).toBeVisible();
      await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    } finally {
      await contexte.close();
    }
  });

  test('CA4 - bénévole : « Espace bénévole » mène à /benevole', async ({ browser, request }) => {
    const { contexte, page } = await pageConnectee(browser, request, 'benevole', '/mon-compte');
    try {
      await expect(page.getByTestId('titre-mon-compte')).toBeVisible();
      await ouvrirMenuCompte(page);
      await page.getByTestId('menu-lien-espace-benevole').click();
      await expect(page).toHaveURL(/\/benevole$/);
      await expect(page.getByTestId('accueil-benevole-titre')).toBeVisible();
      await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
      await ouvrirMenuCompte(page);
      await expect(page.getByTestId('menu-lien-espace-benevole')).toHaveAttribute('aria-current', 'page');
    } finally {
      await contexte.close();
    }
  });

  for (const activation of ['clic', 'Entrée'] as const) {
    test(`CA5 - « Se déconnecter » (${activation}) ferme le menu, déconnecte et mène à /connexion`, async ({ page, request }) => {
      await page.setViewportSize({ width: 1280, height: 800 });
      await coureurConnecte(page, request);
      let envois = 0;
      let reponseDonnee = false;
      await page.route('**/api/deconnexion', async (route) => {
        if (route.request().method() === 'POST') {
          envois++;
          await new Promise((resolve) => setTimeout(resolve, 1000));
          reponseDonnee = true;
        }
        await route.continue();
      });

      await ouvrirMenuCompte(page);
      if (activation === 'clic') {
        await page.getByTestId('bouton-deconnexion').click();
      } else {
        await page.getByTestId('bouton-deconnexion').focus();
        await page.keyboard.press('Enter');
      }
      // Fermé dès l'activation, avant la réponse retardée ; un second clic est impossible.
      await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
      expect(reponseDonnee).toBe(false);
      await expect(page.getByTestId('bouton-deconnexion')).toBeHidden();

      await expect(page).toHaveURL(/\/connexion$/);
      await expect(page.getByTestId('message-deconnexion')).toBeVisible();
      await expect(panneau(page)).toHaveCount(0);
      await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
      await expect(bouton(page)).toBeVisible();
      await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
      await expect(pseudoMenu(page)).toHaveCount(0);
      expect(envois).toBe(1);
    });
  }

  for (const cas of [
    { nom: '500', status: 500, corps: 'Erreur', type: 'text/plain', message: MESSAGE_SERVICE_INDISPONIBLE },
    { nom: '403 CSRF_INVALIDE', status: 403, corps: JSON.stringify({ type: 'about:blank', title: 'Accès refusé', status: 403, detail: 'Jeton CSRF absent ou invalide.', code: 'CSRF_INVALIDE' }), type: 'application/problem+json', message: MESSAGE_PAGE_EXPIREE },
  ]) {
    test(`CA6 - échec de déconnexion (${cas.nom}) : message, menu fermé, focus sur le bouton, nouvel essai possible`, async ({ page, request }) => {
      const pseudo = await coureurConnecte(page, request);
      await page.route('**/api/deconnexion', (route) => route.fulfill({ status: cas.status, contentType: cas.type, body: cas.corps }));

      await ouvrirMenuCompte(page);
      await page.getByTestId('bouton-deconnexion').click();

      await expect(page.getByTestId('entete-erreur')).toHaveText(cas.message);
      await expect(page).toHaveURL(/\/$/);
      await expect(pseudoMenu(page)).toHaveText(pseudo);
      await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
      await expect(panneau(page)).toBeHidden();
      await expect(bouton(page)).toBeFocused();

      await page.unroute('**/api/deconnexion');
      await ouvrirMenuCompte(page);
      await page.getByTestId('bouton-deconnexion').click();
      await expect(page).toHaveURL(/\/connexion$/);
      await expect(page.getByTestId('message-deconnexion')).toBeVisible();
    });
  }

  test('CA7 - Échap ferme le menu et rend le focus au bouton ; menu fermé, Échap ne change rien', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await coureurConnecte(page, request);

    await ouvrirMenuCompte(page);
    await page.keyboard.press('Escape');
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();
    await expect(bouton(page)).toBeFocused();

    await page.goto('/mon-compte');
    await expect(page.getByTestId('titre-mon-compte')).toBeVisible();
    await page.getByTestId('mon-compte-champ-actuel').focus();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('mon-compte-champ-actuel')).toBeFocused();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
  });

  test('CA7 - un appui extérieur ferme le menu (fond de page, champ qui prend le focus), pas un appui dans le panneau', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await coureurConnecte(page, request, 'coureur', '/mon-compte');
    await expect(page.getByTestId('titre-mon-compte')).toBeVisible();

    // Appui sur le pseudo et dans la marge du panneau : le menu reste ouvert.
    await ouvrirMenuCompte(page);
    await pseudoMenu(page).click();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    const p = await rect(panneau(page));
    await page.mouse.click(p.gauche + p.largeur / 2, p.haut + 1);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(panneau(page)).toBeVisible();
    await expect(page).toHaveURL(/\/mon-compte$/);

    // Fond de page.
    await page.mouse.click(10, 790);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();

    // Champ de la page : ferme et prend le focus.
    await ouvrirMenuCompte(page);
    await page.getByTestId('mon-compte-champ-actuel').click();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByTestId('mon-compte-champ-actuel')).toBeFocused();
  });

  test('CA7 - double clic sur le bouton : fermé ; retour arrière et titre ferment ; le redimensionnement conserve', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await coureurConnecte(page, request);

    await bouton(page).dblclick();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(bouton(page)).toHaveCount(1);

    await page.goto('/mon-compte');
    await expect(page.getByTestId('titre-mon-compte')).toBeVisible();
    await ouvrirMenuCompte(page);
    await page.goBack();
    await expect(page).toHaveURL(/\/$/);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();

    await page.goForward();
    await expect(page).toHaveURL(/\/mon-compte$/);
    await ouvrirMenuCompte(page);
    await page.getByTestId('entete-titre').click();
    await expect(page).toHaveURL(/\/$/);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();

    await ouvrirMenuCompte(page);
    await page.setViewportSize({ width: 360, height: 640 });
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(panneau(page)).toBeVisible();
    const p = await rect(panneau(page));
    expect(p.gauche).toBeGreaterThanOrEqual(0);
    expect(p.droite).toBeLessThanOrEqual(360);
  });

  // `/` n'a aucun élément focusable hors de l'en-tête : Tab depuis la dernière entrée y quitte le document.
  for (const chemin of ['/mon-compte', '/']) {
  test(`CA8 - le menu se parcourt au clavier seul, sans piège de focus et sans flèches (sur ${chemin})`, async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await coureurConnecte(page, request, 'coureur', chemin);
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
    await expect.poll(async () => (await focusActuel(page)).startsWith('menu-') || (await focusActuel(page)) === 'bouton-deconnexion').toBe(false);
    await expect.poll(() => focusActuel(page)).not.toBe('bouton-menu');
    await page.keyboard.press('Shift+Tab');
    await expect(bouton(page)).toBeFocused();

    await page.keyboard.press('Enter');
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('menu-lien-espace-coureur')).toBeFocused();

    // Pas de flèches, Début ni Fin.
    for (const touche of ['ArrowDown', 'ArrowDown', 'ArrowUp', 'End', 'Home']) {
      await page.keyboard.press(touche);
      await expect(page.getByTestId('menu-lien-espace-coureur')).toBeFocused();
      await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    }

    for (const testid of ['menu-lien-mes-inscriptions', 'menu-lien-mon-compte', 'bouton-deconnexion']) {
      await page.keyboard.press('Tab');
      const e = page.getByTestId(testid);
      await expect(e).toBeFocused();
      await expect(e).toHaveCSS('outline-style', 'solid');
      await expect(e).toHaveCSS('outline-width', '3px');
      await expect(e).toHaveCSS('outline-color', ORANGE);
      await expect(e).toHaveCSS('background-color', FORET_SURVOL);
    }

    await page.keyboard.press('Tab');
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect.poll(() => focusActuel(page)).not.toMatch(/^(menu-lien|bouton-menu|bouton-deconnexion)/);
    await expect(panneau(page)).toBeHidden();

    // Ré-ouverture par Espace, Maj+Tab depuis la première entrée.
    await bouton(page).focus();
    await page.keyboard.press('Space');
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('menu-lien-espace-coureur')).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(bouton(page)).toBeFocused();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');

    await page.keyboard.press('Shift+Tab');
    await expect(page.getByTestId('entete-titre')).toBeFocused();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');

    await page.keyboard.press('Tab');
    await expect(bouton(page)).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('menu-lien-espace-coureur')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByTestId('menu-lien-mes-inscriptions')).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/coureur\/inscriptions$/);
    await expect(page.getByTestId('inscriptions-titre')).toBeVisible();
  });
  }

  test('CA9 - session expirée, ouverture pendant le chargement de l\'état et connexion en cours', async ({ page, request, context }) => {
    // Menu ouvert, cookie supprimé, action protégée en 401 : menu visiteur fermé.
    await coureurConnecte(page, request);
    await ouvrirMenuCompte(page);
    await context.clearCookies({ name: 'JSESSIONID' });
    await page.getByTestId('menu-lien-mes-inscriptions').click();
    await expect(panneau(page)).toHaveCount(0);
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
    await expect(bouton(page)).toBeVisible();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByTestId('menu-visiteur')).toBeHidden();
    await expect(pseudoMenu(page)).toHaveCount(0);
    for (const testid of TOUS_LES_TESTID_ENTREES) {
      await expect(page.getByTestId(testid)).toHaveCount(0);
    }
  });

  test('CA9 - état inconnu : aucun burger, puis le menu connecté fermé, sans jamais de menu visiteur', async ({ page, request }) => {
    await coureurConnecte(page, request);
    await page.addInitScript(() => {
      (window as unknown as { __visiteurVu: boolean }).__visiteurVu = false;
      new MutationObserver(() => {
        if (document.querySelector('[data-testid="menu-visiteur"]')) (window as unknown as { __visiteurVu: boolean }).__visiteurVu = true;
      }).observe(document, { childList: true, subtree: true });
    });
    await page.route('**/api/comptes/moi', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 3000));
      await route.continue();
    });
    await page.goto('/');
    await expect(page.getByTestId('entete-titre')).toBeVisible();
    await expect(bouton(page)).toHaveCount(0);
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(0);

    await expect(bouton(page)).toBeVisible({ timeout: 15000 });
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toHaveCount(1);
    await expect(panneau(page)).toBeHidden();
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __visiteurVu: boolean }).__visiteurVu)).toBe(false);
  });

  test('CA9 - connexion en cours avec le menu visiteur ouvert : le menu connecté naît fermé', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await page.route('**/api/connexion', async (route) => {
      if (route.request().method() === 'POST') await new Promise((resolve) => setTimeout(resolve, 2000));
      await route.continue();
    });
    await ouvrirConnexion(page);
    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await ouvrirMenuVisiteur(page);

    await expect(pseudoMenu(page)).toHaveText(pseudo);
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(0);
    await expect(panneau(page)).toHaveCount(1);
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
    await expect(panneau(page)).toBeHidden();
  });

  const VIEWPORTS = [
    { largeur: 320, hauteur: 640 },
    { largeur: 360, hauteur: 640 },
    { largeur: 1280, hauteur: 800 },
  ];

  async function verifierGeometrie(page: Page, largeur: number, ouvert: boolean, entrees: EntreeAttendue[], contexte: string, zoom = false): Promise<void> {
    if (zoom) {
      // À 200 % de texte : mesure limitée à l'en-tête et au panneau (la page entière est exclue, R.5).
      const entete = await page.locator('header').evaluate((e) => ({ scroll: e.scrollWidth, client: e.clientWidth }));
      expect(entete.scroll, `débordement de l'en-tête ${contexte}`).toBeLessThanOrEqual(entete.client);
      expect((await rect(bouton(page))).droite).toBeLessThanOrEqual(largeur);
    } else {
      const defilement = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
      expect(defilement.scroll, `défilement horizontal ${contexte} (menu ${ouvert ? 'ouvert' : 'fermé'})`).toBeLessThanOrEqual(defilement.client);
    }
    const b = await rect(bouton(page));
    const t = await rect(page.getByTestId('entete-titre'));
    expect(b.haut).toBeLessThan(t.bas);
    expect(t.haut).toBeLessThan(b.bas);
    expect(b.hauteur).toBeGreaterThanOrEqual(44);
    if (!ouvert) return;
    const p = await rect(panneau(page));
    expect(p.gauche).toBeGreaterThanOrEqual(0);
    expect(p.droite).toBeLessThanOrEqual(largeur);
    for (const entree of entrees) {
      const e = page.getByTestId(entree.testid);
      expect((await rect(e)).hauteur, `${contexte} ${entree.testid}`).toBeGreaterThanOrEqual(44);
      expect(await e.evaluate((x) => parseFloat(getComputedStyle(x).fontSize))).toBeGreaterThanOrEqual(13);
    }
  }

  for (const { largeur, hauteur } of VIEWPORTS) {
    test(`CA10 - aucun débordement, bouton sur la ligne du titre et panneau dans l'écran à ${largeur} px`, async ({ browser, request }) => {
      for (const role of ['coureur', 'admin', 'benevole'] as RoleTest[]) {
        const { contexte, page } = await pageConnectee(browser, request, role);
        try {
          await page.setViewportSize({ width: largeur, height: hauteur });
          await verifierGeometrie(page, largeur, false, ENTREES[role], role);
          await ouvrirMenuCompte(page);
          await verifierGeometrie(page, largeur, true, ENTREES[role], role);
        } finally {
          await contexte.close();
        }
      }
    });
  }

  test('CA10 - à 320 px avec le texte à 200 %, l\'en-tête et le panneau restent dans l\'écran et atteignables', async ({ browser, request }) => {
    for (const role of ['coureur', 'admin', 'benevole'] as RoleTest[]) {
      const { contexte, page } = await pageConnectee(browser, request, role);
      try {
        await page.setViewportSize({ width: 320, height: 640 });
        await page.addStyleTag({ content: 'html{font-size:32px}' });
        await verifierGeometrie(page, 320, false, ENTREES[role], role, true);
        await ouvrirMenuCompte(page);
        await verifierGeometrie(page, 320, true, ENTREES[role], role, true);

        const hauteurPanneau = await rect(panneau(page));
        expect(hauteurPanneau.hauteur).toBeLessThanOrEqual(640);
        const defilementInterne = await panneau(page).evaluate((e) => getComputedStyle(e).overflowY);
        expect(['auto', 'scroll']).toContain(defilementInterne);
        const derniere = page.getByTestId('bouton-deconnexion');
        await derniere.scrollIntoViewIfNeeded();
        await expect(derniere).toBeVisible();
        await expect(derniere).toBeInViewport();
      } finally {
        await contexte.close();
      }
    }
  });

  test('CA11 - le bouton et le panneau suivent la charte (couleurs, rayons, ombre, sans animation)', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await coureurConnecte(page, request);

    await expect(bouton(page)).toHaveCSS('border-top-width', '1px');
    await expect(bouton(page)).toHaveCSS('border-top-style', 'solid');
    await expect(bouton(page)).toHaveCSS('border-top-color', CREME);
    await expect(bouton(page)).toHaveCSS('background-color', RGB_TRANSPARENT);
    await expect(bouton(page)).toHaveCSS('border-top-left-radius', '6px');

    await ouvrirMenuCompte(page);
    await page.mouse.move(600, 600);
    await expect(bouton(page)).toHaveCSS('background-color', FORET_SURVOL);
    await expect(panneau(page)).toHaveCSS('background-color', FORET);
    await expect(panneau(page)).not.toHaveCSS('box-shadow', 'none');
    await expect(panneau(page)).toHaveCSS('border-top-left-radius', '12px');

    const testids = ['menu-lien-espace-coureur', 'menu-lien-mes-inscriptions', 'menu-lien-mon-compte', 'bouton-deconnexion'];
    for (const testid of testids) {
      const e = page.getByTestId(testid);
      await expect(e).toHaveCSS('color', CREME);
      await expect(e).toHaveCSS('background-color', RGB_TRANSPARENT);
      await expect(e).toHaveCSS('border-top-width', '0px');
      await expect(e).toHaveCSS('border-left-width', '0px');
      expect(await e.evaluate((x) => parseInt(getComputedStyle(x).fontWeight, 10))).toBeGreaterThanOrEqual(600);
      expect(await e.evaluate((x) => getComputedStyle(x).textDecorationLine)).toBe('none');
    }
    const compte = page.getByTestId('menu-lien-mon-compte');
    const deconnexion = page.getByTestId('bouton-deconnexion');
    const mesure = (l: typeof compte) => l.evaluate((x) => ({ couleur: getComputedStyle(x).color, graisse: getComputedStyle(x).fontWeight, hauteur: x.getBoundingClientRect().height }));
    expect(await mesure(deconnexion)).toEqual(await mesure(compte));

    expect(ratio(CREME, FORET)).toBeGreaterThanOrEqual(4.5);
    for (const e of [compte, deconnexion]) {
      await e.hover();
      await expect(e).toHaveCSS('background-color', FORET_SURVOL);
      await expect(e).toHaveCSS('color', CREME);
    }
    expect(ratio(CREME, FORET_SURVOL)).toBeGreaterThanOrEqual(4.5);
    await page.mouse.move(600, 600);

    expect(await pseudoMenu(page).evaluate((e) => parseInt(getComputedStyle(e).fontWeight, 10))).toBeGreaterThanOrEqual(700);
    await expect(pseudoMenu(page)).toHaveCSS('color', CREME);

    const svg = bouton(page).locator('svg');
    await expect(svg).toHaveAttribute('stroke', 'currentColor');
    await expect(svg).toHaveCSS('stroke', CREME);

    const mesures = await page.evaluate(() => {
      const racines = [document.querySelector('[data-testid="bouton-menu"]'), document.querySelector('[data-testid="menu-compte"]')];
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
    for (const c of new Set(mesures.trouvees)) {
      expect(autorisees.has(c), `couleur hors palette : ${c}`).toBe(true);
    }
    for (const d of new Set(mesures.durees)) {
      expect(d).toBe('0s');
    }
  });

  for (const { chemin, titre, courante } of [
    { chemin: '/', titre: 'titre', courante: null },
    { chemin: '/connexion', titre: 'titre-connexion', courante: 'menu-lien-se-connecter' },
    { chemin: '/creer-compte', titre: 'titre-creer-compte', courante: 'menu-lien-creer-compte' },
  ]) {
    test(`CA12 - le menu visiteur est inchangé sur ${chemin}, sans menu de compte`, async ({ page }) => {
      await page.goto(chemin);
      await expect(page.getByTestId(titre)).toBeVisible();
      await expect(bouton(page)).toBeVisible();
      await expect(bouton(page)).toHaveAttribute('aria-label', 'Menu du compte');
      await expect(page.getByTestId('menu-visiteur')).toHaveAttribute('aria-label', 'Menu visiteur');
      await expect(panneau(page)).toHaveCount(0);
      await expect(pseudoMenu(page)).toHaveCount(0);

      await ouvrirMenuVisiteur(page);
      const liens = page.getByTestId('menu-visiteur').locator('ul > li > a');
      await expect(liens).toHaveCount(2);
      await expect(liens.nth(0)).toHaveAttribute('data-testid', 'menu-lien-se-connecter');
      await expect(liens.nth(0)).toHaveText('Se connecter');
      await expect(liens.nth(1)).toHaveAttribute('data-testid', 'menu-lien-creer-compte');
      await expect(liens.nth(1)).toHaveText('Créer un compte');
      await expect(page.getByTestId('menu-lien-se-connecter')).toBeFocused();
      for (const testid of ['menu-lien-se-connecter', 'menu-lien-creer-compte']) {
        if (testid === courante) await expect(page.getByTestId(testid)).toHaveAttribute('aria-current', 'page');
        else await expect(page.getByTestId(testid)).not.toHaveAttribute('aria-current');
      }
      await expect(panneau(page)).toHaveCount(0);
    });
  }

  test('CA13 - plus aucun ancien data-testid de lien d\'en-tête dans e2e/ ni dans frontend/src/', async () => {
    const racine = join(__dirname, '..', '..');
    const ancien = /(?<![\w-])lien-(administration|espace-benevole|espace-coureur|mes-inscriptions|mon-compte)(?![\w-])/;
    const fichiers: string[] = [];
    const parcourir = (dossier: string) => {
      for (const nom of readdirSync(dossier)) {
        if (nom === 'node_modules' || nom === 'node_modules.root-old' || nom === 'playwright-report' || nom === 'test-results') continue;
        const chemin = join(dossier, nom);
        if (statSync(chemin).isDirectory()) parcourir(chemin);
        else if (/\.(ts|html|css|json)$/.test(nom) && !chemin.endsWith('menu-compte.spec.ts') && nom !== 'package-lock.json') fichiers.push(chemin);
      }
    };
    parcourir(join(racine, 'e2e'));
    parcourir(join(racine, 'frontend', 'src'));
    expect(fichiers.length).toBeGreaterThan(10);
    const fautifs = fichiers.filter((f) => ancien.test(readFileSync(f, 'utf8')));
    expect(fautifs).toEqual([]);
  });
});
