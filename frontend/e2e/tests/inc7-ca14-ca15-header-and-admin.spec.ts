import { expect, test, type Page } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, SCANNER_PASSWORD, SCANNER_USERNAME, uniqueRun } from '../fixtures/api';
import {
  ANONYMOUS_BOTH_LINKS, ANONYMOUS_CREATE_SCREEN_LINKS, ANONYMOUS_LOGIN_SCREEN_LINKS, auditNoAdminLink,
  headerAccountText, RUNNER_CONNECTED_LINKS, trackAdminApi, type PublicPageAuditOptions,
} from '../fixtures/admin-separation';
import { chooseStaffEntry, expectAccountPage, loginRunner, submitLogin } from '../fixtures/ui';

interface ScreenCase {
  readonly label: string;
  readonly path: string;
  readonly heading: string | RegExp;
  /** Liens attendus pour un visiteur sans connexion coureur. */
  readonly anonymous: PublicPageAuditOptions;
  /** Texte attendu de la zone « compte » de l'en-tête pour un visiteur sans connexion coureur. */
  readonly anonymousZone: string;
  /** Lien « Se connecter » du bandeau de la page, seul admis quand un coureur est connecté. */
  readonly pageLoginLink: boolean;
}

const BOTH = 'Se connecter Créer un compte';

/**
 * CA14 (inc. 7) — Liens publics de l'en-tête (RG4, RG9, amende CA2 inc. 6) : zone « compte » par écran, pas de
 * doublon, état « Connecté : {pseudo} », aucun nouveau « Se déconnecter », connexion staff sans effet, aucun lien
 * `/admin` ni texte « Administration » (contrôle conservé à l'identique).
 */
