import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique } from './aide-connexion';
import { courseDeReference, creerCourseParApi, exigerIdentifiantsAdminMaster } from './aide-admin';
import { lireJetonQrEnBase, placerStatutCourseEnBase, placerStatutInscriptionEnBase, sinscrireParApi } from './aide-coureur';
import { avecApi, dateApplicative } from './aide-horloge';
import { fichiersDuVolume, photoUrlDeMoi, poserPhoto, sessionCoureur } from './aide-photo';
import { seConnecter } from './aide-connexion';
import { lire, urlSuivi } from './aide-suivi';

/*
 * Incrément 10.1a, CA29 : suppression du Compte depuis /mon-compte (écran existant de 3.6), la photo étant posée par l'API.
 * Le fichier disparaît du volume, l'ancienne adresse répond 404, le pseudo réutilisé n'a pas de photo, et le suivi
 * public d'une Course terminée affiche « Coureur anonyme » avec le dossard.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA29 - supprimer son compte efface la photo (404, fichier retiré du volume), le pseudo réutilisé n\'a pas de photo et le suivi affiche « Coureur anonyme »', async ({ page, playwright }) => {
  const png = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));
  const pseudo = pseudoUnique('photo');

  // Course terminée (statut posé en base, sans toucher à l'horloge) où le coureur a couru et gagné
  const donnees = courseDeReference({ date: await dateApplicative(playwright) });
  const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, donnees));
  const courseId = course['id'] as string;
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  const inscription = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, courseId));
  expect(lireJetonQrEnBase(inscription.id)).toBeTruthy();
  placerStatutCourseEnBase(courseId, 'TERMINEE');
  placerStatutInscriptionEnBase(inscription.id, 'VAINQUEUR');

  // Photo posée par l'API ; le fichier est dans le volume
  const jeton = await avecApi(playwright, async (ctx) => poserPhoto(ctx, await sessionCoureur(ctx, pseudo), png));
  const fichier = fichiersDuVolume().find((f) => f.startsWith(jeton));
  expect(fichier, 'fichier de la photo dans le volume').toBeTruthy();
  await avecApi(playwright, async (ctx) => expect((await ctx.get(`/api/photos/${jeton}`)).status()).toBe(200));

  // Suppression par l'écran existant
  await seConnecter(page, pseudo);
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  await page.goto('/mon-compte');
  await page.getByTestId('mon-compte-bouton-supprimer').click();
  await page.getByTestId('mon-compte-champ-suppression-mot-de-passe').fill(MOT_DE_PASSE);
  await page.getByTestId('mon-compte-bouton-confirmer-suppression').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);

  // Ancienne adresse en 404 (GET public) et fichier effacé du volume
  await expect.poll(async () => (await page.request.get(`/api/photos/${jeton}`)).status()).toBe(404);
  await expect.poll(() => fichiersDuVolume().some((f) => f.startsWith(jeton))).toBe(false);

  // Le même pseudo, recréé, n'a pas de photo
  await avecApi(playwright, async (ctx) => {
    await creerCompteParApi(ctx, pseudo);
    await sessionCoureur(ctx, pseudo);
    expect(await photoUrlDeMoi(ctx)).toBeNull();
  });

  // Suivi public : « Coureur anonyme » avec son dossard (micro-cache de lecture : on attend l'API)
  await expect
    .poll(async () => ((await (await page.request.get(`/api/courses/${courseId}/suivi`)).json()) as { vainqueurs: Array<{ pseudo: string }> }).vainqueurs[0]?.pseudo)
    .toBe('Coureur anonyme');
  await page.goto(urlSuivi(courseId));
  const ligne = lire(page, 'suivi-vainqueur-' + inscription.dossard);
  await expect(ligne).toContainText('Coureur anonyme');
  await expect(ligne).toContainText(`Dossard ${inscription.dossard}`);
  await expect(ligne).not.toContainText(pseudo);
});
