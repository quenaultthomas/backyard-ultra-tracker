import { expect, test, type Page } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';
import { loginAdmin } from '../fixtures/ui';

const E13 = /\/api\/admin\/races\/\d+\/runners$/;

async function loginAdminFromConnexion(page: Page): Promise<void> {
  await page.goto('/connexion');
  await page.getByLabel("Nom d'utilisateur").fill('admin-test');
  await page.getByLabel('Mot de passe').fill('admin-secret');
  await page.getByRole('button', { name: 'Se connecter' }).click();
}

/**
 * CA5 (inc. 6) — Accès ADMIN (RG3, CL9). `/connexion` ouverte directement mène à `/admin` ; la course liste ses
 * coureurs ; après un rechargement, `/admin` est « Page introuvable » (identifiants en mémoire seulement, RG7
 * inc. 4) ; une nouvelle connexion par `/connexion` rend l'accès.
 */
test.describe('@INC-6 @smoke @INC6-CA5 Accès ADMIN', () => {
  test.use({ serviceWorkers: 'block' });

  test('connexion directe, liste des courses et des coureurs, rechargement, nouvelle connexion', async ({ page }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I6C5-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const runner = await api.register(race.id, 'Coureur Admin');

    await loginAdminFromConnexion(page);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');
    expect(new URL(page.url()).pathname).toBe('/admin');
    const raceCard = page.locator('li.card').filter({ hasText: race.name });
    await expect(raceCard).toBeVisible();

    await raceCard.getByRole('link', { name: 'Gérer la course et les coureurs' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(race.name);
    expect(new URL(page.url()).pathname).toBe(`/admin/courses/${race.id}`);
    await expect(page.locator('li.card').filter({ hasText: runner.name })).toBeVisible();

    // Rechargement : identifiants ADMIN perdus, « Page introuvable » à la même adresse.
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable');
    expect(new URL(page.url()).pathname).toBe(`/admin/courses/${race.id}`);
    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable');

    // Nouvelle connexion par /connexion : l'accès est rendu.
    await loginAdminFromConnexion(page);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');
    await expect(page.locator('li.card').filter({ hasText: race.name })).toBeVisible();
  });
});

/**
 * CA8 (inc. 6) — Identifiants ADMIN refusés pendant une action (CL4). Sur `/admin/courses/{id}`, la prochaine
 * réponse E13 est remplacée par un 401 : l'écran de connexion s'affiche avec « Session expirée… » (RG10 inc. 4),
 * et non « Page introuvable ». L'action qui déclenche le rechargement est « Modifier » puis « Enregistrer » (E10,
 * suivi du rechargement des coureurs par E13).
 */
test.describe('@INC-6 @INC6-CA8 Identifiants ADMIN refusés pendant une action', () => {
  test.use({ serviceWorkers: 'block' });

  test('401 sur E13 : écran de connexion avec « Session expirée »', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I6C8-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const runner = await api.register(race.id, 'Coureur Expire');

    await loginAdmin(page, `/admin/courses/${race.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(race.name);
    await expect(page.locator('li.card').filter({ hasText: runner.name })).toBeVisible();

    let intercepted = 0;
    await page.route(E13, async (route) => {
      intercepted += 1;
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', detail: 'Identifiants invalides' }),
      });
    });
    await page.getByRole('button', { name: 'Modifier', exact: true }).first().click();
    await page.getByRole('button', { name: 'Enregistrer' }).click();

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    await expect(page.getByText('Session expirée', { exact: false })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Page introuvable' })).toHaveCount(0);
    expect(intercepted).toBeGreaterThanOrEqual(1);
    const url = new URL(page.url());
    expect(url.pathname).toBe('/connexion');
    expect(url.searchParams.get('retour')).toBe(`/admin/courses/${race.id}`);
  });
});
