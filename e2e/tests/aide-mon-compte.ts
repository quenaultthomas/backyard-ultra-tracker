import { expect, type Page } from '@playwright/test';

/**
 * Déplie la section « Changer mon mot de passe » de Mon compte. Idempotente : sans effet si le panneau
 * est déjà déplié. Vérifie ensuite `aria-expanded="true"` et la visibilité du panneau.
 */
export async function deplierChangementMotDePasse(page: Page): Promise<void> {
  const bouton = page.getByTestId('mon-compte-bouton-deplier');
  await expect(bouton).toBeVisible();
  await expect(async () => {
    if ((await bouton.getAttribute('aria-expanded')) === 'false') {
      await bouton.click();
    }
    await expect(bouton).toHaveAttribute('aria-expanded', 'true', { timeout: 1000 });
  }).toPass({ timeout: 10_000 });
  await expect(page.getByTestId('mon-compte-panneau-mot-de-passe')).toBeVisible();
}
