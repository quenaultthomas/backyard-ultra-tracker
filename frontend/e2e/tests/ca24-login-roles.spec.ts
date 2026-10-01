import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';
import { chooseStaffEntry } from '../fixtures/ui';

/** CA24 — Connexion et rôles 401/403 (RG6, RG10, RG36). */
test.describe('@INC-4 @smoke @INC4-CA24 Connexion et rôles', () => {
  // Service worker désactivé : l'interception réseau du test (page.route) doit voir la requête de scan, pas
  // le service worker de l'application, qui la relaierait avant que Playwright ne puisse la remplacer.
  test.use({ serviceWorkers: 'block' });

  // Évolution inc. 6 (adaptation B, RG3 inc. 6) : « Page introuvable » au lieu de l'écran de connexion ; l'absence de
  // requête /api/admin/** est conservée.
  test('/admin sans connexion : Page introuvable (inc. 6), aucune requête /api/admin/**', async ({ page }) => {
    let adminRequests = 0;
    page.on('request', (request) => {
      if (request.url().includes('/api/admin/')) {
        adminRequests += 1;
      }
    });
    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable');
    expect(adminRequests).toBe(0);
  });

  test('identifiants admin invalides : "Identifiants invalides", rien en stockage', async ({ page }) => {
    await page.goto('/connexion');
    await chooseStaffEntry(page); // inc. 7 (RG1) : catégorie A, entrée « Bénévole » à choisir avant de remplir
    await page.getByLabel("Nom d'utilisateur").fill('admin-test');
    await page.getByLabel('Mot de passe').fill('mauvais');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByText('Identifiants invalides')).toBeVisible();

    // Écart mineur comblé (reprise du 2026-09-27) : sessionStorage, contenu d'IndexedDB (pas seulement la
    // liste des bases) et Cache Storage sont désormais inspectés, en plus de localStorage et des cookies.
    const dump = await page.evaluate(async () => {
      const parts: string[] = [JSON.stringify(localStorage), JSON.stringify(sessionStorage), document.cookie];
      const dbNames = (await indexedDB.databases()).map((db) => db.name).filter((name): name is string => !!name);
      for (const name of dbNames) {
        try {
          const db = await new Promise<IDBDatabase>((resolve, reject) => {
            const request = indexedDB.open(name);
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
          });
          for (const storeName of Array.from(db.objectStoreNames)) {
            const records = await new Promise<unknown[]>((resolve, reject) => {
              const tx = db.transaction(storeName, 'readonly');
              const out: unknown[] = [];
              const cursorReq = tx.objectStore(storeName).openCursor();
              cursorReq.onsuccess = () => {
                const cursor = cursorReq.result;
                if (cursor !== null) {
                  out.push(cursor.value);
                  cursor.continue();
                }
              };
              tx.oncomplete = () => resolve(out);
              tx.onerror = () => reject(tx.error);
            });
            parts.push(JSON.stringify(records));
          }
        } catch {
          // Base inaccessible : rien à ajouter.
        }
      }
      try {
        const cacheNames = await caches.keys();
        const cacheParts: string[] = [];
        for (const cacheName of cacheNames) {
          const cache = await caches.open(cacheName);
          const requests = await cache.keys();
          for (const request of requests) {
            const response = await cache.match(request);
            cacheParts.push(`${request.url}::${response ? await response.text() : ''}`);
          }
        }
        parts.push(cacheParts.join(';'));
      } catch {
        // Cache Storage indisponible : rien à ajouter.
      }
      return parts.join('|');
    });
    expect(dump).not.toContain('admin-secret');
    expect(dump).not.toContain('mauvais');
    expect(dump).not.toContain('YWRtaW4tdGVzdDphZG1pbi1zZWNyZXQ=');
    expect(await page.context().cookies()).toEqual([]);
  });

  /**
   * Un compte SCANNER n'est mémorisé qu'en mémoire par défaut (RG7) : une ouverture directe de `/admin`
   * (navigation complète du navigateur) efface donc cette mémoire, comme n'importe quel rechargement. Pour
   * observer RG10 (« un compte SCANNER qui ouvre /admin/** ») sans perdre la session à la navigation, ce test
   * utilise « Rester connecté 24 h » (RG7), qui persiste le rôle SCANNER dans le stockage local et le restaure
   * avant que le garde de route ne s'exécute.
   */
  // Évolution inc. 6 (adaptation B, RG3 inc. 6) : « Page introuvable » au lieu de « Accès réservé à l'administrateur » ;
  // « Gérer les courses » absent et statuts /api/admin/** éventuels à 403 conservés (aucune requête n'est même émise).
  test('connexion scanner (mémorisée) puis /admin : Page introuvable (inc. 6) sans donnée admin', async ({ page }) => {
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
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable');
    await expect(page.getByText('Gérer les courses', { exact: false })).toHaveCount(0);
    // R6-2 : aucune requête /api/admin/** n'est émise pour un non-admin (la boucle ci-dessous serait vide sinon).
    expect(adminRequests).toEqual([]);
    for (const status of adminRequests) {
      expect(status).toBe(403);
    }
  });

  test('connexion admin : la liste des courses admin s\'affiche', async ({ page }) => {
    await page.goto('/connexion');
    await chooseStaffEntry(page); // inc. 7 (RG1) : catégorie A
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
