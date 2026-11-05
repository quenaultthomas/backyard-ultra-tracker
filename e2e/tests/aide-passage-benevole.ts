import { expect, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_ADMIN_MASTER,
  PSEUDO_ADMIN_MASTER,
  connecterParApi,
  courseDeReference,
  creerCourseParApi,
  dateDansJours,
  demarrerCourseParApi,
} from './aide-admin';
import { sinscrireParApi, type Inscription } from './aide-coureur';
import { avecApi } from './aide-recherche-coureurs';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export const MESSAGE_SERVICE_INDISPONIBLE = 'Service indisponible, veuillez réessayer plus tard.';
export const MESSAGE_CSRF = 'La page a expiré, veuillez réessayer.';
export const MESSAGE_INTROUVABLE = "Ce coureur n'existe plus ou n'est plus un coureur.";

export const t = (page: Page, id: string): Locator => page.getByTestId(id);

export const texteConfirmation = (pseudo: string): string =>
  `Passer le coureur ${pseudo} en bénévole ? Il pourra scanner les passages des courses auxquelles il sera affecté et ne pourra plus s'inscrire à une course. Il devra se reconnecter. Ce changement est irréversible.`;

export const estPassage = (r: Request): boolean => r.method() === 'POST' && /\/api\/administration\/coureurs\/[^/]+\/passage-benevole$/.test(new URL(r.url()).pathname);
export const estPassageUrl = (url: URL): boolean => /\/api\/administration\/coureurs\/[^/]+\/passage-benevole$/.test(url.pathname);

/** Pseudo d'un résultat de recherche de la page Bénévoles, sans le libellé du bouton. */
export const pseudoDuResultat = (texte: string): string => texte.replace(/\s*Passer en bénévole\s*$/, '').trim();

/** Résultat de la recherche (page Bénévoles) portant ce pseudo. */
export const resultatDe = (page: Page, pseudo: string): Locator =>
  t(page, 'benevole-promotion-resultat').filter({ has: page.getByRole('button', { name: `Passer en bénévole le coureur ${pseudo}`, exact: true }) });

/** Crée un coureur par l'API. */
export async function creerCoureur(playwright: PlaywrightLib, pseudo: string): Promise<void> {
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
}

/** Course (nom donné) à `jours` jours ; démarrée (date du jour, jours = 0) si `demarree`. */
export async function creerCourse(playwright: PlaywrightLib, nom: string, jours = 30, dateIso?: string): Promise<string> {
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date: dateIso ?? dateDansJours(jours) })));
  return c['id'] as string;
}

export async function inscrire(playwright: PlaywrightLib, pseudo: string, courseId: string): Promise<Inscription> {
  return avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, courseId));
}

export async function demarrer(playwright: PlaywrightLib, courseId: string): Promise<void> {
  await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, courseId));
}

export async function ouvrirEnAdmin(page: Page, pseudo: string, chemin: string, titre: string): Promise<void> {
  await page.context().clearCookies();
  await connecterParApi(page.request, pseudo, MOT_DE_PASSE_ADMIN_CREE);
  await page.goto(chemin);
  await expect(t(page, titre)).toBeVisible();
  await expect(page.getByText('Chargement…')).toHaveCount(0);
}

export async function ouvrirEnMaster(page: Page, chemin: string, titre: string): Promise<void> {
  await page.context().clearCookies();
  await connecterParApi(page.request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  await page.goto(chemin);
  await expect(t(page, titre)).toBeVisible();
  await expect(page.getByText('Chargement…')).toHaveCount(0);
}

/** Recherche le pseudo dans le panneau (ouvert si besoin) et attend le résultat. */
export async function chercher(page: Page, pseudo: string): Promise<void> {
  if (!(await t(page, 'benevole-promotion').isVisible())) {
    await t(page, 'benevole-bouton-promouvoir').click();
    await expect(t(page, 'benevole-promotion-recherche')).toBeFocused();
  }
  await t(page, 'benevole-promotion-recherche').fill(pseudo);
  await expect(resultatDe(page, pseudo)).toHaveCount(1);
}

export const boutonPasser = (page: Page, pseudo: string): Locator => page.getByRole('button', { name: `Passer en bénévole le coureur ${pseudo}`, exact: true });

/** Ouvre la confirmation du résultat et attend le focus sur « Annuler ». */
export async function ouvrirConfirmation(page: Page, pseudo: string): Promise<void> {
  await boutonPasser(page, pseudo).click();
  await expect(t(page, 'benevole-promotion-confirmation')).toBeVisible();
  await expect(t(page, 'benevole-promotion-bouton-annuler')).toBeFocused();
}

export async function confirmerPassage(page: Page): Promise<void> {
  await t(page, 'benevole-promotion-bouton-confirmer').click();
}

export const ligneBenevole = (page: Page, pseudo: string): Locator => t(page, 'ligne-benevole').filter({ hasText: pseudo });

export { MOT_DE_PASSE, type APIRequestContext };
