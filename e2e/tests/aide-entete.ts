import { expect, type Page } from '@playwright/test';

/** Ouvre le menu burger du visiteur anonyme et attend qu'il soit déployé et visible. */
export async function ouvrirMenuVisiteur(page: Page): Promise<void> {
  await page.getByTestId('bouton-menu').click();
  await expect(page.getByTestId('bouton-menu')).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByTestId('menu-visiteur')).toBeVisible();
}

/**
 * Ouvre le menu burger de l'utilisateur connecté et attend qu'il soit déployé et visible.
 * Attend d'abord la fin de la connexion : un clic sur le menu visiteur serait perdu quand
 * l'en-tête recrée le menu fermé au passage à l'état connecté.
 */
export async function ouvrirMenuCompte(page: Page): Promise<void> {
  await expect(page.getByTestId('menu-compte')).toHaveCount(1);
  await page.getByTestId('bouton-menu').click();
  await expect(page.getByTestId('bouton-menu')).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByTestId('menu-compte')).toBeVisible();
}

/** Ferme le menu burger de l'utilisateur connecté par Échap. */
export async function fermerMenuCompte(page: Page): Promise<void> {
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('bouton-menu')).toHaveAttribute('aria-expanded', 'false');
}

/** Ouvre le menu de l'utilisateur connecté puis active « Se déconnecter ». */
export async function seDeconnecterParLeMenu(page: Page): Promise<void> {
  await ouvrirMenuCompte(page);
  await page.getByTestId('bouton-deconnexion').click();
}

/**
 * Ouvre le menu puis active « Se déconnecter » quand le Compte a des scans en attente (5.1b RG4) : attend la
 * confirmation, puis active « Me déconnecter quand même ».
 */
export async function seDeconnecterMalgreLesScansEnAttente(page: Page): Promise<void> {
  await seDeconnecterParLeMenu(page);
  await expect(page.getByTestId('entete-deconnexion-confirmation')).toBeVisible();
  await page.getByTestId('entete-deconnexion-confirmer').click();
}
