import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * Incrément 8.5 : plafond de créations de Compte par adresse. Le 429 réel n'est pas testé ici
 * (limite à 1000 sur la stack E2E, adresse partagée) : la réponse est fabriquée par page.route.
 */
const MOT_DE_PASSE_VALIDE = 'un-mot-de-passe-12';
const MESSAGE_GENERIQUE = 'Service indisponible, veuillez réessayer plus tard.';

function pseudoUnique(): string {
  return `plafond-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
}

async function ouvrirFormulaire(page: Page): Promise<void> {
  const csrf = page.waitForResponse((r) => r.url().includes('/api/csrf'));
  await page.goto('/creer-compte');
  await csrf;
  await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
}

async function remplir(page: Page, pseudo: string): Promise<void> {
  await page.getByTestId('champ-pseudo').fill(pseudo);
  await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE_VALIDE);
  await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE_VALIDE);
}

async function refuserEn429(page: Page, corps: Record<string, unknown>): Promise<(route: Route) => Promise<void>> {
  const gestionnaire = async (route: Route) => {
    if (route.request().method() !== 'POST') return route.continue();
    await route.fulfill({
      status: 429,
      contentType: 'application/problem+json',
      headers: { 'Retry-After': '37' },
      body: JSON.stringify({ status: 429, code: 'TROP_DE_REQUETES', title: 'Trop de requêtes', ...corps }),
    });
  };
  await page.route('**/api/comptes', gestionnaire);
  return gestionnaire;
}

test.describe('Créer un compte : plafond par adresse', () => {
  test('CA19 - le 429 affiche le message de plafond, conserve le pseudo, puis un nouvel envoi réussit', async ({ page }) => {
    const pseudo = pseudoUnique();
    await ouvrirFormulaire(page);
    const gestionnaire = await refuserEn429(page, { reessayerDansSecondes: 37 });

    await remplir(page, pseudo);
    await page.getByTestId('bouton-creer-compte').click();

    const erreur = page.getByTestId('erreur-generale');
    await expect(erreur).toHaveText('Trop de créations de compte depuis cette connexion. Réessayez dans 1 minute.');
    await expect(erreur).not.toContainText('429');
    await expect(erreur).not.toContainText('TROP_DE_REQUETES');
    await expect(page.getByTestId('champ-pseudo')).toHaveValue(pseudo);
    await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('champ-confirmation')).toHaveValue('');
    await expect(page.getByTestId('bouton-creer-compte')).toBeEnabled();
    await expect(page.getByTestId('message-succes')).toHaveCount(0);

    await page.unroute('**/api/comptes', gestionnaire);
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE_VALIDE);
    await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE_VALIDE);
    await page.getByTestId('bouton-creer-compte').click();

    await expect(page.getByTestId('message-succes')).toContainText(`Compte créé pour ${pseudo}.`);
    await expect(page.getByTestId('erreur-generale')).toHaveCount(0);
  });

  test('CA19 - variante : 429 sans reessayerDansSecondes, message « Réessayez plus tard. »', async ({ page }) => {
    await ouvrirFormulaire(page);
    await refuserEn429(page, {});

    await remplir(page, pseudoUnique());
    await page.getByTestId('bouton-creer-compte').click();

    await expect(page.getByTestId('erreur-generale')).toHaveText(
      'Trop de créations de compte depuis cette connexion. Réessayez plus tard.',
    );
    await expect(page.getByTestId('bouton-creer-compte')).toBeEnabled();
  });

  test('CA19 - variante : 429 text/plain, message générique', async ({ page }) => {
    await ouvrirFormulaire(page);
    await page.route('**/api/comptes', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      await route.fulfill({ status: 429, contentType: 'text/plain', body: 'Too Many Requests' });
    });

    await remplir(page, pseudoUnique());
    await page.getByTestId('bouton-creer-compte').click();

    const erreur = page.getByTestId('erreur-generale');
    await expect(erreur).toHaveText(MESSAGE_GENERIQUE);
    await expect(erreur).not.toContainText('429');
  });
});
