import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, SCANNER_PASSWORD, SCANNER_USERNAME, uniqueRun } from '../fixtures/api';
import { chooseStaffEntry, expectAccountPage, loginAdmin, submitLogin } from '../fixtures/ui';

/**
 * CA16 (inc. 7) — Routes conservées (RG3) : `/compte/connexion` ouvre l'écran unique, « Coureur » sélectionnée même
 * avec `retour=/scan` ; `/compte` sans connexion renvoie vers `/compte/connexion` avec `retour=/compte`.
 */
test.describe('@INC-7 @smoke @INC7-CA16 Routes de connexion conservées', () => {
  test.use({ serviceWorkers: 'block' });

  test('/compte/connexion : écran unique, « Coureur » sélectionnée, y compris avec retour=/scan', async ({ page }) => {
    for (const url of ['/compte/connexion', `/compte/connexion?retour=${encodeURIComponent('/scan')}`]) {
      await page.goto(url);
      await expect(page.getByRole('heading', { level: 1 }), url).toHaveText('Connexion');
      await expect(page.getByRole('radio', { name: 'Coureur' }), url).toBeChecked();
      await expect(page.getByRole('radio', { name: 'Bénévole' }), url).not.toBeChecked();
      await expect(page.getByLabel('Pseudo'), url).toBeVisible();
    }
  });

  test('/compte sans connexion : renvoi vers /compte/connexion?retour=/compte, puis retour sur /compte après connexion', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C16-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);

    await page.goto('/compte');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    const redirected = new URL(page.url());
    expect(redirected.pathname).toBe('/compte/connexion');
    expect(redirected.searchParams.get('retour')).toBe('/compte');
    await expect(page.getByRole('radio', { name: 'Coureur' })).toBeChecked();

    await submitLogin(page, 'runner', typed, DEFAULT_RUNNER_PASSWORD);
    await expectAccountPage(page, `lievre-${run}`);
    expect(new URL(page.url()).pathname).toBe('/compte');
    await expect(page.getByRole('heading', { level: 2, name: race.name })).toBeVisible();
  });
});

/**
 * CA22 (inc. 7) — Accessibilité (axe-core WCAG 2.1 A et AA, aucune violation serious ou critical, même outil que
 * CA41 inc. 4) et présélection de l'entrée (RG1, RG3).
 */
test.describe('@INC-7 @smoke @INC7-CA22 Accessibilité et présélection', () => {
  test.use({ serviceWorkers: 'block' });

  async function assertNoSeriousViolations(page: Page, label: string): Promise<void> {
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(serious, `${label} : ${JSON.stringify(serious, null, 2)}`).toEqual([]);
  }

  test('(a) axe-core sur /connexion (Coureur puis Bénévole) et /inscription', async ({ page }) => {
    await page.goto('/connexion');
    await expect(page.getByRole('radio', { name: 'Coureur' })).toBeChecked();
    await assertNoSeriousViolations(page, '/connexion Coureur');
    await chooseStaffEntry(page);
    await assertNoSeriousViolations(page, '/connexion Bénévole');

    await page.goto('/inscription');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Créer un compte');
    await assertNoSeriousViolations(page, '/inscription');

    // États d'erreur de ces écrans (message d'échec et aide, erreurs de champs), eux aussi audités.
    await page.getByRole('button', { name: 'Créer mon compte' }).click();
    await expect(page.getByText('3 à 30 caractères', { exact: false })).toBeVisible();
    await assertNoSeriousViolations(page, '/inscription avec erreurs de champs');
    await page.goto('/connexion');
    await submitLogin(page, 'runner', `inconnu-${uniqueRun()}`, 'motdepasse-1');
    await expect(page.getByRole('alert')).toHaveText('Identifiants invalides');
    await assertNoSeriousViolations(page, '/connexion avec message d\'échec');
  });

  const cases: readonly { readonly url: string; readonly expected: 'Coureur' | 'Bénévole' }[] = [
    { url: `/connexion?retour=${encodeURIComponent('/scan')}`, expected: 'Bénévole' },
    { url: `/connexion?retour=${encodeURIComponent('/admin/comptes')}`, expected: 'Bénévole' },
    { url: '/connexion?profil=benevole', expected: 'Bénévole' },
    { url: '/connexion', expected: 'Coureur' },
    { url: `/connexion?retour=${encodeURIComponent('/compte')}`, expected: 'Coureur' },
    { url: '/connexion?profil=x', expected: 'Coureur' },
    { url: `/compte/connexion?retour=${encodeURIComponent('/scan')}`, expected: 'Coureur' },
  ];
  for (const { url, expected } of cases) {
    test(`(b) ${url} présélectionne « ${expected} »`, async ({ page }) => {
      await page.goto(url);
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
      const other = expected === 'Coureur' ? 'Bénévole' : 'Coureur';
      await expect(page.getByRole('radio', { name: expected })).toBeChecked();
      await expect(page.getByRole('radio', { name: other })).not.toBeChecked();
      await expect(page.getByLabel(expected === 'Coureur' ? 'Pseudo' : "Nom d'utilisateur")).toBeVisible();
    });
  }
});

