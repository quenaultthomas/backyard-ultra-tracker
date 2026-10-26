import { expect, test, type APIRequestContext } from '@playwright/test';
import { MOT_DE_PASSE_ADMIN_MASTER, PSEUDO_ADMIN_MASTER } from './aide-admin';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';

/*
 * Incrément 8.4 CA24 : plafond d'échecs de connexion par adresse (CONNEXION_ECHECS_PAR_IP_MAX=100 en E2E).
 *
 * Ce test n'utilise pas la fixture de `aide-horloge` : l'adresse étant plafonnée, toute nouvelle connexion de l'admin
 * master (nécessaire pour piloter l'horloge) serait refusée. La session master est donc ouverte AVANT les échecs et
 * réutilisée pour avancer puis remettre l'horloge. Le registre d'échecs ne s'efface que par écoulement de la fenêtre
 * (purge à la lecture, ici lors de la dernière connexion) : à la fin, le test vérifie par sondage que l'adresse est
 * libérée, pour ne pas perturber les tests suivants.
 */
test.describe.configure({ mode: 'serial' });
test.setTimeout(180_000);

const PLAFOND = 100;
const MESSAGE_PLAFOND = 'Trop de tentatives de connexion. Réessayez dans 1 minute.';
const BASE_URL = process.env.BASE_URL ?? 'http://localhost';

async function jetonCsrf(ctx: APIRequestContext): Promise<string> {
  const csrf = await ctx.get('/api/csrf');
  expect(csrf.status()).toBe(204);
  const jeton = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  return jeton!;
}

test('CA24 - le plafond d\'échecs par adresse refuse la connexion sur l\'écran puis se lève avec l\'horloge', async ({ page, request, playwright }) => {
  const master = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    // Session master ouverte avant le plafond, réutilisée pour piloter l'horloge.
    const jetonMaster = await jetonCsrf(master);
    const connexionMaster = await master.post('/api/connexion', {
      headers: { 'X-XSRF-TOKEN': jetonMaster },
      data: { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER },
    });
    expect(connexionMaster.status()).toBe(200);
    const avancer = async (duree: string) => {
      const r = await master.post('/api/test/horloge/avance', { headers: { 'X-XSRF-TOKEN': await jetonCsrf(master) }, data: { duree } });
      expect(r.status()).toBe(200);
    };
    const reinitialiser = async () => {
      const r = await master.delete('/api/test/horloge', { headers: { 'X-XSRF-TOKEN': await jetonCsrf(master) } });
      expect(r.status()).toBe(200);
    };

    await reinitialiser();
    // Fenêtre vide : plus aucun échec antérieur ne compte.
    await avancer('PT2M');
    const pseudo = pseudoUnique();
    await creerCompteParApi(request, pseudo);

    const jeton = await jetonCsrf(request);
    for (let i = 0; i < PLAFOND; i++) {
      const reponse = await request.post('/api/connexion', {
        headers: { 'X-XSRF-TOKEN': jeton },
        data: { pseudo: pseudoUnique('inexistant'), motDePasse: MOT_DE_PASSE },
      });
      expect(reponse.status(), `échec n°${i + 1}`).toBe(401);
    }

    // Écran : le bon mot de passe est refusé, sans révéler la cause.
    await ouvrirConnexion(page);
    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page.getByTestId('erreur-generale')).toHaveText(MESSAGE_PLAFOND);
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
    await expect(page.getByTestId('champ-pseudo')).toHaveValue(pseudo);
    await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
    await expect(page.getByTestId('bouton-connexion')).toBeEnabled();

    // Pseudo inexistant et mauvais mot de passe : même texte.
    await saisir(page, pseudoUnique('inexistant'), MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page.getByTestId('erreur-generale')).toHaveText(MESSAGE_PLAFOND);
    await saisir(page, pseudo, 'mauvais-mot-de-passe-1');
    await page.getByTestId('bouton-connexion').click();
    await expect(page.getByTestId('erreur-generale')).toHaveText(MESSAGE_PLAFOND);

    // Requête directe : 429 avec Retry-After entre 1 et 60.
    const directe = await request.post('/api/connexion', {
      headers: { 'X-XSRF-TOKEN': jeton },
      data: { pseudo, motDePasse: MOT_DE_PASSE },
    });
    expect(directe.status()).toBe(429);
    const retryApres = Number(directe.headers()['retry-after']);
    expect(retryApres).toBeGreaterThanOrEqual(1);
    expect(retryApres).toBeLessThanOrEqual(60);

    // La fenêtre s'écoule : la connexion aboutit.
    await avancer('PT2M');
    await saisir(page, pseudo, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  } finally {
    // Horloge remise à zéro (session master conservée), puis attente que l'adresse soit libérée en temps réel :
    // les échecs, datés de l'horloge applicative, redeviennent actifs le temps de la fenêtre.
    await master.delete('/api/test/horloge', { headers: { 'X-XSRF-TOKEN': await jetonCsrf(master) } });
    await expect
      .poll(
        async () => {
          const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
          try {
            const reponse = await ctx.post('/api/connexion', {
              headers: { 'X-XSRF-TOKEN': await jetonCsrf(ctx) },
              data: { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER },
            });
            return reponse.status();
          } finally {
            await ctx.dispose();
          }
        },
        { timeout: 90_000, intervals: [2_000] },
      )
      .toBe(200);
    await master.dispose();
  }
});
