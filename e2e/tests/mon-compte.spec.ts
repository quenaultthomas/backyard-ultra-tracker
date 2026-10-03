import { expect, test, type Page } from '@playwright/test';
import { creerCompteParApi, MOT_DE_PASSE, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  creerAdminParApi,
  creerBenevoleParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const NOUVEAU = 'nouveau-mot-de-passe-1';
const FAUX = 'mauvais-mot-de-passe-1';
const URL_MON_COMPTE = /\/mon-compte$/;
const RETOUR_MON_COMPTE = /\/connexion\?retour=%2Fmon-compte$/;

async function connecter(page: Page, pseudo: string, motDePasse: string, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

async function ouvrirMonCompte(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await connecter(page, pseudo, motDePasse);
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  await page.getByTestId('lien-mon-compte').click();
  await expect(page).toHaveURL(URL_MON_COMPTE);
  await expect(page.getByTestId('titre-mon-compte')).toHaveText('Mon compte');
}

async function changer(page: Page, actuel: string, nouveau: string, confirmation = nouveau): Promise<void> {
  await page.getByTestId('mon-compte-champ-actuel').fill(actuel);
  await page.getByTestId('mon-compte-champ-nouveau').fill(nouveau);
  await page.getByTestId('mon-compte-champ-confirmation').fill(confirmation);
  await page.getByTestId('mon-compte-bouton-changer').click();
}

async function verifierChampsVides(page: Page): Promise<void> {
  await expect(page.getByTestId('mon-compte-champ-actuel')).toHaveValue('');
  await expect(page.getByTestId('mon-compte-champ-nouveau')).toHaveValue('');
  await expect(page.getByTestId('mon-compte-champ-confirmation')).toHaveValue('');
}

async function parcoursChangement(page: Page, pseudo: string, ancien: string, libelleRole: string, arrivee: RegExp): Promise<void> {
  await ouvrirMonCompte(page, pseudo, ancien);
  await expect(page.getByTestId('mon-compte-pseudo')).toHaveText(pseudo);
  await expect(page.getByTestId('mon-compte-role')).toHaveText(libelleRole);
  await expect(page.getByTestId('mon-compte-aide')).toContainText('12 caractères minimum');

  await changer(page, ancien, NOUVEAU);
  await expect(page.getByTestId('mon-compte-message-succes')).toHaveText('Votre mot de passe a été modifié.');
  await verifierChampsVides(page);
  await expect(page).toHaveURL(URL_MON_COMPTE);
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);

  await page.reload();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  await expect(page.getByTestId('titre-mon-compte')).toBeVisible();

  await page.getByTestId('bouton-deconnexion').click();
  await expect(page.getByTestId('lien-se-connecter')).toBeVisible();

  await connecter(page, pseudo, ancien);
  await expect(page.getByTestId('erreur-generale')).toHaveText('Pseudo ou mot de passe incorrect.');
  await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);

  await saisir(page, pseudo, NOUVEAU);
  await page.getByTestId('bouton-connexion').click();
  await expect(page).toHaveURL(arrivee);
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}

