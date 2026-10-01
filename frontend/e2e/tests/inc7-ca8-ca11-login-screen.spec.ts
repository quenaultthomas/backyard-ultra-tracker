import { expect, test, type Page } from '@playwright/test';
import { Api, basicAuth, DEFAULT_RUNNER_PASSWORD, SCANNER_PASSWORD, SCANNER_USERNAME, uniqueRun } from '../fixtures/api';
import { credentialSlots } from '../fixtures/admin-separation';
import { storageDump } from '../fixtures/storage';
import { chooseStaffEntry, expectAccountPage, submitLogin } from '../fixtures/ui';

const E19 = '/api/scan/me';
const E21 = '/api/account/me';
const FAILURE = 'Identifiants invalides';
const HINT = 'Vérifiez le type de compte choisi';

/** Réponse du backend (statut) pour le chemin donné, déclenchée par `action`. */
async function responseStatus(page: Page, pathname: string, action: () => Promise<void>): Promise<number> {
  const [response] = await Promise.all([
    page.waitForResponse((r) => new URL(r.url()).pathname === pathname),
    action(),
  ]);
  return response.status();
}

/**
 * CA8 (inc. 7) — Écran de connexion unique : deux entrées, libellés par entrée, aucune mention de l'administration,
 * aucune requête `/api/` avant la soumission (RG1).
 */
test.describe('@INC-7 @smoke @INC7-CA8 Écran de connexion unique', () => {
  test.use({ serviceWorkers: 'block' });

  test('h1, deux entrées, libellés par entrée, aucun texte ni lien vers l\'administration, aucune requête avant soumission', async ({ page }) => {
    const apiRequests: string[] = [];
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.startsWith('/api/')) {
        apiRequests.push(request.url());
      }
    });
    await page.goto('/connexion');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    await page.waitForLoadState('networkidle');

    const choice = page.getByRole('group', { name: 'Je me connecte en tant que' });
    await expect(choice.getByRole('radio', { name: 'Coureur' })).toBeChecked();
    await expect(choice.getByRole('radio', { name: 'Bénévole' })).toBeVisible();
    await expect(choice.getByRole('radio', { name: 'Bénévole' })).not.toBeChecked();
    await expect(choice.getByRole('radio')).toHaveCount(2);

    // Entrée « Coureur » : « Pseudo » et la case sans mention « compte scanner ».
    await expect(page.getByLabel('Pseudo')).toBeVisible();
    await expect(page.getByLabel("Nom d'utilisateur")).toHaveCount(0);
    await expect(page.getByLabel('Rester connecté 24 h sur cet appareil', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Mot de passe')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Afficher' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
    await assertNoAdminMention(page, 'entrée Coureur');

    // Entrée « Bénévole » : « Nom d'utilisateur » et la case « (compte scanner uniquement) ».
    await choice.getByRole('radio', { name: 'Bénévole' }).check();
    await expect(choice.getByRole('radio', { name: 'Bénévole' })).toBeChecked();
    await expect(page.getByLabel("Nom d'utilisateur")).toBeVisible();
    await expect(page.getByLabel('Pseudo')).toHaveCount(0);
    await expect(page.getByLabel('Rester connecté 24 h sur cet appareil (compte scanner uniquement)', { exact: true }))
      .toBeVisible();
    await expect(page.getByLabel('Mot de passe')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Afficher' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
    await assertNoAdminMention(page, 'entrée Bénévole');

    expect(apiRequests, 'requêtes /api/ avant la soumission').toEqual([]);
  });
});

/** Ni « Administration » ni « administrateur » dans le texte visible, aucun `a[href]` vers `/admin`. */
async function assertNoAdminMention(page: Page, label: string): Promise<void> {
  const text = await page.evaluate(() => document.body.innerText);
  expect(text, `${label} : texte visible`).not.toMatch(/administration|administrateur/i);
  const adminLinks = await page.evaluate(() => Array.from(document.querySelectorAll('a[href]'))
    .map((el) => new URL((el as HTMLAnchorElement).href).pathname)
    .filter((path) => path === '/admin' || path.startsWith('/admin/')));
  expect(adminLinks, `${label} : liens /admin`).toEqual([]);
}

