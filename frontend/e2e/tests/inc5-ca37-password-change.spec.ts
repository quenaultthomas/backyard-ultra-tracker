import { expect, test } from '@playwright/test';
import { Api, basicAuth, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { storageDump } from '../fixtures/storage';
import { countRequests, expectAccountPage, loginRunner } from '../fixtures/ui';

const E23 = /\/api\/account\/password$/;
const NEW_PASSWORD = 'nouveau-mdp-43';

/**
 * CA37 (inc. 5) — Parcours de changement de mot de passe par le coureur (RG17, RG19, RG21).
 *
 * « Ni l'un ni l'autre mot de passe n'apparaît » dans le stockage : vérifié en clair, tout du long. Avec « Rester
 * connecté », RG21 prévoit d'enregistrer la valeur `Authorization` (base64 de pseudo:motdepasse) dans IndexedDB :
 * on vérifie donc qu'elle y est, remplacée par la nouvelle après le changement (RG21), et absente sans « Rester
 * connecté » et après déconnexion.
 */
test.describe('@INC-5 @smoke @INC5-CA37 Changement de mot de passe par le coureur', () => {
  test.use({ serviceWorkers: 'block' });

  test('une requête E23, session conservée au rechargement, ancien mot de passe refusé, rien de stocké en clair', async ({
    page,
  }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const typed = `Lievre-${run}`;
    const pseudo = `lievre-${run}`;
    const race = await api.createRace({ name: `E2E-C37-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, typed, DEFAULT_RUNNER_PASSWORD);
    const oldBasic = basicAuth(typed, DEFAULT_RUNNER_PASSWORD).replace('Basic ', '');
    // Après le changement, l'application conserve le pseudo renvoyé par l'API (minuscules, RG2), pas la saisie.
    const newBasic = basicAuth(pseudo, NEW_PASSWORD).replace('Basic ', '');
    const newBasicTyped = basicAuth(typed, NEW_PASSWORD).replace('Basic ', '');
    const oldBasicLower = basicAuth(pseudo, DEFAULT_RUNNER_PASSWORD).replace('Basic ', '');

    const assertNoPlainPasswordStored = async (): Promise<string> => {
      const dump = await storageDump(page);
      for (const secret of [DEFAULT_RUNNER_PASSWORD, NEW_PASSWORD]) {
        expect(dump).not.toContain(secret);
        expect(decodeURIComponent(page.url())).not.toContain(secret);
        expect(page.url()).not.toContain(secret);
      }
      return dump;
    };

    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD, { remember: true });
    await expectAccountPage(page, pseudo);
    const afterLogin = await assertNoPlainPasswordStored();
    expect(afterLogin).toContain(oldBasic);
    const e23 = countRequests(page, 'PUT', E23);

    await page.getByLabel('Nouveau mot de passe', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirmer le nouveau mot de passe').fill(NEW_PASSWORD);
    const [response] = await Promise.all([
      page.waitForResponse((r) => E23.test(r.url()) && r.request().method() === 'PUT'),
      page.getByRole('button', { name: 'Changer mon mot de passe' }).click(),
    ]);
    expect(response.status()).toBe(204);
    await expect(page.getByText('Mot de passe modifié')).toBeVisible();
    expect(e23.count()).toBe(1);
    // RG21 : les identifiants conservés sont remplacés par les nouveaux.
    await expect.poll(() => storageDump(page)).toContain(newBasic);
    const afterChange = await assertNoPlainPasswordStored();
    expect(afterChange).not.toContain(oldBasic);
    expect(afterChange).not.toContain(oldBasicLower);

    // « Rester connecté » : après rechargement, aucune nouvelle connexion, avec le pseudo en minuscules.
    await page.reload();
    await expectAccountPage(page, pseudo);
    await assertNoPlainPasswordStored();

    // Déconnexion : plus rien de stocké ; ancien mot de passe refusé, nouveau accepté.
    await page.getByRole('button', { name: 'Se déconnecter' }).click();
    const afterLogout = await assertNoPlainPasswordStored();
    expect(afterLogout).not.toContain(newBasic);
    expect(afterLogout).not.toContain(newBasicTyped);
    await loginRunner(page, typed, DEFAULT_RUNNER_PASSWORD);
    await expect(page.getByText('Identifiants invalides')).toBeVisible();
    await loginRunner(page, typed, NEW_PASSWORD, { open: false });
    await expectAccountPage(page, pseudo);
    // Sans « Rester connecté », rien n'est écrit.
    const withoutRemember = await assertNoPlainPasswordStored();
    for (const value of [newBasic, newBasicTyped, oldBasic, oldBasicLower]) {
      expect(withoutRemember).not.toContain(value);
    }
  });
});
