import { expect, test } from '@playwright/test';
import { Api, basicAuth, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { storageDump } from '../fixtures/storage';
import { countRequests, expectAccountPage, loginRunner } from '../fixtures/ui';

const E26 = /\/api\/public\/accounts$/;
const E21 = /\/api\/account\/me$/;
const E20 = /\/api\/account\/races\/\d+\/registrations$/;
const NEW_PASSWORD = 'motdepasse-9';

/**
 * CA12 (inc. 7) — Écran d'inscription autonome `/inscription` (RG7, CL8) : validation sans requête, création (une
 * seule E26 sans Authorization, une seule E21), pseudo pris (409 et lien), 429 à corps HTML, déjà connecté.
 */
test.describe('@INC-7 @smoke @INC7-CA12 Écran d\'inscription autonome', () => {
  test.use({ serviceWorkers: 'block' });

  test('(a) champs et mots de passe différents : aucune requête', async ({ page }) => {
    const e26 = countRequests(page, 'POST', E26);
    await page.goto('/inscription');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Créer un compte');
    await expect(page.getByLabel('Pseudo')).toBeVisible();
    await expect(page.getByLabel('Mot de passe', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Confirmer le mot de passe')).toBeVisible();
    await expect(page.getByText("Bénévole : votre compte est créé par l'organisateur.")).toBeVisible();

    await page.getByLabel('Pseudo').fill(`nouveau-${uniqueRun()}`);
    await page.getByLabel('Mot de passe', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill('motdepasse-8');
    await page.getByRole('button', { name: 'Créer mon compte' }).click();
    await expect(page.getByText('Les deux mots de passe sont différents')).toBeVisible();
    expect(e26.count(), 'requêtes E26').toBe(0);
    expect(new URL(page.url()).pathname).toBe('/inscription');
  });

  test('(b) création : une seule E26 (201, sans Authorization) puis une seule E21 (200), arrivée sur /compte', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Nouveau-${run}`;
    const pseudo = `nouveau-${run}`;
    const e26 = countRequests(page, 'POST', E26);
    const e21 = countRequests(page, 'GET', E21);
    // E21 émises depuis l'écran de création (connexion automatique) ; la page /compte émet ensuite sa propre E21
    // (liste « Mes inscriptions »), qui n'est pas un rejeu.
    const e21FromCreationScreen: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'GET' && E21.test(request.url())
        && new URL(request.frame().url()).pathname === '/inscription') {
        e21FromCreationScreen.push(request.url());
      }
    });
    const e26Headers: Record<string, string>[] = [];
    page.on('request', (request) => {
      if (E26.test(request.url())) {
        e26Headers.push(request.headers());
      }
    });

    await page.goto('/inscription');
    await page.getByLabel('Pseudo').fill(typed);
    await page.getByLabel('Mot de passe', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(NEW_PASSWORD);
    const [created, signedIn] = await Promise.all([
      page.waitForResponse((r) => E26.test(r.url())),
      page.waitForResponse((r) => E21.test(r.url())),
      page.getByRole('button', { name: 'Créer mon compte' }).click(),
    ]);
    expect(created.status()).toBe(201);
    expect(await created.json()).toEqual({ pseudo });
    expect(signedIn.status()).toBe(200);

    await expectAccountPage(page, pseudo);
    expect(new URL(page.url()).pathname).toBe('/compte');
    await expect(page.getByText('Aucune inscription.')).toBeVisible();
    expect(e26.count(), 'requêtes E26').toBe(1);
    expect(e21FromCreationScreen, 'requêtes E21 émises depuis /inscription').toHaveLength(1);
    expect(e21.count(), 'E21 au total : connexion automatique puis liste de /compte').toBe(2);
    expect(e26Headers).toHaveLength(1);
    expect(e26Headers[0]?.['authorization'], 'en-tête Authorization de E26').toBeUndefined();

    // Côté serveur : le compte existe, sans coureur, et se connecte (vérification par l'API, pas par la page).
    expect(await api.accountMeStatus(typed, NEW_PASSWORD)).toBe(200);
    expect((await api.adminAccounts(pseudo)).map((account) => [account.pseudo, account.runnerCount]))
      .toEqual([[pseudo, 0]]);
  });

  test('(c) pseudo pris : 409, detail affiché, lien « J\'ai déjà un compte », aucun secret dans l\'URL ni les stockages', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C12C-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);
    const e26 = countRequests(page, 'POST', E26);
    const e21 = countRequests(page, 'GET', E21);

    await page.goto('/inscription');
    await page.getByLabel('Pseudo').fill(typed);
    await page.getByLabel('Mot de passe', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(NEW_PASSWORD);
    const [response] = await Promise.all([
      page.waitForResponse((r) => E26.test(r.url())),
      page.getByRole('button', { name: 'Créer mon compte' }).click(),
    ]);
    expect(response.status()).toBe(409);
    await expect(page.getByRole('alert')).toContainText(`Pseudo déjà utilisé : ${pseudo}`);
    const link = page.getByRole('link', { name: "J'ai déjà un compte" });
    await expect(link).toBeVisible();
    expect(new URL(await link.evaluate((el) => (el as HTMLAnchorElement).href)).pathname).toBe('/compte/connexion');
    expect(e26.count()).toBe(1);
    expect(e21.count(), 'aucune connexion après un 409').toBe(0);

    const url = page.url();
    expect(url).not.toContain(NEW_PASSWORD);
    expect(url).not.toContain(typed);
    const dump = await storageDump(page);
    expect(dump).not.toContain(NEW_PASSWORD);
    expect(dump).not.toContain(basicAuth(typed, NEW_PASSWORD).replace('Basic ', ''));
    expect(dump).not.toContain(basicAuth(pseudo, NEW_PASSWORD).replace('Basic ', ''));
  });

  test('(d) 429 à corps HTML : « Trop de tentatives. Réessayez dans une minute. », une seule requête', async ({ page }) => {
    await page.route(E26, (route) => route.fulfill({
      status: 429, contentType: 'text/html', body: '<html><body>429 Too Many Requests</body></html>',
    }));
    const e26 = countRequests(page, 'POST', E26);
    const e21 = countRequests(page, 'GET', E21);

    await page.goto('/inscription');
    await page.getByLabel('Pseudo').fill(`nouveau-${uniqueRun()}`);
    await page.getByLabel('Mot de passe', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Créer mon compte' }).click();

    await expect(page.getByRole('alert')).toContainText('Trop de tentatives. Réessayez dans une minute.');
    await expect(page.getByRole('button', { name: 'Créer mon compte' })).toBeEnabled();
    expect(e26.count(), 'requêtes E26').toBe(1);
    expect(e21.count(), 'requêtes E21').toBe(0);
  });

  test('(e) déjà connecté en coureur : « Vous êtes connecté en tant que », aucun champ de création', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-I7C12E-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);

    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD, { remember: true });
    await expectAccountPage(page, pseudo);
    await page.goto('/inscription');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Créer un compte');
    await expect(page.getByText(`Vous êtes connecté en tant que ${pseudo}`)).toBeVisible();
    await expect(page.getByLabel('Pseudo')).toHaveCount(0);
    await expect(page.getByLabel('Mot de passe', { exact: true })).toHaveCount(0);
    await expect(page.getByLabel('Confirmer le mot de passe')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Créer mon compte' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Aller à mon compte' })).toBeVisible();
  });
});

/**
 * CA13 (inc. 7) — Enchaînement avec une course (RG7, RG8) : depuis `/inscription/{raceId}` (course SETUP), l'anonyme
 * suit « Créer un compte » de l'en-tête, crée son compte, revient sur la page de la course connecté, s'inscrit par E20.
 */
test.describe('@INC-7 @smoke @INC7-CA13 Création de compte puis inscription à une course', () => {
  test.use({ serviceWorkers: 'block' });

  test('« Créer un compte » (retour vers la course), création, « M\'inscrire à cette course » : une E20 (201), QR, /compte', async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Nouveau-${run}`;
    const pseudo = `nouveau-${run}`;
    const race = await api.createRace({ name: `E2E-I7C13-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const e20 = countRequests(page, 'POST', E20);
    const e3 = countRequests(page, 'POST', /\/api\/public\/races\/\d+\/registrations$/);

    await page.goto(`/inscription/${race.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(`Inscription — ${race.name}`);
    await page.getByRole('banner').getByRole('link', { name: 'Créer un compte' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Créer un compte');
    const arrival = new URL(page.url());
    expect(arrival.pathname).toBe('/inscription');
    expect(arrival.searchParams.get('retour')).toBe(`/inscription/${race.id}`);

    await page.getByLabel('Pseudo').fill(typed);
    await page.getByLabel('Mot de passe', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirmer le mot de passe').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Créer mon compte' }).click();

    // Retour sur la page de la course, connecté.
    await expect(page.getByRole('heading', { level: 1 })).toContainText(`Inscription — ${race.name}`);
    expect(new URL(page.url()).pathname).toBe(`/inscription/${race.id}`);
    await expect(page.getByText(`Vous êtes connecté en tant que ${pseudo}`)).toBeVisible();

    const [response] = await Promise.all([
      page.waitForResponse((r) => E20.test(r.url()) && r.request().method() === 'POST'),
      page.getByRole('button', { name: "M'inscrire à cette course" }).click(),
    ]);
    expect(response.status()).toBe(201);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Inscription confirmée');
    await expect(page.locator('img.qr-image')).toBeVisible();
    expect(e20.count(), 'requêtes E20').toBe(1);
    expect(e3.count(), 'requêtes E3').toBe(0);

    // /compte la liste (navigation interne : la connexion n'est qu'en mémoire).
    await page.getByLabel('Navigation principale').getByRole('link', { name: 'Mes inscriptions', exact: true }).click();
    await expectAccountPage(page, pseudo);
    await expect(page.getByRole('heading', { level: 2, name: race.name })).toBeVisible();
  });
});
