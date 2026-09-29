import { expect, test } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { countRequests, loginAdmin, loginRunner } from '../fixtures/ui';

const E24 = /\/api\/admin\/accounts\/\d+$/;

/** CA38 (inc. 5) — Parcours de suppression d'un compte par l'admin, depuis la liste des coureurs (RG17, RG20). */
test.describe('@INC-5 @smoke @INC5-CA38 Suppression d\'un compte par l\'admin', () => {
  test.use({ serviceWorkers: 'block' });

  test('confirmation, une requête E24, coureur conservé sans compte, connexion refusée', async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-C38-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    const registration = await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);

    await loginAdmin(page, `/admin/courses/${race.id}`);
    const card = page.getByRole('listitem').filter({ hasText: `Pseudo : ${pseudo}` });
    await expect(card).toBeVisible();
    const e24 = countRequests(page, 'DELETE', E24);

    // Confirmation avec le pseudo en minuscules ; Annuler : aucune requête.
    await card.getByRole('button', { name: 'Supprimer le compte' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText(`Supprimer le compte ${pseudo} ?`);
    await dialog.getByRole('button', { name: 'Annuler' }).click();
    await expect(dialog).toBeHidden();
    expect(e24.count()).toBe(0);

    // Confirmer : une seule requête E24, 204.
    await card.getByRole('button', { name: 'Supprimer le compte' }).click();
    const [response] = await Promise.all([
      page.waitForResponse((r) => E24.test(r.url()) && r.request().method() === 'DELETE'),
      dialog.getByRole('button', { name: 'Confirmer' }).click(),
    ]);
    expect(response.status()).toBe(204);
    await expect(dialog).toBeHidden();
    expect(e24.count()).toBe(1);

    // Liste rechargée : le coureur reste, avec son dossard, sans pseudo, nommé « Coureur n°{bib} ».
    await loginAdmin(page, `/admin/courses/${race.id}`);
    const label = `Coureur n°${registration.bib}`;
    const runnerCard = page.getByRole('listitem').filter({ hasText: label });
    await expect(runnerCard).toBeVisible();
    await expect(runnerCard).toContainText(`${registration.bib} — ${label}`);
    await expect(runnerCard.getByText(/^Pseudo :\s*$/)).toBeVisible();
    await expect(runnerCard.getByRole('button', { name: 'Supprimer le compte' })).toHaveCount(0);
    const runners = await api.adminRunners(race.id);
    expect(runners).toHaveLength(1);
    expect(runners[0]?.accountId).toBeNull();
    expect(runners[0]?.name).toBe(label);

    // Connexion coureur : refusée.
    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD);
    await expect(page.getByText('Identifiants invalides')).toBeVisible();
  });
});
