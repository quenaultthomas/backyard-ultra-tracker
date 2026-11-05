import { expect, type Locator, type Page, type Request } from '@playwright/test';

/*
 * Incrément 9.7 : « Passer en admin » depuis la recherche de la page Admins (réservée à l'admin master).
 * Les mises en place (création de coureurs, de courses, ouverture en admin) sont celles de aide-passage-benevole.ts.
 */
export { MESSAGE_CSRF, MESSAGE_INTROUVABLE, MESSAGE_SERVICE_INDISPONIBLE, creerCoureur, creerCourse, demarrer, inscrire, ouvrirEnAdmin, ouvrirEnMaster, t } from './aide-passage-benevole';
import { t } from './aide-passage-benevole';

export const texteConfirmationAdmin = (pseudo: string): string =>
  `Passer le coureur ${pseudo} en administrateur ? Il aura les droits d'administration, pourra gérer les courses, les bénévoles et les coureurs, et ne pourra plus s'inscrire à une course. Il devra se reconnecter. Ce changement est irréversible dans l'application.`;

export const estPassageAdmin = (r: Request): boolean => r.method() === 'POST' && /\/api\/administration\/coureurs\/[^/]+\/passage-admin$/.test(new URL(r.url()).pathname);
export const estPassageAdminUrl = (url: URL): boolean => /\/api\/administration\/coureurs\/[^/]+\/passage-admin$/.test(url.pathname);

export const nomBoutonAdmin = (pseudo: string): string => `Passer en admin le coureur ${pseudo}`;

export const boutonPasserAdmin = (page: Page, pseudo: string): Locator => page.getByRole('button', { name: nomBoutonAdmin(pseudo), exact: true });

/** Résultat de la recherche (page Admins) portant ce pseudo. */
export const resultatAdminDe = (page: Page, pseudo: string): Locator =>
  t(page, 'admin-promotion-resultat').filter({ has: boutonPasserAdmin(page, pseudo) });

export const ligneAdmin = (page: Page, pseudo: string): Locator => t(page, 'ligne-admin').filter({ hasText: pseudo });

/** Recherche le pseudo dans le panneau de la page Admins (ouvert si besoin) et attend le résultat. */
export async function chercherAdmin(page: Page, pseudo: string): Promise<void> {
  if (!(await t(page, 'admin-promotion').isVisible())) {
    await t(page, 'admin-bouton-promouvoir').click();
    await expect(t(page, 'admin-promotion-recherche')).toBeFocused();
  }
  await t(page, 'admin-promotion-recherche').fill(pseudo);
  await expect(resultatAdminDe(page, pseudo)).toHaveCount(1);
}

/** Ouvre la confirmation du résultat et attend le focus sur « Annuler ». */
export async function ouvrirConfirmationAdmin(page: Page, pseudo: string): Promise<void> {
  await boutonPasserAdmin(page, pseudo).click();
  await expect(t(page, 'admin-promotion-confirmation')).toBeVisible();
  await expect(t(page, 'admin-promotion-bouton-annuler')).toBeFocused();
}

export async function confirmerPassageAdmin(page: Page): Promise<void> {
  await t(page, 'admin-promotion-bouton-confirmer').click();
}