/**
 * CA9 (inc. 7) — Orientation par profil (RG2, CL4) : coureur -> `/compte`, bénévole -> `/scan`, admin -> `/admin`,
 * et l'ADMIN avec `retour=/scan` revient sur `/scan`.
 */
test.describe('@INC-7 @smoke @INC7-CA9 Orientation par profil', () => {
  test.use({ serviceWorkers: 'block' });

  test('(a) « Coureur » + Lievre : E21 200, arrivée sur /compte', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C9A-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);

    await page.goto('/connexion');
    await expect(page.getByRole('radio', { name: 'Coureur' })).toBeChecked();
    const status = await responseStatus(page, E21, () => submitLogin(page, 'runner', typed, DEFAULT_RUNNER_PASSWORD));
    expect(status).toBe(200);
    await expectAccountPage(page, `lievre-${run}`);
    expect(new URL(page.url()).pathname).toBe('/compte');
    await expect(page.getByRole('heading', { level: 2, name: race.name })).toBeVisible();
  });

  test('(b) « Bénévole » + scanner-test : E19 200, arrivée sur /scan', async ({ page }) => {
    await page.goto('/connexion');
    await chooseStaffEntry(page);
    const status = await responseStatus(page, E19, () => submitLogin(page, 'staff', SCANNER_USERNAME, SCANNER_PASSWORD));
    expect(status).toBe(200);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    expect(new URL(page.url()).pathname).toBe('/scan');
    await expect(page.getByText('Non connecté : envoi suspendu')).toHaveCount(0);
  });

  test('(c) « Bénévole » + admin-test : E19 200, arrivée sur /admin qui liste les courses', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I7C9C-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });

    await page.goto('/connexion');
    await chooseStaffEntry(page);
    const status = await responseStatus(page, E19, () => submitLogin(page, 'staff', 'admin-test', 'admin-secret'));
    expect(status).toBe(200);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');
    expect(new URL(page.url()).pathname).toBe('/admin');
    await expect(page.locator('li.card').filter({ hasText: race.name })).toBeVisible();
  });

  test('(d) retour=/scan : l\'ADMIN revient sur /scan', async ({ page }) => {
    await page.goto(`/connexion?retour=${encodeURIComponent('/scan')}`);
    await expect(page.getByRole('radio', { name: 'Bénévole' })).toBeChecked();
    const status = await responseStatus(page, E19, () => submitLogin(page, 'staff', 'admin-test', 'admin-secret'));
    expect(status).toBe(200);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    expect(new URL(page.url()).pathname).toBe('/scan');
  });
});

/**
 * CA10 (inc. 7) — Mauvaise entrée (CL1, CL2, CL3) : 401 « Identifiants invalides » avec l'aide neutre, rien conservé,
 * message identique que l'identifiant existe ou non dans l'autre référentiel (D1-bis).
 */
