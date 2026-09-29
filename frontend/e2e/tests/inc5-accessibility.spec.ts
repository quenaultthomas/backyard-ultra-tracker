import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { Api, DEFAULT_RUNNER_PASSWORD, uniqueRun } from '../fixtures/api';
import { expectAccountPage, loginAdmin, loginRunner } from '../fixtures/ui';

/**
 * Accessibilité de base (axe-core, WCAG 2.1 A et AA, aucune violation « serious » ou « critical ») sur les écrans
 * ajoutés par l'incrément 5 : inscription avec compte, connexion coureur, « Mes inscriptions » et « Comptes ».
 * Pas de CA numéroté dans la spec de l'inc. 5 : test transverse (RG48 inc. 4).
 */
test.describe('@INC-5 @INC5-A11Y Accessibilité des écrans de comptes', () => {
  test.use({ serviceWorkers: 'block' });

  async function assertNoSeriousViolations(page: Page): Promise<void> {
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(serious, JSON.stringify(serious, null, 2)).toEqual([]);
  }

  test('audit axe-core sur inscription, connexion coureur, Mes inscriptions et Comptes', async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({ name: `E2E-A11Y5-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10 });
    await api.registerAccount(race.id, `Lievre-${run}`, DEFAULT_RUNNER_PASSWORD);

    await page.goto(`/inscription/${race.id}`);
    await expect(page.getByLabel('Pseudo')).toBeVisible();
    await assertNoSeriousViolations(page);

    await page.goto('/compte/connexion');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Connexion coureur');
    await assertNoSeriousViolations(page);

    await loginRunner(page, `Lievre-${run}`, DEFAULT_RUNNER_PASSWORD, { open: false });
    await expectAccountPage(page, `lievre-${run}`);
    await expect(page.locator('img.qr-image')).toBeVisible();
    await assertNoSeriousViolations(page);

    await loginAdmin(page, '/admin/comptes');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Comptes');
    await expect(page.getByRole('listitem').first()).toBeVisible();
    await assertNoSeriousViolations(page);
  });
});
