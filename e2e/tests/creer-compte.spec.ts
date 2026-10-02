import { expect, test, type Page } from '@playwright/test';

const MOT_DE_PASSE_VALIDE = 'un-mot-de-passe-12';

function pseudoUnique(prefixe = 'coureur'): string {
  const suffixe = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
  return `${prefixe}-${suffixe}`;
}

async function ouvrirFormulaire(page: Page): Promise<void> {
  const csrf = page.waitForResponse((r) => r.url().includes('/api/csrf'));
  await page.goto('/creer-compte');
  await csrf;
  await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
}

async function remplir(page: Page, pseudo: string, motDePasse: string, confirmation = motDePasse): Promise<void> {
  await page.getByTestId('champ-pseudo').fill(pseudo);
  await page.getByTestId('champ-mot-de-passe').fill(motDePasse);
  await page.getByTestId('champ-confirmation').fill(confirmation);
}

test.describe('Créer un compte coureur', () => {
  test('CA22 - le visiteur accède au formulaire depuis l\'accueil', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('titre')).toHaveText('Backyard Ultra Tracker');
    await expect(page.getByTestId('etat-api')).toHaveText('API : disponible', { timeout: 5000 });
    await page.getByTestId('lien-creer-compte').click();
    await expect(page).toHaveURL(/\/creer-compte$/);
    await expect(page.getByTestId('titre-creer-compte')).toHaveText('Créer un compte');
  });

  test('CA23 - le visiteur crée un compte coureur', async ({ page, context }) => {
    const pseudo = pseudoUnique();
    await ouvrirFormulaire(page);
    await remplir(page, pseudo, MOT_DE_PASSE_VALIDE);
    await page.getByTestId('bouton-creer-compte').click();

    await expect(page.getByTestId('message-succes')).toContainText(`Compte créé pour ${pseudo}.`);
    await expect(page.getByTestId('champ-pseudo')).toHaveCount(0);
    await expect(page.getByTestId('bouton-creer-compte')).toHaveCount(0);

    const cookies = await context.cookies();
    expect(cookies.map((c) => c.name)).not.toContain('JSESSIONID');
    expect(page.url()).not.toContain(MOT_DE_PASSE_VALIDE);
    const stockage = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
    expect(stockage).not.toContain(MOT_DE_PASSE_VALIDE);
  });

  test('CA24 - le pseudo déjà utilisé (autre casse) est refusé', async ({ page, request }) => {
    const pseudo = pseudoUnique();
    // Préparation via l'API
    const csrf = await request.get('/api/csrf');
    expect(csrf.status()).toBe(204);
    const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
    expect(jeton).toBeTruthy();
    const creation = await request.post('/api/comptes', {
      headers: { 'X-XSRF-TOKEN': jeton! },
      data: { pseudo, motDePasse: MOT_DE_PASSE_VALIDE },
    });
    expect(creation.status()).toBe(201);

    await ouvrirFormulaire(page);
    const majuscules = pseudo.toUpperCase();
    await remplir(page, majuscules, MOT_DE_PASSE_VALIDE);
    await page.getByTestId('bouton-creer-compte').click();

    await expect(page.getByTestId('erreur-pseudo')).toHaveText('Ce pseudo est déjà utilisé.');
    await expect(page.getByTestId('champ-pseudo')).toHaveValue(majuscules);
    await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('champ-confirmation')).toHaveValue('');
  });

  test('CA25 - mot de passe trop court puis confirmation différente, sans appel API', async ({ page }) => {
    let postsComptes = 0;
    await page.route('**/api/comptes', async (route) => {
      if (route.request().method() === 'POST') postsComptes++;
      await route.continue();
    });
    await ouvrirFormulaire(page);

    await remplir(page, pseudoUnique('bob'), 'court-12345');
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('erreur-mot-de-passe')).toHaveText(
      'Le mot de passe doit faire au moins 12 caractères.',
    );

    await remplir(page, pseudoUnique('bob'), MOT_DE_PASSE_VALIDE, 'une-autre-saisie-12');
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('erreur-confirmation')).toHaveText('Les deux mots de passe ne correspondent pas.');

    expect(postsComptes).toBe(0);
  });

  test('CA26 - le pseudo invalide est refusé par le serveur, le pseudo vide par le client', async ({ page }) => {
    await ouvrirFormulaire(page);

    await remplir(page, 'a b', MOT_DE_PASSE_VALIDE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('erreur-pseudo')).toHaveText(
      'Le pseudo ne peut contenir que des lettres, des chiffres, « . », « _ » et « - ».',
    );

    await remplir(page, '', MOT_DE_PASSE_VALIDE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('erreur-pseudo')).toHaveText('Le pseudo est obligatoire.');
  });

  test('CA27 - le jeton CSRF expiré est renouvelé et le second envoi réussit', async ({ page, context }) => {
    const pseudo = pseudoUnique();
    await ouvrirFormulaire(page);
    await context.clearCookies({ name: 'XSRF-TOKEN' });
    expect((await context.cookies()).map((c) => c.name)).not.toContain('XSRF-TOKEN');

    await remplir(page, pseudo, MOT_DE_PASSE_VALIDE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('erreur-generale')).toHaveText('La page a expiré, veuillez réessayer.');

    await remplir(page, pseudo, MOT_DE_PASSE_VALIDE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('message-succes')).toContainText(`Compte créé pour ${pseudo}.`);
  });

  test('CA28 - le service indisponible affiche un message générique', async ({ page }) => {
    await ouvrirFormulaire(page);
    await page.route('**/api/comptes', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 503, contentType: 'text/plain', body: 'Service Unavailable' });
      } else {
        await route.continue();
      }
    });

    await remplir(page, pseudoUnique(), MOT_DE_PASSE_VALIDE);
    await page.getByTestId('bouton-creer-compte').click();

    const erreur = page.getByTestId('erreur-generale');
    await expect(erreur).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(erreur).not.toContainText('503');
    await expect(page.getByTestId('bouton-creer-compte')).toBeEnabled();
  });
});
