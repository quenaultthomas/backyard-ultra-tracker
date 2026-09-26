import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/** CA24 — Connexion et rôles 401/403 (RG6, RG10, RG36). */
test.describe('@INC-4 @INC4-CA24 Connexion et rôles', () => {
  // Service worker désactivé : l'interception réseau du test (page.route) doit voir la requête de scan, pas
  // le service worker de l'application, qui la relaierait avant que Playwright ne puisse la remplacer.
  test.use({ serviceWorkers: 'block' });

  test('/admin sans connexion : écran de connexion, aucune requête /api/admin/**', async ({ page }) => {
    let adminRequests = 0;
    page.on('request', (request) => {
      if (request.url().includes('/api/admin/')) {
        adminRequests += 1;
      }
    });
    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    expect(adminRequests).toBe(0);
  });

  test('identifiants admin invalides : "Identifiants invalides", rien en stockage', async ({ page }) => {
    await page.goto('/connexion');
    await page.getByLabel("Nom d'utilisateur").fill('admin-test');
    await page.getByLabel('Mot de passe').fill('mauvais');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByText('Identifiants invalides')).toBeVisible();

    const storage = await page.evaluate(async () => {
      const local = JSON.stringify(localStorage);
      const cookies = document.cookie;
      const dbs = await indexedDB.databases();
      return { local, cookies, dbs };
    });
    expect(storage.local).not.toContain('admin-secret');
    expect(storage.local).not.toContain('mauvais');
    expect(storage.cookies).toBe('');
  });

  /**
   * Un compte SCANNER n'est mémorisé qu'en mémoire par défaut (RG7) : une ouverture directe de `/admin`
   * (navigation complète du navigateur) efface donc cette mémoire, comme n'importe quel rechargement. Pour
   * observer RG10 (« un compte SCANNER qui ouvre /admin/** ») sans perdre la session à la navigation, ce test
   * utilise « Rester connecté 24 h » (RG7), qui persiste le rôle SCANNER dans le stockage local et le restaure
   * avant que le garde de route ne s'exécute.
   */
  test('connexion scanner (mémorisée) puis accès admin refusé sans donnée admin', async ({ page }) => {
    const adminRequests: number[] = [];
    page.on('response', (response) => {
      if (response.url().includes('/api/admin/')) {
        adminRequests.push(response.status());
      }
    });
    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByLabel('Rester connecté 24 h', { exact: false }).check();
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByText('scanner', { exact: true })).toBeVisible();

    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Accès réservé à l\'administrateur');
    await expect(page.getByText('Gérer les courses', { exact: false })).toHaveCount(0);
    for (const status of adminRequests) {
      expect(status).toBe(403);
    }
  });

  test('connexion admin : la liste des courses admin s\'affiche', async ({ page }) => {
    await page.goto('/connexion');
    await page.getByLabel("Nom d'utilisateur").fill('admin-test');
    await page.getByLabel('Mot de passe').fill('admin-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');
  });

  test('un 401 sur E6 suspend la file sans perte, reprise après reconnexion', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-401SCAN-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const registration = await api.register(race.id, 'Suspend Scan');
    await api.startRace(race.id);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    // La prochaine réponse de E6 est remplacée par un 401 (interception côté navigateur, RG57.6).
    let intercepted = false;
    await page.route('**/api/scan/passages', async (route) => {
      if (!intercepted) {
        intercepted = true;
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', detail: 'Authentification requise' }),
        });
        return;
      }
      await route.continue();
    });

    await page.getByLabel('Code du QR').fill(registration.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    await expect(page.getByText('Session expirée', { exact: false })).toBeVisible();

    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    // Reprise automatique : accepté sans nouvelle capture.
    await expect(page.getByText(`Dossard ${registration.bib}`, { exact: false })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('0 en attente')).toBeVisible();
  });
});
