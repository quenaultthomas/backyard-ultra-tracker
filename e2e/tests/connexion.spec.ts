import { expect, test } from '@playwright/test';
import { fermerMenuCompte, ouvrirMenuCompte, ouvrirMenuVisiteur, seDeconnecterParLeMenu } from './aide-entete';
import {
  MOT_DE_PASSE,
  creerCompteParApi,
  ouvrirConnexion,
  pseudoUnique,
  saisir,
  seConnecter,
} from './aide-connexion';

test.describe('Se connecter et se déconnecter (1.2)', () => {
  test('1.2 CA28 - le coureur se connecte, son pseudo apparaît dans l\'en-tête et résiste au rechargement', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);

    await ouvrirConnexion(page);
    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
    await ouvrirMenuCompte(page);
    await expect(page.getByTestId('bouton-deconnexion')).toBeVisible();
    await fermerMenuCompte(page);
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(0);
    await expect(page.getByTestId('menu-compte')).toHaveCount(1);

    await page.reload();
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
    await ouvrirMenuCompte(page);
    await expect(page.getByTestId('bouton-deconnexion')).toBeVisible();
    await fermerMenuCompte(page);
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(0);
    await expect(page.getByTestId('menu-compte')).toHaveCount(1);
  });

  test('1.2 CA28 - la connexion avec le pseudo en majuscules donne le même résultat', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);

    await ouvrirConnexion(page);
    await saisir(page, pseudo.toUpperCase(), MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
    await ouvrirMenuCompte(page);
    await expect(page.getByTestId('bouton-deconnexion')).toBeVisible();
    await fermerMenuCompte(page);
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(0);
    await expect(page.getByTestId('menu-compte')).toHaveCount(1);
  });

  test('1.2 CA29 - mauvais mot de passe et pseudo inexistant donnent le même message générique', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    const fantome = pseudoUnique('fantome');
    await creerCompteParApi(request, pseudo);
    await ouvrirConnexion(page);

    for (const [pseudoSaisi, motDePasse] of [
      [pseudo, 'mauvais-mot-de-passe-1'],
      [fantome, MOT_DE_PASSE],
    ]) {
      await saisir(page, pseudoSaisi, motDePasse);
      await page.getByTestId('bouton-connexion').click();
      await expect(page.getByTestId('erreur-generale')).toHaveText('Pseudo ou mot de passe incorrect.');
      await expect(page.getByTestId('champ-pseudo')).toHaveValue(pseudoSaisi);
      await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
      await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
      await expect(page).toHaveURL(/\/connexion$/);
    }
  });

  test('1.2 CA30 - la déconnexion ramène sur /connexion avec un message et une reconnexion possible', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);

    await seDeconnecterParLeMenu(page);

    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('message-deconnexion')).toHaveText('Vous êtes déconnecté.');
    await expect(page.getByTestId('bouton-menu')).toBeVisible();
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);

    await page.reload();
    await expect(page.getByTestId('bouton-menu')).toBeVisible();
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await expect(page.getByTestId('message-deconnexion')).toHaveCount(0);

    await seConnecter(page, pseudo);
  });

  test('1.2 CA31 - la redirection retour est limitée aux chemins internes et aux pages autorisées', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    const baseURL = process.env.BASE_URL ?? 'http://localhost';

    for (const retour of ['/creer-compte', '//exemple.org', 'https://exemple.org']) {
      await ouvrirConnexion(page, `/connexion?retour=${encodeURIComponent(retour)}`);
      await saisir(page, pseudo, MOT_DE_PASSE);
      await page.getByTestId('bouton-connexion').click();

      await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
      await expect(page).toHaveURL(/\/$/);
      expect(new URL(page.url()).origin).toBe(new URL(baseURL).origin);

      await seDeconnecterParLeMenu(page);
      await expect(page.getByTestId('message-deconnexion')).toBeVisible();
    }
  });

  test('1.2 CA32 - connecté, /connexion et /creer-compte redirigent vers l\'accueil sans lien Créer un compte', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);

    await page.goto('/connexion');
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);

    await page.goto('/creer-compte');
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);

    await expect(page.getByTestId('titre')).toHaveText('Backyard Ultra Tracker');
    await expect(page.getByTestId('lien-creer-compte')).toHaveCount(0);
  });

  test('1.2 CA33 - le visiteur anonyme navigue entre accueil, connexion et création de compte', async ({ page }) => {
    const pseudo = pseudoUnique();
    await page.goto('/');
    await expect(page.getByTestId('entete-titre')).toBeVisible();
    await expect(page.getByTestId('bouton-menu')).toBeVisible();
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
    await expect(page.getByTestId('lien-creer-compte')).toHaveCount(0);

    await ouvrirMenuVisiteur(page);
    await expect(page.getByTestId('menu-lien-creer-compte')).toBeVisible();
    await page.getByTestId('menu-lien-se-connecter').click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();

    await page.getByTestId('lien-creer-compte-connexion').click();
    await expect(page).toHaveURL(/\/creer-compte$/);
    await expect(page.getByTestId('titre-creer-compte')).toBeVisible();

    await page.getByTestId('champ-pseudo').fill(pseudo);
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
    await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
    await page.getByTestId('bouton-creer-compte').click();

    await expect(page.getByTestId('message-succes')).toHaveText(
      `Compte créé pour ${pseudo}. Vous pouvez maintenant vous connecter.`,
    );
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await page.getByTestId('lien-se-connecter-succes').click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
  });

  test('1.2 CA33 - le lien Retour à l\'accueil de la connexion ramène sur /', async ({ page }) => {
    await ouvrirConnexion(page);
    await page.getByTestId('lien-accueil').click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('titre')).toBeVisible();
  });

  test('1.2 CA34 - champs vides refusés côté client, sans appel à POST /api/connexion', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    let posts = 0;
    await page.route('**/api/connexion', async (route) => {
      if (route.request().method() === 'POST') posts++;
      await route.continue();
    });
    await ouvrirConnexion(page);

    await saisir(page, '', MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page.getByTestId('erreur-pseudo')).toHaveText('Le pseudo est obligatoire.');

    await saisir(page, pseudo, '');
    await page.getByTestId('bouton-connexion').click();
    await expect(page.getByTestId('erreur-mot-de-passe')).toHaveText('Le mot de passe est obligatoire.');

    expect(posts).toBe(0);
  });

  test('1.2 CA34 - le jeton CSRF expiré est renouvelé et le second envoi réussit', async ({ page, context, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await ouvrirConnexion(page);
    await context.clearCookies({ name: 'XSRF-TOKEN' });
    expect((await context.cookies()).map((c) => c.name)).not.toContain('XSRF-TOKEN');

    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page.getByTestId('erreur-generale')).toHaveText('La page a expiré, veuillez réessayer.');

    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  });

  test('1.2 CA34 - le service indisponible affiche un message générique et réactive le bouton', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await ouvrirConnexion(page);
    await page.route('**/api/connexion', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 503, contentType: 'text/plain', body: 'Service Unavailable' });
      } else {
        await route.continue();
      }
    });

    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();

    const erreur = page.getByTestId('erreur-generale');
    await expect(erreur).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(erreur).not.toContainText('503');
    await expect(page.getByTestId('bouton-connexion')).toBeEnabled();
  });

  test('1.2 CA35 - le cookie de session est protégé et aucun secret n\'est stocké côté navigateur', async ({ page, context, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);

    const session = (await context.cookies()).find((c) => c.name === 'JSESSIONID');
    expect(session).toBeDefined();
    expect(session!.httpOnly).toBe(true);
    expect(session!.sameSite).toBe('Lax');

    const navigateur = await page.evaluate(() => ({
      cookie: document.cookie,
      local: JSON.stringify({ ...localStorage }),
      session: JSON.stringify({ ...sessionStorage }),
    }));
    expect(navigateur.cookie).not.toContain('JSESSIONID');
    expect(navigateur.local).not.toContain(MOT_DE_PASSE);
    expect(navigateur.session).not.toContain(MOT_DE_PASSE);
    expect(navigateur.local).not.toContain(pseudo);
    expect(navigateur.session).not.toContain(pseudo);
    expect(page.url()).not.toContain(MOT_DE_PASSE);
  });

  test('1.2 CA36 - sans cookie de session, le rechargement repasse en anonyme', async ({ page, context, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);

    await context.clearCookies({ name: 'JSESSIONID' });
    await page.reload();

    await expect(page.getByTestId('bouton-menu')).toBeVisible();
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
  });

  test('1.2 CA36 - GET /api/comptes/moi en 503 : l\'application reste utilisable en anonyme', async ({ page }) => {
    await page.route('**/api/comptes/moi', async (route) => {
      await route.fulfill({ status: 503, contentType: 'text/plain', body: 'Service Unavailable' });
    });
    await page.goto('/');

    await expect(page.getByTestId('titre')).toHaveText('Backyard Ultra Tracker');
    await expect(page.getByTestId('bouton-menu')).toBeVisible();
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await ouvrirMenuVisiteur(page);
    await page.getByTestId('menu-lien-se-connecter').click();
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
  });

  test('1.2 CA30 - échec de déconnexion en 403 CSRF_INVALIDE : message dans l\'en-tête, jeton redemandé, utilisateur toujours connecté', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);
    await page.route('**/api/deconnexion', async (route) => {
      await route.fulfill({
        status: 403,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Accès refusé',
          status: 403,
          detail: 'Jeton CSRF absent ou invalide.',
          code: 'CSRF_INVALIDE',
        }),
      });
    });

    const nouveauJeton = page.waitForRequest((r) => r.url().includes('/api/csrf'));
    await seDeconnecterParLeMenu(page);
    await nouveauJeton;

    await expect(page.getByTestId('entete-erreur')).toHaveText('La page a expiré, veuillez réessayer.');
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
    await ouvrirMenuCompte(page);
    await expect(page.getByTestId('bouton-deconnexion')).toBeVisible();
    await fermerMenuCompte(page);
    await expect(page).toHaveURL(/\/$/);
  });

  test('1.2 CA30 - échec de déconnexion en 503 : message dans l\'en-tête, utilisateur toujours connecté', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);
    await seConnecter(page, pseudo);
    await page.route('**/api/deconnexion', async (route) => {
      await route.fulfill({ status: 503, contentType: 'text/plain', body: 'Service Unavailable' });
    });

    await seDeconnecterParLeMenu(page);

    const erreur = page.getByTestId('entete-erreur');
    await expect(erreur).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(erreur).not.toContainText('503');
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
    await ouvrirMenuCompte(page);
    await expect(page.getByTestId('bouton-deconnexion')).toBeVisible();
    await fermerMenuCompte(page);
  });

  test('1.2 CA36 - GET /api/comptes/moi sans réponse : en-tête vide pendant la restauration puis anonyme après 5 s', async ({ page }) => {
    await page.route('**/api/comptes/moi', async () => {
      // requête volontairement retenue : jamais fulfillée ni poursuivie
    });
    await page.goto('/');

    await expect(page.getByTestId('entete-titre')).toBeVisible();
    await expect(page.getByTestId('bouton-menu')).toHaveCount(0);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);

    await expect(page.getByTestId('bouton-menu')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await expect(page.getByTestId('titre')).toHaveText('Backyard Ultra Tracker');

    await ouvrirMenuVisiteur(page);
    await page.getByTestId('menu-lien-se-connecter').click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
  });
});