/**
 * CA23 (inc. 7) — Messages d'expiration par entrée (RG1, CL4, reprise de CA24 inc. 4) : 401 de E6 pendant un envoi de
 * scan -> « Connexion », « Session expirée », « Bénévole » ; 401 sur `/api/account/**` -> `/compte/connexion` avec
 * « Session expirée ou identifiants modifiés : reconnectez-vous » et « Coureur » ; 401 admin -> « Bénévole ».
 */
test.describe('@INC-7 @INC7-CA23 Messages d\'expiration par entrée', () => {
  test.use({ serviceWorkers: 'block' });

  const RUNNER_EXPIRED = 'Session expirée ou identifiants modifiés : reconnectez-vous';
  const unauthorized = {
    status: 401, contentType: 'application/json',
    body: JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', detail: 'Authentification requise' }),
  };

  test('401 de E6 pendant un envoi de scan : « Session expirée », « Bénévole » sélectionnée ; message gardé pour son entrée', async ({ page }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I7C23A-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const registration = await api.register(race.id, 'Expire Scan');
    await api.startRace(race.id);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await submitLogin(page, 'staff', SCANNER_USERNAME, SCANNER_PASSWORD);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await page.route('**/api/scan/passages', (route) => route.fulfill(unauthorized));
    await page.getByLabel('Code du QR').fill(registration.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    await expect(page.getByRole('alert').filter({ hasText: 'Session expirée' })).toBeVisible();
    await expect(page.getByRole('radio', { name: 'Bénévole' })).toBeChecked();

    // Le message du référentiel actif est affiché ; celui de l'autre est conservé pour son entrée.
    await page.getByRole('radio', { name: 'Coureur' }).check();
    await expect(page.getByLabel('Pseudo')).toBeVisible();
    await expect(page.getByText('Session expirée', { exact: false })).toHaveCount(0);
    await page.getByRole('radio', { name: 'Bénévole' }).check();
    await expect(page.getByRole('alert').filter({ hasText: 'Session expirée' })).toBeVisible();
  });

  test('401 sur /api/account/** : /compte/connexion avec le message coureur et « Coureur » sélectionnée', async ({ page }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C23B-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);

    await page.goto('/compte/connexion');
    await submitLogin(page, 'runner', typed, DEFAULT_RUNNER_PASSWORD);
    await expectAccountPage(page, `lievre-${run}`);
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Courses', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Courses');

    await page.route('**/api/account/**', (route) => route.fulfill(unauthorized));
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Mes inscriptions', exact: true }).click();

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    expect(new URL(page.url()).pathname).toBe('/compte/connexion');
    await expect(page.getByRole('alert').filter({ hasText: RUNNER_EXPIRED })).toBeVisible();
    await expect(page.getByRole('radio', { name: 'Coureur' })).toBeChecked();
  });

  test('ADMIN : 401 pendant une action sous /admin : /connexion?retour=/admin/..., « Bénévole » sélectionnée', async ({ page }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-I7C23C-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const runner = await api.register(race.id, 'Coureur Admin Expire');

    await loginAdmin(page, `/admin/courses/${race.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(race.name);
    await expect(page.locator('li.card').filter({ hasText: runner.name })).toBeVisible();
    await page.route(/\/api\/admin\/races\/\d+\/runners$/, (route) => route.fulfill(unauthorized));
    await page.getByRole('button', { name: 'Modifier', exact: true }).first().click();
    await page.getByRole('button', { name: 'Enregistrer' }).click();

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion');
    await expect(page.getByRole('alert').filter({ hasText: 'Session expirée' })).toBeVisible();
    await expect(page.getByRole('radio', { name: 'Bénévole' })).toBeChecked();
    const url = new URL(page.url());
    expect(url.pathname).toBe('/connexion');
    expect(url.searchParams.get('retour')).toBe(`/admin/courses/${race.id}`);
  });
});
