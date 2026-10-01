import { expect, test, type Page } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import {
  ANONYMOUS_BOTH_LINKS, ANONYMOUS_CREATE_SCREEN_LINKS, ANONYMOUS_LOGIN_SCREEN_LINKS, ANONYMOUS_SCAN_LINKS, auditNoAdminLink,
  headerAccountText, RUNNER_CONNECTED_LINKS, type PublicPageAuditOptions,
} from '../fixtures/admin-separation';
import { expectAccountPage, loginRunner } from '../fixtures/ui';

/**
 * CA2 (inc. 6) — Aucun lien public vers l'espace admin (RG2, RG7, CL1). Tous les écrans publics sont audités,
 * en-tête de navigation compris, d'abord en anonyme puis connecté `Lievre-{run}` : aucun lien vers `/admin/**`,
 * aucun lien vers `/connexion` sauf le « Se connecter » de `/scan`, aucun texte « Administration ». Les liens
 * « Courses », « Mes inscriptions » et « Scan » de l'en-tête sont conservés (PO6).
 */
test.describe('@INC-6 @smoke @INC6-CA2 Aucun lien public vers l\'espace admin', () => {
  test.use({ serviceWorkers: 'block' });

  test('anonyme puis coureur connecté : aucun lien, bouton ou texte menant à l\'administration', async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const raceA = await api.createRace({ name: `E2E-I6A-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const raceB = await api.createRace({ name: `E2E-I6B-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const other = await api.register(raceA.id, 'Autre Coureur');

    // Évolution inc. 7 (catégorie B, RG4, CA14) : en anonyme, les liens de la zone « compte » sont exactement ceux
    // attendus pour l'écran ; connecté en coureur, ni « Se connecter » ni « Créer un compte », mais « Connecté : {pseudo} ».
    async function audit(label: string, anonymousZone: PublicPageAuditOptions): Promise<void> {
      if (label.startsWith('coureur')) {
        await expect(page.getByRole('banner'), `${label} : état connecté`).toContainText(`Connecté : ${pseudo}`);
        expect(await headerAccountText(page), `${label} : zone compte`).toBe(`Connecté : ${pseudo}`);
        // `/scan` garde son bandeau « Se connecter » (connexion staff, indépendante de la connexion coureur).
        await auditNoAdminLink(page, label, label.endsWith('/scan')
          ? { loginLinks: ['Se connecter'], createAccountLinks: [] } : RUNNER_CONNECTED_LINKS);
      } else {
        await auditNoAdminLink(page, label, anonymousZone);
      }
    }

    async function auditPublicPages(phase: string): Promise<void> {
      await page.goto('/');
      await expect(page.getByText(raceA.name).first()).toBeVisible();
      await audit(`${phase} /`, ANONYMOUS_BOTH_LINKS);
      await expectScanAndNavigationLinks(page, phase);

      await page.goto(`/courses/${raceA.id}`);
      await expect(page.getByRole('heading', { level: 1 })).toContainText(raceA.name);
      await expect(page.getByRole("link", { name: other.name }).first()).toBeVisible();
      await audit(`${phase} /courses/{id}`, ANONYMOUS_BOTH_LINKS);

      await page.goto(`/coureurs/${other.runnerId}`);
      await expect(page.getByRole('heading', { level: 1 })).toContainText(other.name);
      await audit(`${phase} /coureurs/{id}`, ANONYMOUS_BOTH_LINKS);

      await page.goto(`/inscription/${raceB.id}`);
      await expect(page.getByRole('heading', { level: 1 })).toContainText('Inscription');
      await audit(`${phase} /inscription/{id}`, ANONYMOUS_BOTH_LINKS);

      await page.goto('/compte/connexion');
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await audit(`${phase} /compte/connexion`, ANONYMOUS_LOGIN_SCREEN_LINKS);

      await page.goto('/scan');
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
      await expect(page.getByRole('link', { name: 'Se connecter' })).toBeVisible();
      await audit(`${phase} /scan`, ANONYMOUS_SCAN_LINKS);

      await page.goto('/connexion');
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
      await audit(`${phase} /connexion`, ANONYMOUS_LOGIN_SCREEN_LINKS);
    }

    // Phase 1, anonyme.
    await auditPublicPages('anonyme');
    await page.goto('/compte');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await auditNoAdminLink(page, 'anonyme /compte (renvoyé vers la connexion coureur)', ANONYMOUS_LOGIN_SCREEN_LINKS);

    // Confirmation d'inscription (création du compte `Lievre-{run}` par l'interface).
    await page.goto(`/inscription/${raceA.id}`);
    await page.getByLabel('Pseudo').fill(typed);
    await page.getByLabel('Mot de passe', { exact: true }).fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Inscription confirmée');
    await auditNoAdminLink(page, 'anonyme confirmation d\'inscription', ANONYMOUS_BOTH_LINKS);

    // Phase 2, connecté en coureur (« Rester connecté 24 h » pour survivre aux navigations complètes).
    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD, { remember: true });
    await expectAccountPage(page, pseudo);
    await auditNoAdminLink(page, 'coureur /compte', RUNNER_CONNECTED_LINKS);
    await auditPublicPages('coureur');
    await page.goto('/compte');
    await expectAccountPage(page, pseudo);

    // Confirmation d'une inscription faite avec le compte connecté (E20).
    await page.goto(`/inscription/${raceB.id}`);
    await page.getByRole('button', { name: "M'inscrire à cette course" }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Inscription confirmée');
    await expect(page.getByRole('banner')).toContainText(`Connecté : ${pseudo}`);
    await auditNoAdminLink(page, 'coureur confirmation d\'inscription', RUNNER_CONNECTED_LINKS);
  });

  test('« Scan » cible /scan (en-tête et accueil), « Courses » et « Mes inscriptions » sont présents', async ({ page }) => {
    await page.goto('/');
    await expectScanAndNavigationLinks(page, 'anonyme');
    await page.getByRole('main').getByRole('link', { name: 'Scan', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Courses', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Scan', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
  });
});

/** Liens de l'en-tête (Courses, Mes inscriptions, Scan) et lien « Scan » de l'accueil, tous vers `/scan` pour Scan. */
async function expectScanAndNavigationLinks(page: Page, phase: string): Promise<void> {
  const nav = page.getByLabel('Navigation principale');
  await expect(nav.getByRole('link', { name: 'Courses', exact: true }), `${phase} : « Courses »`).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Mes inscriptions', exact: true }), `${phase} : « Mes inscriptions »`)
    .toBeVisible();
  const scanLinks = page.getByRole('link', { name: 'Scan', exact: true });
  await expect(scanLinks, `${phase} : liens « Scan » (en-tête et accueil)`).toHaveCount(2);
  for (const link of await scanLinks.all()) {
    await expect(link).toHaveAttribute('href', '/scan');
  }
  await expect(nav.getByRole('link', { name: 'Scan', exact: true })).toHaveAttribute('href', '/scan');
  await expect(page.getByRole('main').getByRole('link', { name: 'Scan', exact: true })).toHaveAttribute('href', '/scan');
}