test.describe('Mon compte', () => {
  test('CA18 - un coureur change son mot de passe puis se reconnecte avec le nouveau', async ({ page, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    await parcoursChangement(page, pseudo, MOT_DE_PASSE, 'Coureur', /\/$/);
  });

  test('CA18 - un bénévole change son mot de passe puis se reconnecte avec le nouveau', async ({ page, request }) => {
    const pseudo = pseudoBenevoleUnique();
    await creerBenevoleParApi(request, pseudo);
    await parcoursChangement(page, pseudo, MOT_DE_PASSE_BENEVOLE_CREE, 'Bénévole', /\/benevole$/);
  });

  test('CA18 - un admin change son mot de passe puis se reconnecte avec le nouveau', async ({ page, request }) => {
    const pseudo = pseudoAdminUnique();
    await creerAdminParApi(request, pseudo);
    await parcoursChangement(page, pseudo, MOT_DE_PASSE_ADMIN_CREE, 'Administrateur', /\/administration$/);
  });

  test('CA19 - les contrôles client et les erreurs serveur sont affichés sans changer le mot de passe', async ({ page, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    await ouvrirMonCompte(page, pseudo, MOT_DE_PASSE);

    let envois = 0;
    page.on('request', (r) => {
      if (r.method() === 'PUT' && r.url().includes('/api/comptes/moi/mot-de-passe')) envois++;
    });

    await page.getByTestId('mon-compte-bouton-changer').click();
    await expect(page.getByTestId('mon-compte-erreur-actuel')).toHaveText('Le mot de passe actuel est obligatoire.');
    await expect(page.getByTestId('mon-compte-erreur-nouveau')).toHaveText('Le nouveau mot de passe est obligatoire.');

    await changer(page, MOT_DE_PASSE, 'court-secre', 'autre-chose-12');
    await expect(page.getByTestId('mon-compte-erreur-nouveau')).toHaveText('Le mot de passe doit faire au moins 12 caractères.');
    await expect(page.getByTestId('mon-compte-erreur-confirmation')).toHaveText('Les mots de passe ne correspondent pas.');
    await expect(page.getByTestId('mon-compte-erreur-actuel')).toHaveCount(0);
    expect(envois).toBe(0);

    await changer(page, FAUX, NOUVEAU);
    await expect(page.getByTestId('mon-compte-erreur-actuel')).toHaveText('Le mot de passe actuel est incorrect.');
    await verifierChampsVides(page);
    await expect(page.getByTestId('mon-compte-message-succes')).toHaveCount(0);

    await changer(page, MOT_DE_PASSE, MOT_DE_PASSE);
    await expect(page.getByTestId('mon-compte-erreur-nouveau')).toHaveText('Le nouveau mot de passe doit être différent de l\'actuel.');
    await verifierChampsVides(page);

    await changer(page, MOT_DE_PASSE, 'x'.repeat(129));
    await expect(page.getByTestId('mon-compte-erreur-nouveau')).toBeVisible();
    await expect(page.getByTestId('mon-compte-erreur-nouveau')).toContainText('128');
    await verifierChampsVides(page);
    await expect(page.getByTestId('mon-compte-message-succes')).toHaveCount(0);
    expect(envois).toBe(3);

    // l'ancien mot de passe est toujours valable
    await page.getByTestId('bouton-deconnexion').click();
    await expect(page.getByTestId('lien-se-connecter')).toBeVisible();
    await connecter(page, pseudo, MOT_DE_PASSE);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  });

  test('CA20 - un anonyme est redirigé vers la connexion puis revient sur Mon compte', async ({ page, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    await page.goto('/mon-compte');
    await expect(page).toHaveURL(RETOUR_MON_COMPTE);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(URL_MON_COMPTE);
    await expect(page.getByTestId('titre-mon-compte')).toBeVisible();
  });

  test('CA20 - le blocage après cinq échecs est commun au changement et à la connexion', async ({ page, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    await ouvrirMonCompte(page, pseudo, MOT_DE_PASSE);

    for (let i = 0; i < 5; i++) {
      await changer(page, FAUX, NOUVEAU);
      await expect(page.getByTestId('mon-compte-erreur-actuel')).toHaveText('Le mot de passe actuel est incorrect.');
      await verifierChampsVides(page);
    }
    await changer(page, MOT_DE_PASSE, NOUVEAU);
    await expect(page.getByTestId('mon-compte-erreur-generale')).toHaveText('Trop de tentatives. Réessayez dans 15 minutes.');
    await verifierChampsVides(page);
    await expect(page.getByTestId('mon-compte-message-succes')).toHaveCount(0);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
    await expect(page).toHaveURL(URL_MON_COMPTE);

    await page.getByTestId('bouton-deconnexion').click();
    await expect(page.getByTestId('lien-se-connecter')).toBeVisible();
    await connecter(page, pseudo, MOT_DE_PASSE);
    await expect(page.getByTestId('erreur-generale')).toHaveText('Trop de tentatives de connexion. Réessayez dans 15 minutes.');
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
  });

  test('CA20 - une erreur serveur 500 sur le changement affiche un message de service indisponible', async ({ page, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    await ouvrirMonCompte(page, pseudo, MOT_DE_PASSE);
    await page.route('**/api/comptes/moi/mot-de-passe', (route) =>
      route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'ERREUR_INTERNE' }) }),
    );
    await changer(page, MOT_DE_PASSE, NOUVEAU);
    await expect(page.getByTestId('mon-compte-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await verifierChampsVides(page);
    await expect(page.getByTestId('mon-compte-message-succes')).toHaveCount(0);
  });

  test('CA20 - une erreur réseau sur le changement affiche un message de service indisponible', async ({ page, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    await ouvrirMonCompte(page, pseudo, MOT_DE_PASSE);
    await page.route('**/api/comptes/moi/mot-de-passe', (route) => route.abort('failed'));
    await changer(page, MOT_DE_PASSE, NOUVEAU);
    await expect(page.getByTestId('mon-compte-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await verifierChampsVides(page);
  });

  test('CA20 - une session expirée (401) sur le changement redirige vers la connexion avec retour', async ({ page, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    await ouvrirMonCompte(page, pseudo, MOT_DE_PASSE);
    await page.route('**/api/comptes/moi/mot-de-passe', (route) =>
      route.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ status: 401, code: 'NON_AUTHENTIFIE' }) }),
    );
    await changer(page, MOT_DE_PASSE, NOUVEAU);
    await expect(page).toHaveURL(RETOUR_MON_COMPTE);
  });

  test('CA21 - les autres sessions du même compte sont fermées au changement de mot de passe', async ({ browser, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    const contexteA = await browser.newContext();
    const contexteB = await browser.newContext();
    try {
      const pageA = await contexteA.newPage();
      const pageB = await contexteB.newPage();
      await connecter(pageB, pseudo, MOT_DE_PASSE);
      await expect(pageB.getByTestId('entete-pseudo')).toHaveText(pseudo);
      await ouvrirMonCompte(pageA, pseudo, MOT_DE_PASSE);

      await changer(pageA, MOT_DE_PASSE, NOUVEAU);
      await expect(pageA.getByTestId('mon-compte-message-succes')).toBeVisible();
      await expect(pageA.getByTestId('entete-pseudo')).toHaveText(pseudo);

      await pageB.reload();
      await expect(pageB.getByTestId('lien-se-connecter')).toBeVisible();
      await expect(pageB.getByTestId('entete-pseudo')).toHaveCount(0);

      await pageA.reload();
      await expect(pageA.getByTestId('entete-pseudo')).toHaveText(pseudo);

      await connecter(pageB, pseudo, NOUVEAU);
      await expect(pageB.getByTestId('entete-pseudo')).toHaveText(pseudo);
    } finally {
      await contexteA.close();
      await contexteB.close();
    }
  });
});
