import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA41 — Accessibilité et cibles tactiles (RG48, RG49, RG51). Audit automatisé axe-core (WCAG 2.1 A et AA),
 * 0 violation de gravité « serious » ou « critical » sur les pages clés ; cibles tactiles de /scan ; piège à
 * focus du dialogue de DNF.
 */
test.describe('@INC-4 @INC4-CA41 Accessibilité et cibles tactiles', () => {
  async function assertNoSeriousViolations(page: import('@playwright/test').Page): Promise<void> {
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(serious, JSON.stringify(serious, null, 2)).toEqual([]);
  }

  test('audit axe-core sur les pages clés', async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-A11Y-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const a11yRunner = await api.register(race.id, 'Runner A11y');
    await api.startRace(race.id);

    await page.goto('/');
    await assertNoSeriousViolations(page);

    await page.goto(`/courses/${race.id}`);
    await expect(page.getByText('Yard 1', { exact: false })).toBeVisible({ timeout: 10_000 });
    await assertNoSeriousViolations(page);

    await page.goto(`/inscription/${race.id}`);
    await assertNoSeriousViolations(page);

    await page.goto('/scan');
    await assertNoSeriousViolations(page);

    await page.goto('/connexion');
    await assertNoSeriousViolations(page);

    await page.goto(`/connexion?retour=${encodeURIComponent(`/admin/courses/${race.id}`)}`);
    await page.getByLabel("Nom d'utilisateur").fill('admin-test');
    await page.getByLabel('Mot de passe').fill('admin-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(`E2E-A11Y-${run}`);
    await assertNoSeriousViolations(page);

    // Dialogue de DNF ouvert. Ouverture au clavier (focus explicite puis Entrée) : WebKit/Safari, à la
    // différence de Chromium, ne place pas le focus sur un bouton lors d'un simple clic souris — un clic
    // laisserait donc le focus « avant ouverture » sur un autre élément, et fausserait la vérification du
    // retour de focus ci-dessous, qui restaure précisément ce qui était focus au moment de l'ouverture.
    const runnerCard = page.locator('li.card').filter({ hasText: a11yRunner.name });
    const dnfTrigger = runnerCard.getByRole('button', { name: 'Déclarer DNF' });
    await dnfTrigger.focus();
    await dnfTrigger.press('Enter');
    const dnfDialogElement = page.locator('app-confirm-dialog').filter({ hasText: 'Déclarer DNF' }).locator('dialog');
    await expect(dnfDialogElement).toBeVisible();
    await assertNoSeriousViolations(page);

    // Piège à focus (RG51) : Tab ne sort pas du dialogue.
    const focusableCount = await dnfDialogElement.locator('button, input').count();
    for (let i = 0; i < focusableCount + 2; i++) {
      await page.keyboard.press('Tab');
    }
    const activeInsideDialog = await page.evaluate(() => {
      const dialog = document.querySelector('app-confirm-dialog dialog[open]');
      return dialog?.contains(document.activeElement) ?? false;
    });
    expect(activeInsideDialog).toBe(true);

    // Échap ferme, focus rendu au bouton d'origine.
    await page.keyboard.press('Escape');
    await expect(dnfDialogElement).toBeHidden();
    await expect(dnfTrigger).toBeFocused({ timeout: 2_000 });
  });

  test('cibles tactiles de /scan en 360×740', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-A11Y2-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const runner = await api.register(race.id, 'Runner Tactile');
    await api.startRace(race.id);

    await page.setViewportSize({ width: 360, height: 740 });
    await page.goto('/scan');

    const hasHorizontalScroll = await page.evaluate(() => document.documentElement.scrollWidth
      > document.documentElement.clientWidth);
    expect(hasHorizontalScroll).toBe(false);

    const buttons = page.locator('button:visible');
    const count = await buttons.count();
    for (let i = 0; i < count; i++) {
      const box = await buttons.nth(i).boundingBox();
      expect(box, `bouton #${i}`).not.toBeNull();
      if (box !== null) {
        expect(box.width).toBeGreaterThanOrEqual(48);
        expect(box.height).toBeGreaterThanOrEqual(48);
      }
    }

    // Taille du texte du dernier résultat (au moins 32 px, RG48/RG49).
    await page.getByLabel('Code du QR').fill(runner.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    const lastResultText = page.locator('.scan-result-text');
    await expect(lastResultText).toBeVisible({ timeout: 10_000 });
    const fontSizePx = await lastResultText.evaluate((element) => Number.parseFloat(getComputedStyle(element).fontSize));
    expect(fontSizePx).toBeGreaterThanOrEqual(32);
  });
});
