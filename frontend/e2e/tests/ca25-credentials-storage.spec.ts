import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/** CA25 — Stockage des identifiants (RG7, RG9). */
test.describe('@INC-4 @INC4-CA25 Stockage des identifiants', () => {
  // Le dernier test simule une coupure réseau (context.setOffline) : le service worker de l'application,
  // dont le fetch s'exécute hors du contexte réseau de la page, n'est pas soumis à cette émulation sous
  // Chromium/Playwright (limite d'outillage constatée, voir docs/tests/rapports/INC-4-e2e.md, LIM-E2E-1) ;
  // sans quoi la capture serait faussement acceptée en ligne.
  test.use({ serviceWorkers: 'block' });

  async function storageDump(page: import('@playwright/test').Page): Promise<string> {
    return page.evaluate(async () => {
      const parts: string[] = [JSON.stringify(localStorage), JSON.stringify(sessionStorage), document.cookie];
      try {
        const db = await new Promise<IDBDatabase>((resolve, reject) => {
          const request = indexedDB.open('backyard-ultra-tracker');
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error);
        });
        const stores = Array.from(db.objectStoreNames);
        for (const storeName of stores) {
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
        // Base absente : rien à ajouter.
      }
      return parts.join('|');
    });
  }

  test('connexion ADMIN : ni le mot de passe ni le Basic ne sont stockés ; reconnexion redemandée', async ({ page }) => {
    await page.goto('/connexion');
    await page.getByLabel("Nom d'utilisateur").fill('admin-test');
    await page.getByLabel('Mot de passe').fill('admin-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');

    const dump = await storageDump(page);
    expect(dump).not.toContain('admin-secret');
    expect(dump).not.toContain('YWRtaW4tdGVzdDphZG1pbi1zZWNyZXQ=');

    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
  });

  test('connexion SCANNER sans "Rester connecté" : rien stocké, reconnexion redemandée', async ({ page }) => {
    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    const dump = await storageDump(page);
    expect(dump).not.toContain('c2Nhbm5lci10ZXN0OnNjYW5uZXItc2VjcmV0');

    await page.reload();
    await expect(page.locator('.indicator').filter({ hasText: 'Non connecté' })).toBeVisible();
  });

  test('connexion SCANNER avec "Rester connecté" : session conservée après rechargement', async ({ page }) => {
    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByLabel('Rester connecté 24 h', { exact: false }).check();
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await page.reload();
    await expect(page.getByText('scanner', { exact: true })).toBeVisible();
  });

  test('"Se déconnecter" efface tout stockage', async ({ page }) => {
    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByLabel('Rester connecté 24 h', { exact: false }).check();
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await page.getByRole('button', { name: 'Se déconnecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');

    const dump = await storageDump(page);
    expect(dump).not.toContain('c2Nhbm5lci10ZXN0OnNjYW5uZXItc2VjcmV0');
  });

  test('déconnexion avec 2 scans en attente : confirmation, annulation garde la session', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-LOGOUTQ-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const alice = await api.register(race.id, 'Alice Logout');
    const bob = await api.register(race.id, 'Bob Logout');
    await api.startRace(race.id);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await page.context().setOffline(true);
    await page.getByLabel('Code du QR').fill(alice.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await page.getByLabel('Code du QR').fill(bob.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('2 en attente')).toBeVisible();

    // Le bouton déclencheur (hors dialogue) et le bouton de confirmation (dans <app-confirm-dialog>) portent
    // tous deux le texte « Se déconnecter » ; un <dialog> fermé reste résolvable par rôle même masqué, d'où
    // ces locators précis plutôt qu'une recherche par rôle non ambiguë seulement une fois le dialogue ouvert.
    const triggerButton = page.locator('app-logout-button > button');
    const logoutDialog = page.locator('app-confirm-dialog');
    await triggerButton.click();
    await expect(page.getByText('scans en attente ne seront envoyés', { exact: false })).toBeVisible();
    await logoutDialog.getByRole('button', { name: 'Annuler' }).click();
    await expect(page.getByText('scanner', { exact: true })).toBeVisible();

    await triggerButton.click();
    await logoutDialog.getByRole('button', { name: 'Se déconnecter', exact: true }).click();
    await expect(page.locator('.indicator').filter({ hasText: 'Non connecté' })).toBeVisible();
    await expect(page.getByText('2 en attente')).toBeVisible();
    await page.context().setOffline(false);
  });
});