test.describe('@INC-7 @smoke @INC7-CA14 Liens publics de l\'en-tête', () => {
  test.use({ serviceWorkers: 'block' });

  test('anonyme : zone « compte » par écran, liens conservés, aucun lien /admin', async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I7C14A-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const runner = await api.register(race.id, 'Coureur Entete');

    for (const screen of screens(race.id, runner.runnerId, runner.name)) {
      await page.goto(screen.path);
      await expect(page.getByRole('heading', { level: 1 }), `${screen.label} : titre`).toHaveText(screen.heading);
      await auditNoAdminLink(page, `anonyme ${screen.label}`, screen.anonymous);
      await expect.poll(() => headerAccountText(page), { message: `${screen.label} : zone compte` })
        .toBe(screen.anonymousZone);
      await expectNavigationLinks(page, screen.label, screen.path === '/' ? 2 : 1);

      // Cibles des liens de la zone « compte ».
      const header = page.getByRole('banner');
      if (screen.anonymousZone.includes('Se connecter')) {
        expect(await hrefPath(header.getByRole('link', { name: 'Se connecter' })), `${screen.label} : cible`)
          .toBe('/connexion');
      }
      if (screen.anonymousZone.includes('Créer un compte')) {
        expect(await hrefPath(header.getByRole('link', { name: 'Créer un compte' })), `${screen.label} : cible`)
          .toBe('/inscription');
      }
      if (screen.path === '/scan') {
        // Un seul lien « Se connecter » sur /scan : celui du bandeau, avec retour=/scan.
        const loginLinks = page.getByRole('link', { name: 'Se connecter' });
        await expect(loginLinks).toHaveCount(1);
        const target = new URL(await loginLinks.evaluate((el) => (el as HTMLAnchorElement).href));
        expect(target.pathname).toBe('/connexion');
        expect(target.searchParams.get('retour')).toBe('/scan');
        await expect(page.getByRole('banner').getByRole('link', { name: 'Se connecter' })).toHaveCount(0);
      }
    }
  });

  test('coureur connecté : « Connecté : {pseudo} » partout, ni « Se connecter » ni « Créer un compte » en en-tête, un seul « Se déconnecter » (sur /compte)', async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C14B-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const runner = await api.register(race.id, 'Coureur Entete');
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);

    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD, { remember: true });
    await expectAccountPage(page, pseudo);

    for (const screen of [...screens(race.id, runner.runnerId, runner.name), accountScreen()]) {
      await page.goto(screen.path);
      await expect(page.getByRole('heading', { level: 1 }), `${screen.label} : titre`).toHaveText(screen.heading);
      await expect(page.getByRole('banner'), `${screen.label} : état connecté`).toContainText(`Connecté : ${pseudo}`);
      expect(await headerAccountText(page), `${screen.label} : zone compte`).toBe(`Connecté : ${pseudo}`);
      await auditNoAdminLink(page, `coureur ${screen.label}`,
        screen.pageLoginLink ? { loginLinks: ['Se connecter'], createAccountLinks: [] } : RUNNER_CONNECTED_LINKS);
      await expect(page.getByRole('banner').getByRole('link')
        .filter({ hasText: /Se connecter|Créer un compte/ }), `${screen.label} : liens du compte`).toHaveCount(0);
      // Aucun nouveau contrôle de déconnexion : un seul bouton « Se déconnecter », sur /compte.
      await expect(page.getByRole('button', { name: 'Se déconnecter' }), `${screen.label} : déconnexion`)
        .toHaveCount(screen.path === '/compte' ? 1 : 0);
      await expect(page.getByRole('banner').getByRole('button'), `${screen.label} : boutons de l'en-tête`).toHaveCount(0);
    }
  });

  test('bénévole puis admin connectés seulement : en-tête identique à celui d\'un anonyme', async ({ page }) => {
    test.setTimeout(90_000);
    // SCANNER mémorisé 24 h : survit aux navigations complètes.
    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await submitLogin(page, 'staff', SCANNER_USERNAME, SCANNER_PASSWORD, { remember: true });
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await expect(page.getByText('scanner', { exact: true })).toBeVisible();
    for (const path of ['/', '/inscription', '/scan', '/connexion']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expectStaffInvisibleInHeader(page, `SCANNER ${path}`, path);
    }

    // ADMIN (en mémoire seulement) : connexion par l'écran unique, puis navigation interne.
    await page.goto('/connexion');
    await chooseStaffEntry(page);
    await submitLogin(page, 'staff', 'admin-test', 'admin-secret');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');
    await expectStaffInvisibleInHeader(page, 'ADMIN /admin', '/admin');
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Courses', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');
    await expectStaffInvisibleInHeader(page, 'ADMIN /', '/');
  });
});

/** En-tête d'un visiteur sans connexion coureur : ni identifiant ni rôle staff, mêmes liens qu'un anonyme. */
async function expectStaffInvisibleInHeader(page: Page, label: string, path: string): Promise<void> {
  const expected = path === '/scan' || path === '/connexion' ? 'Créer un compte'
    : path === '/inscription' ? 'Se connecter' : BOTH;
  await expect.poll(() => headerAccountText(page), { message: `${label} : zone compte` }).toBe(expected);
  const header = (await page.getByRole('banner').innerText()).toLowerCase();
  for (const word of ['scanner-test', 'admin-test', 'bénévole', 'benevole', 'administrateur', 'administration', 'connecté']) {
    expect(header, `${label} : « ${word} » dans l'en-tête`).not.toContain(word);
  }
}