test.describe('@INC-7 @smoke @INC7-CA10 Mauvaise entrée', () => {
  test.use({ serviceWorkers: 'block' });

  test('(a) Lievre sur « Bénévole » : E19 401, message et aide, rien conservé, /scan redemande une connexion', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C10A-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);

    await page.goto('/connexion');
    await chooseStaffEntry(page);
    const status = await responseStatus(page, E19,
      () => submitLogin(page, 'staff', typed, DEFAULT_RUNNER_PASSWORD, { remember: true }));
    expect(status).toBe(401);
    await expect(page.getByRole('alert')).toHaveText(FAILURE);
    await expect(page.getByText(HINT)).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');

    const slots = await credentialSlots(page);
    expect(slots.scanner, 'emplacement bénévole').toBeNull();
    expect(slots.runner, 'emplacement coureur').toBeNull();
    const dump = await storageDump(page);
    expect(dump).not.toContain(DEFAULT_RUNNER_PASSWORD);
    expect(dump).not.toContain(basicAuth(typed, DEFAULT_RUNNER_PASSWORD).replace('Basic ', ''));

    // /scan redemande une connexion (navigation interne : rien n'est gardé en mémoire).
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Scan', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await expect(page.getByText('Non connecté : envoi suspendu')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Se connecter' })).toBeVisible();
  });

  test('(b) scanner-test sur « Coureur » : E21 401, même message, aucun mot de passe ni Basic stocké', async ({ page }) => {
    await page.goto('/connexion');
    await expect(page.getByRole('radio', { name: 'Coureur' })).toBeChecked();
    const status = await responseStatus(page, E21,
      () => submitLogin(page, 'runner', SCANNER_USERNAME, SCANNER_PASSWORD, { remember: true }));
    expect(status).toBe(401);
    await expect(page.getByRole('alert')).toHaveText(FAILURE);
    await expect(page.getByText(HINT)).toBeVisible();

    const slots = await credentialSlots(page);
    expect(slots.runner, 'emplacement coureur').toBeNull();
    expect(slots.scanner, 'emplacement bénévole').toBeNull();
    const dump = await storageDump(page);
    expect(dump).not.toContain(SCANNER_PASSWORD);
    expect(dump).not.toContain(basicAuth(SCANNER_USERNAME, SCANNER_PASSWORD).replace('Basic ', ''));
    expect(await page.context().cookies()).toEqual([]);
  });

  test('(c) message et aide identiques pour un identifiant inconnu et pour un identifiant de l\'autre référentiel', async ({ page }) => {
    await page.goto('/connexion');
    const observed: { message: string | null; hint: boolean }[] = [];
    for (const identifier of [`inconnu-${uniqueRun()}`, SCANNER_USERNAME]) {
      await page.getByLabel('Pseudo').fill(identifier);
      await page.getByLabel('Mot de passe').fill(SCANNER_PASSWORD);
      const status = await responseStatus(page, E21, () => page.getByRole('button', { name: 'Se connecter' }).click());
      expect(status).toBe(401);
      await expect(page.getByRole('alert')).toBeVisible();
      observed.push({
        message: await page.getByRole('alert').textContent(),
        hint: await page.getByText(HINT).isVisible(),
      });
    }
    expect(observed[0]).toEqual({ message: FAILURE, hint: true });
    expect(observed[1]).toEqual(observed[0]);
  });
});

/**
 * CA11 (inc. 7) — Indépendance des emplacements (RG2) : connexion coureur puis bénévole dans le même contexte ;
 * `/compte` affiche toujours les inscriptions, `/scan` envoie avec le Basic bénévole, la déconnexion coureur
 * n'efface pas la connexion bénévole.
 */
test.describe('@INC-7 @smoke @INC7-CA11 Indépendance des emplacements', () => {
  test.use({ serviceWorkers: 'block' });

  test('coureur puis bénévole : /compte intact, scan avec le Basic bénévole, déconnexion coureur sans effet sur le bénévole', async ({ page }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C11-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const lievre = await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);
    await api.startRace(race.id);

    // Connexion coureur (en mémoire), puis connexion bénévole depuis /scan : navigation interne, rien n'est rechargé.
    await page.goto('/connexion');
    await submitLogin(page, 'runner', typed, DEFAULT_RUNNER_PASSWORD);
    await expectAccountPage(page, pseudo);
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Scan', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await expect(page.getByRole('radio', { name: 'Bénévole' })).toBeChecked();
    await submitLogin(page, 'staff', SCANNER_USERNAME, SCANNER_PASSWORD);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await expect(page.getByText('Non connecté : envoi suspendu')).toHaveCount(0);

    // /scan envoie avec le Basic bénévole.
    const scanRequest = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/scan/passages'
      && r.method() === 'POST');
    await page.getByLabel('Code du QR').fill(lievre.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    expect((await scanRequest).headers()['authorization']).toBe(basicAuth(SCANNER_USERNAME, SCANNER_PASSWORD));
    await expect(page.getByText(`Dossard ${lievre.bib} — ${lievre.name}`, { exact: false })).toBeVisible();

    // /compte affiche toujours les inscriptions de Lievre.
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Mes inscriptions', exact: true }).click();
    await expectAccountPage(page, pseudo);
    await expect(page.getByRole('heading', { level: 2, name: race.name })).toBeVisible();

    // La déconnexion coureur (sur /compte) n'efface pas la connexion bénévole.
    await page.getByRole('button', { name: 'Se déconnecter' }).click();
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Scan', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await expect(page.getByText('Non connecté : envoi suspendu')).toHaveCount(0);
    await expect(page.getByText('scanner', { exact: true })).toBeVisible();
    const slots = await credentialSlots(page);
    expect(slots.runner, 'emplacement coureur après déconnexion').toBeNull();
  });
});
