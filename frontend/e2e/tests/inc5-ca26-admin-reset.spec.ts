import { expect, test } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { countRequests, expectAccountPage, loginAdmin, loginRunner } from '../fixtures/ui';

const E22 = /\/api\/admin\/accounts\/\d+\/password$/;

/** CA26 (inc. 5) — Parcours de réinitialisation du mot de passe par l'admin (RG14, RG17). */
test.describe('@INC-5 @smoke @INC5-CA26 Réinitialisation du mot de passe par l\'admin', () => {
  test.use({ serviceWorkers: 'block' });

  test('annuler n\'envoie rien, confirmer envoie une requête E22, l\'ancien mot de passe ne marche plus', async ({
    page,
  }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-C26-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, `Lievre-${run}`, DEFAULT_RUNNER_PASSWORD);

    await loginAdmin(page, `/admin/courses/${race.id}`);
    const card = page.getByRole('listitem').filter({ hasText: `Pseudo : ${pseudo}` });
    await expect(card).toBeVisible();
    const e22 = countRequests(page, 'PUT', E22);

    // Annuler : aucune requête.
    await card.getByRole('button', { name: 'Réinitialiser le mot de passe' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Annuler' }).click();
    await expect(dialog).toBeHidden();
    expect(e22.count()).toBe(0);

    // Confirmer : une seule requête E22, 204.
    await card.getByRole('button', { name: 'Réinitialiser le mot de passe' }).click();
    await dialog.getByLabel('Mot de passe provisoire', { exact: true }).fill('nouveau-mdp-42');
    await dialog.getByLabel('Confirmer le mot de passe provisoire').fill('nouveau-mdp-42');
    const [response] = await Promise.all([
      page.waitForResponse((r) => E22.test(r.url()) && r.request().method() === 'PUT'),
      dialog.getByRole('button', { name: 'Confirmer' }).click(),
    ]);
    expect(response.status()).toBe(204);
    await expect(dialog).toBeHidden();
    expect(e22.count()).toBe(1);

    // Ancien mot de passe refusé, nouveau accepté sans demande de changement.
    await loginRunner(page, `Lievre-${run}`, DEFAULT_RUNNER_PASSWORD);
    await expect(page.getByText('Identifiants invalides')).toBeVisible();
    await loginRunner(page, `Lievre-${run}`, 'nouveau-mdp-42', { open: false });
    await expectAccountPage(page, pseudo);
    await expect(page.getByRole('heading', { name: 'Changer mon mot de passe' })).toBeVisible();
  });
});
