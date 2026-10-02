import { expect, test, type Page } from '@playwright/test';
import {
  MOT_DE_PASSE,
  creerCompteParApi,
  ouvrirConnexion,
  pseudoUnique,
  saisir,
  seConnecter,
} from './aide-connexion';

const MOT_DE_PASSE_FAUX = 'mauvais-mot-de-passe-1';
const MESSAGE_ECHEC = 'Pseudo ou mot de passe incorrect.';
const MESSAGE_BLOCAGE = 'Trop de tentatives de connexion. Réessayez dans 15 minutes.';

async function tenter(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

/** Cinq échecs consécutifs : chacun affiche le message générique de 1.2. Retourne les messages observés. */
async function cinqEchecs(page: Page, pseudo: string): Promise<string[]> {
  const messages: string[] = [];
  for (let i = 0; i < 5; i++) {
    await tenter(page, pseudo, MOT_DE_PASSE_FAUX);
    const erreur = page.getByTestId('erreur-generale');
    await expect(erreur).toHaveText(MESSAGE_ECHEC);
    await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
    messages.push((await erreur.textContent()) ?? '');
  }
  return messages;
}

async function verifierEtatBloque(page: Page, pseudo: string): Promise<void> {
  await expect(page.getByTestId('erreur-generale')).toHaveText(MESSAGE_BLOCAGE);
  await expect(page.getByTestId('champ-pseudo')).toHaveValue(pseudo);
  await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
  await expect(page.getByTestId('bouton-connexion')).toBeEnabled();
  await expect(page).toHaveURL(/\/connexion$/);
  await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
}

test.describe('Limiter les tentatives de connexion (1.3)', () => {
  test('CA24 - après 5 échecs, le 6e essai, même avec le bon mot de passe, affiche le message de blocage', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await ouvrirConnexion(page);

    await cinqEchecs(page, pseudo);
    await tenter(page, pseudo, MOT_DE_PASSE);

    await verifierEtatBloque(page, pseudo);

    // Une nouvelle tentative reste bloquée, le bouton reste utilisable.
    await tenter(page, pseudo, MOT_DE_PASSE);
    await verifierEtatBloque(page, pseudo);
  });

  test('CA25 - un pseudo inexistant est bloqué exactement comme un pseudo existant', async ({ page, request }) => {
    const existant = pseudoUnique();
    const fantome = pseudoUnique('fantome');
    await creerCompteParApi(request, existant);
    await ouvrirConnexion(page);

    const messagesExistant = await cinqEchecs(page, existant);
    await tenter(page, existant, MOT_DE_PASSE);
    await verifierEtatBloque(page, existant);
    const blocageExistant = await page.getByTestId('erreur-generale').textContent();

    await saisir(page, fantome, MOT_DE_PASSE_FAUX);
    const messagesFantome: string[] = [];
    for (let i = 0; i < 5; i++) {
      await page.getByTestId('champ-pseudo').fill(fantome);
      await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE_FAUX);
      await page.getByTestId('bouton-connexion').click();
      const erreur = page.getByTestId('erreur-generale');
      await expect(erreur).toHaveText(MESSAGE_ECHEC);
      await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
      messagesFantome.push((await erreur.textContent()) ?? '');
    }
    await tenter(page, fantome, MOT_DE_PASSE);
    await verifierEtatBloque(page, fantome);

    expect(messagesFantome).toEqual(messagesExistant);
    expect(await page.getByTestId('erreur-generale').textContent()).toBe(blocageExistant);
  });

  test('CA26 - 4 échecs puis un succès remettent le compteur à zéro, y compris après déconnexion', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await ouvrirConnexion(page);

    for (let i = 0; i < 4; i++) {
      await tenter(page, pseudo, MOT_DE_PASSE_FAUX);
      await expect(page.getByTestId('erreur-generale')).toHaveText(MESSAGE_ECHEC);
      await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
    }
    await tenter(page, pseudo, MOT_DE_PASSE);
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);

    await page.getByTestId('bouton-deconnexion').click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('message-deconnexion')).toBeVisible();

    for (let i = 0; i < 4; i++) {
      await tenter(page, pseudo, MOT_DE_PASSE_FAUX);
      await expect(page.getByTestId('erreur-generale')).toHaveText(MESSAGE_ECHEC);
      await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
    }
    await tenter(page, pseudo, MOT_DE_PASSE);
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  });

  test('CA27 - le blocage d\'un pseudo n\'empêche pas un autre compte de se connecter', async ({ page, request }) => {
    const a = pseudoUnique('a');
    const b = pseudoUnique('b');
    await creerCompteParApi(request, a);
    await creerCompteParApi(request, b);
    await ouvrirConnexion(page);

    await cinqEchecs(page, a);
    await tenter(page, a, MOT_DE_PASSE);
    await verifierEtatBloque(page, a);

    await tenter(page, b, MOT_DE_PASSE);
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(b);
  });

  test('CA28 - le message de blocage arrondit la durée en minutes, ou reste générique si elle est absente', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await ouvrirConnexion(page);

    const corps: Array<Record<string, unknown>> = [
      { reessayerDansSecondes: 90 },
      { reessayerDansSecondes: 30 },
      {},
    ];
    const attendus = [
      'Trop de tentatives de connexion. Réessayez dans 2 minutes.',
      'Trop de tentatives de connexion. Réessayez dans 1 minute.',
      'Trop de tentatives de connexion. Réessayez plus tard.',
    ];
    let indice = 0;
    await page.route('**/api/connexion', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue();
        return;
      }
      await route.fulfill({
        status: 429,
        contentType: 'application/problem+json',
        headers: { 'Retry-After': '30' },
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Trop de tentatives',
          status: 429,
          detail: 'Trop de tentatives de connexion. Réessayez plus tard.',
          code: 'TENTATIVES_EXCESSIVES',
          ...corps[indice],
        }),
      });
    });

    for (; indice < attendus.length; indice++) {
      await tenter(page, pseudo, MOT_DE_PASSE);
      const erreur = page.getByTestId('erreur-generale');
      await expect(erreur).toHaveText(attendus[indice]);
      await expect(erreur).not.toContainText('429');
      await expect(page.getByTestId('bouton-connexion')).toBeEnabled();
      await expect(page).toHaveURL(/\/connexion$/);
    }
  });

  test('CA24 - un blocage actif ne ferme pas une session déjà ouverte du même compte (non-régression RG15)', async ({ page, browser, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);

    const autre = await browser.newPage();
    try {
      await ouvrirConnexion(autre);
      await cinqEchecs(autre, pseudo);
      await tenter(autre, pseudo, MOT_DE_PASSE);
      await verifierEtatBloque(autre, pseudo);
    } finally {
      await autre.close();
    }

    await page.reload();
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  });
});
