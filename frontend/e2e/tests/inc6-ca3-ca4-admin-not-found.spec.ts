import { expect, test, type Page } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { trackAdminApi, trackAdminChunks } from '../fixtures/admin-separation';
import { expectAccountPage, loginRunner } from '../fixtures/ui';

/**
 * CA3 et CA4 (inc. 6) — `/admin/**` pour un non-admin (RG3, RG4, CL2, CL3). Anonyme, coureur connecté ou SCANNER :
 * « Page introuvable » avec un lien vers l'accueil, sans formulaire, sans redirection vers `/connexion`, sans
 * requête `/api/admin/**` ni bloc admin téléchargé. Le service worker est bloqué (journal réseau fiable, LIM-E2E-1) ;
 * le préchargement par le service worker relève de CA6.
 */
test.describe('@INC-6 @smoke @INC6-CA3 /admin/** pour un anonyme', () => {
  test.use({ serviceWorkers: 'block' });

  test('« Page introuvable », adresse inchangée, aucune requête admin', async ({ page, context }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I6C3-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const adminApi = trackAdminApi(context);
    const adminChunks = trackAdminChunks(context);

    const paths = ['/admin', `/admin/courses/${race.id}`, `/admin/courses/${race.id}/qr`, '/admin/comptes',
      '/admin/inconnu'];
    for (const path of paths) {
      await page.goto(path);
      await expectNotFound(page, path, path);
    }
    expect(adminApi.urls(), 'requêtes /api/admin/**').toEqual([]);
    expect(adminChunks.urls(), 'blocs admin téléchargés').toEqual([]);
  });
});

test.describe('@INC-6 @INC6-CA4 /admin/** pour un coureur et pour un SCANNER', () => {
  test.use({ serviceWorkers: 'block' });

  test('coureur connecté, puis SCANNER : « Page introuvable » ; scan et « Mes inscriptions » fonctionnent', async ({
    page, context,
  }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I6C4-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const lievre = await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);
    await api.startRace(race.id);
    const adminApi = trackAdminApi(context);
    const adminChunks = trackAdminChunks(context);

    // Coureur connecté (mémorisé 24 h pour survivre aux navigations complètes) : traité comme un anonyme.
    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD, { remember: true });
    await expectAccountPage(page, pseudo);
    await page.goto('/admin');
    await expectNotFound(page, '/admin (coureur)', '/admin');
    expect(adminApi.urls(), 'requêtes /api/admin/** (coureur)').toEqual([]);

    // SCANNER connecté depuis /scan (mémorisé 24 h), dans le même contexte que le coureur.
    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByLabel('Rester connecté 24 h', { exact: false }).check();
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await expect(page.getByText('scanner', { exact: true })).toBeVisible();

    await page.goto('/admin');
    await expectNotFound(page, '/admin (SCANNER)', '/admin');
    await expect(page.getByText('Accès réservé', { exact: false })).toHaveCount(0);
    await expect(page.getByText('Se connecter en administrateur', { exact: false })).toHaveCount(0);
    expect(adminApi.urls(), 'requêtes /api/admin/** (SCANNER)').toEqual([]);
    expect(adminChunks.urls(), 'blocs admin téléchargés').toEqual([]);

    // Dans ce même contexte : /scan fonctionne (capture reçue avec 200) et /compte garde les inscriptions.
    await page.goto('/scan');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    const [response] = await Promise.all([
      page.waitForResponse((r) => r.url().endsWith('/api/scan/passages') && r.request().method() === 'POST'),
      (async () => {
        await page.getByLabel('Code du QR').fill(lievre.qrToken);
        await page.getByRole('button', { name: 'Valider' }).click();
      })(),
    ]);
    expect(response.status()).toBe(200);
    await expect(page.getByText(`Dossard ${lievre.bib} — ${lievre.name}`, { exact: false })).toBeVisible();

    await page.goto('/compte');
    await expectAccountPage(page, pseudo);
    await expect(page.getByRole('heading', { level: 2, name: race.name })).toBeVisible();
  });
});

/** « Page introuvable » + lien vers l'accueil, ni formulaire de connexion, ni redirection (RG3). */
async function expectNotFound(page: Page, label: string, expectedPath: string): Promise<void> {
  await expect(page.getByRole('heading', { level: 1 }), `${label} : titre`).toHaveText('Page introuvable');
  await expect(page.getByRole('link', { name: "Retour à l'accueil" }), `${label} : lien vers l'accueil`).toBeVisible();
  await expect(page.locator('input[type="password"]'), `${label} : champ de mot de passe`).toHaveCount(0);
  await expect(page.getByLabel('Mot de passe'), `${label} : formulaire`).toHaveCount(0);
  const url = new URL(page.url());
  expect(url.pathname, `${label} : adresse`).toBe(expectedPath);
  expect(url.pathname, `${label} : redirection vers /connexion`).not.toBe('/connexion');
}
