import { expect, test } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique, seConnecter } from './aide-connexion';
import { connecterAdminMaster, exigerIdentifiantsAdminMaster } from './aide-admin';

test.describe('Transport, erreurs et connexion (7.3)', () => {
  test.beforeAll(() => exigerIdentifiantsAdminMaster());

  test('CA14 - en HTTP, aucune réponse ne porte Strict-Transport-Security', async ({ request }) => {
    for (const chemin of ['/', '/api/sante', '/api/courses']) {
      const reponse = await request.get(chemin);
      expect(reponse.status(), chemin).toBe(200);
      expect(reponse.headers()['strict-transport-security'], chemin).toBeUndefined();
    }
  });

  test('CA17 - une rafale de 20 connexions simultanées ne donne que 401 ou 503 SERVICE_SATURE, puis les connexions et créations de compte aboutissent', async ({ page, request, playwright }) => {
    const contexte = await playwright.request.newContext({ baseURL: test.info().project.use.baseURL });
    expect((await contexte.get('/api/csrf')).status()).toBe(204);
    const jeton = (await contexte.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value;
    const reponses = await Promise.all(
      Array.from({ length: 20 }, (_, i) =>
        contexte.post('/api/connexion', {
          headers: { 'X-XSRF-TOKEN': jeton },
          data: { pseudo: `inconnu-${Date.now()}-${i}`, motDePasse: MOT_DE_PASSE },
        }),
      ),
    );
    const statuts = reponses.map((r) => r.status());
    for (const statut of statuts) expect([401, 503]).toContain(statut);
    for (const r of reponses.filter((r) => r.status() === 503)) expect((await r.json()).code).toBe('SERVICE_SATURE');
    await contexte.dispose();

    // Une fois la rafale finie : admin master, coureur, création de compte.
    await expect(async () => {
      await connecterAdminMaster(page);
      await expect(page.getByTestId('titre-administration')).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 20_000 });

    const coureur = pseudoUnique();
    await creerCompteParApi(request, coureur);
    await page.context().clearCookies();
    await seConnecter(page, coureur);

    const nouveau = pseudoUnique();
    await page.context().clearCookies();
    const csrf = page.waitForResponse((r) => r.url().includes('/api/csrf'));
    await page.goto('/creer-compte');
    await csrf;
    await page.getByTestId('champ-pseudo').fill(nouveau);
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
    await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('message-succes')).toContainText(`Compte créé pour ${nouveau}.`);
  });
});
