import { expect, test } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { countRequests, loginAdmin } from '../fixtures/ui';

const E25 = /\/api\/admin\/accounts(\?.*)?$/;
const E22 = /\/api\/admin\/accounts\/\d+\/password$/;
const E24 = /\/api\/admin\/accounts\/\d+$/;

/** CA44 (inc. 5) — Parcours de l'écran « Comptes » (RG17, RG24, RG14, RG20, CL21). */
test.describe('@INC-5 @smoke @INC5-CA44 Écran Comptes de l\'administration', () => {
  test.use({ serviceWorkers: 'block' });

  test('recherche, réinitialisation, suppression et recherche insensible à la casse', async ({ page }, testInfo) => {
    test.setTimeout(90_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const lievre = `lievre-${run}`;
    const oublie = `oublie-${run}`;
    const race = await api.createRace({ name: `E2E-C44-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, `Lievre-${run}`, DEFAULT_RUNNER_PASSWORD);
    const orphan = await api.registerAccount(race.id, `Oublie-${run}`, DEFAULT_RUNNER_PASSWORD);
    await api.deleteRunner(orphan.runnerId);

    // Depuis /admin, lien « Comptes ».
    await loginAdmin(page, '/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');
    await page.getByRole('link', { name: 'Comptes' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Comptes');
    await expect(page.getByRole('button', { name: 'Rechercher' })).toBeEnabled();

    // Recherche « -{run} » : une seule requête E25, deux lignes.
    const e25 = countRequests(page, 'GET', E25);
    await page.getByLabel('Rechercher un pseudo').fill(`-${run}`);
    await page.getByRole('button', { name: 'Rechercher' }).click();
    const lievreRow = page.getByRole('listitem').filter({ hasText: lievre });
    const oublieRow = page.getByRole('listitem').filter({ hasText: oublie });
    await expect(lievreRow).toContainText(`${lievre} — 1 inscription`);
    await expect(oublieRow).toContainText(`${oublie} — 0 inscription`);
    await expect(page.getByRole('listitem')).toHaveCount(2);
    expect(e25.count()).toBe(1);
    expect(decodeURIComponent(e25.urls()[0] ?? '')).toContain(`pseudo=-${run}`);

    // Réinitialisation : Annuler sans requête, puis Confirmer avec une seule requête E22 (204).
    const e22 = countRequests(page, 'PUT', E22);
    await oublieRow.getByRole('button', { name: 'Réinitialiser le mot de passe' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: 'Annuler' }).click();
    await expect(dialog).toBeHidden();
    expect(e22.count()).toBe(0);
    await oublieRow.getByRole('button', { name: 'Réinitialiser le mot de passe' }).click();
    await dialog.getByLabel('Mot de passe provisoire', { exact: true }).fill('nouveau-mdp-42');
    await dialog.getByLabel('Confirmer le mot de passe provisoire').fill('nouveau-mdp-42');
    const [reset] = await Promise.all([
      page.waitForResponse((r) => E22.test(r.url()) && r.request().method() === 'PUT'),
      dialog.getByRole('button', { name: 'Confirmer' }).click(),
    ]);
    expect(reset.status()).toBe(204);
    await expect(dialog).toBeHidden();
    expect(e22.count()).toBe(1);
    expect(await api.accountMeStatus(oublie, 'nouveau-mdp-42')).toBe(200);

    // Suppression : une seule requête E24 (204) ; la liste rechargée ne montre plus que lievre-{run}.
    const e24 = countRequests(page, 'DELETE', E24);
    await oublieRow.getByRole('button', { name: 'Supprimer le compte' }).click();
    const [deletion] = await Promise.all([
      page.waitForResponse((r) => E24.test(r.url()) && r.request().method() === 'DELETE'),
      dialog.getByRole('button', { name: 'Confirmer' }).click(),
    ]);
    expect(deletion.status()).toBe(204);
    expect(e24.count()).toBe(1);
    await expect(page.getByRole('listitem')).toHaveCount(1);
    await expect(page.getByRole('listitem')).toContainText(lievre);
    await expect(oublieRow).toHaveCount(0);

    // Recherche avec majuscule : normalisée côté API, plus aucun compte.
    await page.getByLabel('Rechercher un pseudo').fill(`Oublie-${run}`);
    await page.getByRole('button', { name: 'Rechercher' }).click();
    await expect(page.getByText('Aucun compte')).toBeVisible();
  });
});