function screens(raceId: number, runnerId: number, runnerName: string): readonly ScreenCase[] {
  return [
    { label: '/', path: '/', heading: 'Courses', anonymous: ANONYMOUS_BOTH_LINKS, anonymousZone: BOTH, pageLoginLink: false },
    { label: '/courses/{id}', path: `/courses/${raceId}`, heading: /E2E-I7C14/, anonymous: ANONYMOUS_BOTH_LINKS,
      anonymousZone: BOTH, pageLoginLink: false },
    { label: '/coureurs/{id}', path: `/coureurs/${runnerId}`, heading: new RegExp(runnerName),
      anonymous: ANONYMOUS_BOTH_LINKS, anonymousZone: BOTH, pageLoginLink: false },
    { label: '/inscription/{id}', path: `/inscription/${raceId}`, heading: /Inscription/, anonymous: ANONYMOUS_BOTH_LINKS,
      anonymousZone: BOTH, pageLoginLink: false },
    { label: '/compte/connexion', path: '/compte/connexion', heading: 'Connexion', anonymous: ANONYMOUS_LOGIN_SCREEN_LINKS,
      anonymousZone: 'Créer un compte', pageLoginLink: false },
    { label: '/inscription', path: '/inscription', heading: 'Créer un compte', anonymous: ANONYMOUS_CREATE_SCREEN_LINKS,
      anonymousZone: 'Se connecter', pageLoginLink: false },
    { label: '/scan', path: '/scan', heading: 'Scan', anonymous: ANONYMOUS_BOTH_LINKS, anonymousZone: 'Créer un compte',
      pageLoginLink: true },
    { label: '/connexion', path: '/connexion', heading: 'Connexion', anonymous: ANONYMOUS_LOGIN_SCREEN_LINKS,
      anonymousZone: 'Créer un compte', pageLoginLink: false },
  ];
}

function accountScreen(): ScreenCase {
  return { label: '/compte', path: '/compte', heading: 'Mes inscriptions', anonymous: ANONYMOUS_LOGIN_SCREEN_LINKS,
    anonymousZone: 'Créer un compte', pageLoginLink: false };
}

/** Liens « Courses », « Mes inscriptions » et « Scan » de l'en-tête conservés (deux liens « Scan » sur `/`). */
async function expectNavigationLinks(page: Page, label: string, scanLinkCount: number): Promise<void> {
  const nav = page.getByLabel('Navigation principale');
  await expect(nav.getByRole('link', { name: 'Courses', exact: true }), `${label} : « Courses »`).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Mes inscriptions', exact: true }), `${label} : « Mes inscriptions »`)
    .toBeVisible();
  await expect(nav.getByRole('link', { name: 'Scan', exact: true }), `${label} : « Scan »`).toBeVisible();
  await expect(page.getByRole('link', { name: 'Scan', exact: true }), `${label} : liens « Scan »`)
    .toHaveCount(scanLinkCount);
}

async function hrefPath(link: ReturnType<Page['getByRole']>): Promise<string> {
  return new URL(await link.evaluate((el) => (el as HTMLAnchorElement).href)).pathname;
}

/**
 * CA15 (inc. 7) — `/admin` toujours introuvable (RG10) : anonyme, coureur, SCANNER ; `/admin` et `/admin/comptes`
 * affichent « Page introuvable », sans requête `/api/admin/**` (CA3 et CA4 inc. 6 inchangés).
 */
test.describe('@INC-7 @smoke @INC7-CA15 /admin toujours introuvable', () => {
  test.use({ serviceWorkers: 'block' });

  test('anonyme, coureur puis SCANNER : « Page introuvable », aucune requête /api/admin/**', async ({ page, context }, testInfo) => {
    test.setTimeout(120_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C15-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);
    const adminApi = trackAdminApi(context);

    async function expectNotFound(profile: string): Promise<void> {
      for (const path of ['/admin', '/admin/comptes']) {
        await page.goto(path);
        await expect(page.getByRole('heading', { level: 1 }), `${profile} ${path}`).toHaveText('Page introuvable');
        await expect(page.getByRole('link', { name: "Retour à l'accueil" })).toBeVisible();
        expect(new URL(page.url()).pathname, `${profile} ${path} : adresse`).toBe(path);
      }
      expect(adminApi.urls(), `${profile} : requêtes /api/admin/**`).toEqual([]);
    }

    await expectNotFound('anonyme');

    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD, { remember: true });
    await expectAccountPage(page, `lievre-${run}`);
    await expectNotFound('coureur');

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await submitLogin(page, 'staff', SCANNER_USERNAME, SCANNER_PASSWORD, { remember: true });
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await expectNotFound('SCANNER');
  });
});
