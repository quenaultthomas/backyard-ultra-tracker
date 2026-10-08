import { expect, type Page } from '@playwright/test';

/** Ouvre le menu burger du visiteur anonyme et attend qu'il soit déployé et visible. */
export async function ouvrirMenuVisiteur(page: Page): Promise<void> {
  await page.getByTestId('bouton-menu').click();
  await expect(page.getByTestId('bouton-menu')).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByTestId('menu-visiteur')).toBeVisible();
}
