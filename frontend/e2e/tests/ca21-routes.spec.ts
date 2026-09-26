import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA21 — Routes et liens directs (RG5, RG45). Chaque route de la liste fermée s'ouvre directement (lien,
 * favori, rechargement), dans un contexte neuf (sans service worker installé au préalable).
 */
test.describe('@INC-4 @INC4-CA21 Routes et liens directs', () => {
  let raceId: number;
  let runnerId: number;

  test.beforeAll(async ({}, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-ROUTES-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    raceId = race.id;
    const registration = await api.register(raceId, 'Alice Routes');
    runnerId = registration.runnerId;
  });

  const staticRoutes: readonly { path: string; heading: string }[] = [
    { path: '/', heading: 'Courses' },
    { path: '/connexion', heading: 'Connexion' },
    { path: '/scan', heading: 'Scan' },
  ];

  for (const route of staticRoutes) {
    test(`${route.path} s'affiche puis résiste au rechargement`, async ({ page }) => {
      await page.goto(route.path);
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(route.heading);
      await page.reload();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(route.heading);
    });
  }

  test('/courses/{id} affiche le tableau de bord', async ({ page }) => {
    await page.goto(`/courses/${raceId}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('E2E-ROUTES');
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('E2E-ROUTES');
  });

  test('/coureurs/{id} affiche le détail du coureur', async ({ page }) => {
    await page.goto(`/coureurs/${runnerId}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Alice Routes');
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Alice Routes');
  });

  test('/inscription/{id} affiche le formulaire', async ({ page }) => {
    await page.goto(`/inscription/${raceId}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Inscription');
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Inscription');
  });

  test('/admin sans connexion affiche l\'écran de connexion', async ({ page }) => {
    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
  });

  test('/admin/courses/{id}/qr sans connexion affiche l\'écran de connexion', async ({ page }) => {
    await page.goto(`/admin/courses/${raceId}/qr`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
  });

  test('route inconnue sous /courses/{id} affiche Page introuvable', async ({ page }) => {
    await page.goto(`/courses/${raceId}/inconnu`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable');
    await expect(page.getByRole('link', { name: "Retour à l'accueil" })).toBeVisible();
  });

  /**
   * BUG-1 (voir docs/tests/rapports/INC-4-e2e.md, section 4) : l'ouverture directe anonyme de `/admin/inconnu`
   * est explicitement attendue par CA21 ("L'ouverture directe de ... /admin/inconnu affiche « Page
   * introuvable » avec un lien vers l'accueil"). Le résultat observé est un renvoi vers `/connexion` : le
   * garde `requireSignedIn`, posé sur la route parente `admin`, s'applique aussi à son enfant `**` (page non
   * trouvée), qui ne devrait pourtant nécessiter aucune connexion. Assertion conforme à la spec, laissée en
   * échec (non contournée) pour porter le constat à la connaissance de l'agent fonctionnel.
   */
  test('route inconnue sous /admin affiche Page introuvable, même sans connexion (BUG-1)', async ({ page }) => {
    await page.goto('/admin/inconnu');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable');
    await expect(page.getByRole('link', { name: "Retour à l'accueil" })).toBeVisible();
  });

  /**
   * Hors liste fermée de RG53 (ex. `/nimporte-quoi`) : la spec (section « Routes de la PWA ») précise que ce
   * chemin, demandé directement au serveur, reste refusé par le serveur (RG53), et non affiché par la route
   * `**` de l'application (qui ne s'atteint que sous les préfixes ouverts). Confirmation depuis le navigateur,
   * en complément de la vérification serveur déjà faite par l'agent test-integration-backend (CA3).
   */
  test('chemin hors de la liste fermée RG53 (/nimporte-quoi) reste refusé par le serveur', async ({ page }) => {
    const response = await page.goto('/nimporte-quoi');
    expect(response?.status()).toBe(401);
  });
});
