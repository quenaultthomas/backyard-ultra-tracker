import { expect, test } from '@playwright/test';

test.describe('Accueil', () => {
  test('[CA14 / 0.2 CA7] le visiteur voit le titre Backyard Ultra Tracker', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('titre')).toHaveText('Backyard Ultra Tracker');
  });

  test('[CA15 / 0.2 CA7] le visiteur voit « API : disponible » en moins de 5 s', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('etat-api')).toHaveText('API : disponible', { timeout: 5000 });
  });
});
