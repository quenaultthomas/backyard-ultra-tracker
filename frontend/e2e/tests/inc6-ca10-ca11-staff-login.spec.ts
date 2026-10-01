import { expect, test } from '@playwright/test';
import { Api, basicAuth, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { ANONYMOUS_LOGIN_SCREEN_LINKS, auditNoAdminLink, credentialSlots, spaNavigate } from '../fixtures/admin-separation';
import { expectAccountPage, loginRunner } from '../fixtures/ui';
import { storageDump } from '../fixtures/storage';

/**
 * CA10 (inc. 6) — Identifiants coureur saisis sur l'écran de connexion staff (RG6, CL7, PO15 inc. 5). Connecté
 * `Lievre-{run}`, on suit « Scan » depuis l'accueil puis « Se connecter » et on saisit ses identifiants de coureur :
 * E19 répond 401, « Identifiants invalides » s'affiche, `/scan` redemande une connexion, `/compte` affiche encore
 * les inscriptions sans nouvelle connexion, et rien n'est conservé dans l'emplacement ADMIN/SCANNER (IndexedDB,
 * clé `scanner`), tandis que l'emplacement coureur (clé `runner`) est inchangé.
 */
test.describe('@INC-6 @smoke @INC6-CA10 Identifiants coureur refusés sur la connexion staff', () => {
  test.use({ serviceWorkers: 'block' });

  test('E19 401, « Identifiants invalides », rien conservé côté staff, /compte intact', async ({ page }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I6C10-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);

    // Connexion coureur mémorisée (emplacement `runner` d'IndexedDB), puis « Scan » depuis l'accueil.
    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD, { remember: true });
    await expectAccountPage(page, pseudo);
    const before = await credentialSlots(page);
    expect(before.runner, 'emplacement coureur avant').not.toBeNull();
    expect(before.scanner, 'emplacement staff avant').toBeNull();

    await page.getByRole('link', { name: 'Backyard Ultra Tracker' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');
    await page.getByRole('main').getByRole('link', { name: 'Scan', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');

    await page.getByLabel("Nom d'utilisateur").fill(typed);
    await page.getByLabel('Mot de passe').fill(DEFAULT_RUNNER_PASSWORD);
    await page.getByLabel('Rester connecté 24 h', { exact: false }).check();
    const [response] = await Promise.all([
      page.waitForResponse((r) => new URL(r.url()).pathname === '/api/scan/me'),
      page.getByRole('button', { name: 'Se connecter' }).click(),
    ]);
    expect(response.status()).toBe(401);
    await expect(page.getByText('Identifiants invalides')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');

    // Rien n'est conservé côté staff ; l'emplacement coureur est inchangé ; aucun mot de passe ni encodage Basic
    // ailleurs que dans l'emplacement coureur.
    const after = await credentialSlots(page);
    expect(after.scanner, 'emplacement staff après').toBeNull();
    // Emplacement coureur inchangé : mêmes identifiants (RG21 inc. 5) ; seule l'échéance glissante peut avancer.
    const { expiresAt: expiresBefore, ...runnerBefore } = before.runner as Record<string, unknown> & { expiresAt: number };
    const { expiresAt: expiresAfter, ...runnerAfter } = after.runner as Record<string, unknown> & { expiresAt: number };
    expect(runnerAfter, 'emplacement coureur après').toEqual(runnerBefore);
    expect(expiresAfter).toBeGreaterThanOrEqual(expiresBefore);
    const outsideRunnerSlot = (await storageDump(page)).split(JSON.stringify(after.runner)).join('');
    expect(outsideRunnerSlot).not.toContain(DEFAULT_RUNNER_PASSWORD);
    for (const username of [typed, pseudo]) {
      expect(outsideRunnerSlot).not.toContain(basicAuth(username, DEFAULT_RUNNER_PASSWORD).replace('Basic ', ''));
    }

    // Aucun accès au scan : /scan redemande une connexion (navigation interne : rien n'est gardé en mémoire).
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Scan', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await expect(page.getByText('Non connecté : envoi suspendu')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Se connecter' })).toBeVisible();

    // /compte affiche toujours les inscriptions, sans nouvelle connexion (navigation interne, puis complète).
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Mes inscriptions', exact: true }).click();
    await expectAccountPage(page, pseudo);
    await expect(page.getByRole('heading', { level: 2, name: race.name })).toBeVisible();
    await page.goto('/scan');
    await expect(page.getByText('Non connecté : envoi suspendu')).toBeVisible();
    await page.goto('/compte');
    await expectAccountPage(page, pseudo);
  });
});

/**
 * CA11 (inc. 6) — Connexion ADMIN depuis `/scan` (RG3, RG7, CL8, CL9). L'écran de connexion atteint depuis le
 * lien « Scan » ne mentionne pas l'administration ; l'ADMIN s'y connecte, revient sur `/scan` sans lien ni mention
 * de l'administration, y envoie une capture (200), puis ouvre `/admin` par son URL. L'ouverture par URL se fait par
 * le routeur (équivalent de l'historique du navigateur, CL9), car un rechargement complet perdrait les
 * identifiants ADMIN, gardés en mémoire seulement (RG7 inc. 4, CA5).
 */
test.describe('@INC-6 @smoke @INC6-CA11 Connexion ADMIN depuis /scan', () => {
  test.use({ serviceWorkers: 'block' });

  test('écran de connexion neutre, retour sur /scan, capture 200, /admin par son URL', async ({ page }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I6C11-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const runner = await api.register(race.id, 'Coureur Scan');
    await api.startRace(race.id);

    await page.goto('/');
    await page.getByRole('main').getByRole('link', { name: 'Scan', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    expect(new URL(page.url()).searchParams.get('retour')).toBe('/scan');

    // Écran de connexion : ni « Administration » ni « administrateur », aucun lien /admin, en-tête compris.
    // Inc. 7 (RG4, catégorie A) : l'en-tête propose « Créer un compte » (seul lien admis en plus), aucun /admin.
    await auditNoAdminLink(page, 'connexion depuis /scan', ANONYMOUS_LOGIN_SCREEN_LINKS);
    expect(await page.evaluate(() => document.body.innerText)).not.toMatch(/administrateur/i);

    await page.getByLabel("Nom d'utilisateur").fill('admin-test');
    await page.getByLabel('Mot de passe').fill('admin-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    expect(new URL(page.url()).pathname).toBe('/scan');
    await expect(page.getByText('Non connecté : envoi suspendu')).toHaveCount(0);

    // /scan connecté en ADMIN : aucun lien ni texte vers l'administration (le lien « Se connecter » a disparu).
    // Connexion staff sans effet sur l'en-tête (RG4) : « Créer un compte » seulement, le bandeau « Se connecter » a disparu.
    await auditNoAdminLink(page, '/scan connecté en ADMIN', ANONYMOUS_LOGIN_SCREEN_LINKS);
    expect(await page.evaluate(() => document.body.innerText)).not.toMatch(/administrateur/i);

    const [response] = await Promise.all([
      page.waitForResponse((r) => r.url().endsWith('/api/scan/passages') && r.request().method() === 'POST'),
      (async () => {
        await page.getByLabel('Code du QR').fill(runner.qrToken);
        await page.getByRole('button', { name: 'Valider' }).click();
      })(),
    ]);
    expect(response.status()).toBe(200);
    await expect(page.getByText(`Dossard ${runner.bib} — ${runner.name}`, { exact: false })).toBeVisible();

    // /admin par son URL (routeur, identifiants ADMIN conservés en mémoire) : la liste des courses.
    await spaNavigate(page, '/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');
    await expect(page.locator('li.card').filter({ hasText: race.name })).toBeVisible();
  });
});
