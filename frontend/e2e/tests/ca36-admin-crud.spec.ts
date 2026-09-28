import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';
import { decodeQrImage } from '../fixtures/qr-decode';

/**
 * CA36 — Administration des courses et des coureurs (RG37, RG39, RG40, RG43).
 * L'admin est mémorisé uniquement en mémoire (RG7) : toute navigation complète (`page.goto`, rechargement)
 * l'efface. Ce test ne relogue donc jamais l'admin par un rechargement nu ; il repasse par `/connexion` avec
 * un paramètre `retour` (navigation interne du routeur après connexion) chaque fois qu'un changement d'écran
 * est nécessaire après une modification faite hors de l'interface (ici, les inscriptions par l'API).
 */
test.describe('@INC-4 @INC4-CA36 Administration des courses et des coureurs', () => {
  test.use({ serviceWorkers: 'block' });

  async function loginAdmin(page: import('@playwright/test').Page, retour?: string): Promise<void> {
    await page.goto(retour ? `/connexion?retour=${encodeURIComponent(retour)}` : '/connexion');
    await page.getByLabel("Nom d'utilisateur").fill('admin-test');
    await page.getByLabel('Mot de passe').fill('admin-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
  }

  test('création, erreurs, planche QR, modification, suppression, démarrage, action hors ligne', async ({
    page, context,
  }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const raceName = `E2E-CRUD-${run}`;

    await loginAdmin(page);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Administration des courses');

    // Création.
    await page.getByRole('button', { name: 'Créer une course' }).click();
    await page.getByLabel('Nom').fill(raceName);
    await page.getByLabel('Date').fill('2026-10-03');
    await page.getByLabel('Distance de boucle (m)').fill('6706');
    await page.getByLabel('Durée de boucle (s)').fill('3600');
    await expect(page.locator('output.preview')).toHaveText('1:00:00');
    await page.getByLabel('D+ par boucle (m)').fill('50');
    await page.getByRole('button', { name: 'Créer la course' }).click();
    const raceCard = page.locator('li.card').filter({ hasText: raceName });
    await expect(raceCard).toBeVisible();
    await expect(raceCard).toContainText('Non démarrée');

    // Distance à 0 : erreur ciblée sous le champ distance (écart mineur comblé, reprise du 2026-09-27),
    // aucune course créée.
    const before = await countAdminRaces(page);
    await page.getByRole('button', { name: 'Créer une course' }).click();
    await page.getByLabel('Nom').fill(`${raceName}-zero`);
    await page.getByLabel('Date').fill('2026-10-03');
    await page.getByLabel('Distance de boucle (m)').fill('0');
    await page.getByLabel('Durée de boucle (s)').fill('3600');
    await page.getByLabel('D+ par boucle (m)').fill('10');
    await page.getByRole('button', { name: 'Créer la course' }).click();
    const distanceInput = page.getByLabel('Distance de boucle (m)');
    const distanceErrorId = await distanceInput.getAttribute('aria-describedby');
    expect(distanceErrorId).not.toBeNull();
    const distanceError = page.locator(`#${distanceErrorId}`);
    await expect(distanceError).not.toHaveText('');
    // Les autres champs, valides, ne portent aucune erreur.
    await expect(page.locator(`#${await page.getByLabel('Nom').getAttribute('aria-describedby')}`)).toHaveText('');
    await page.getByRole('button', { name: 'Annuler' }).click();
    expect(await countAdminRaces(page)).toBe(before);

    // Doublon de nom : le bandeau affiche exactement le `detail` renvoyé par le 409 (écart mineur comblé).
    let duplicateDetail: string | null = null;
    page.on('response', (response) => {
      const request = response.request();
      if (request.method() === 'POST' && request.url().endsWith('/api/admin/races') && response.status() === 409) {
        void response.json().then((body: { detail?: string }) => {
          duplicateDetail = body.detail ?? null;
        }).catch(() => undefined);
      }
    });
    await page.getByRole('button', { name: 'Créer une course' }).click();
    await page.getByLabel('Nom').fill(raceName);
    await page.getByLabel('Date').fill('2026-10-03');
    await page.getByLabel('Distance de boucle (m)').fill('1000');
    await page.getByLabel('Durée de boucle (s)').fill('3600');
    await page.getByLabel('D+ par boucle (m)').fill('10');
    await page.getByRole('button', { name: 'Créer la course' }).click();
    await expect(page.locator('.banner-error')).toContainText(/./);
    await expect.poll(() => duplicateDetail).not.toBeNull();
    await expect(page.locator('.banner-error')).toHaveText(duplicateDetail as string);
    await page.getByRole('button', { name: 'Annuler' }).click();

    await raceCard.getByRole('link', { name: 'Gérer la course et les coureurs' }).click();
    // La navigation (interne, via le routeur Angular) met à jour l'URL de façon asynchrone : on attend le
    // contenu de l'écran cible avant de lire `page.url()`, sans quoi celle-ci peut encore être l'ancienne.
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(raceName);
    const raceIdMatch = /\/admin\/courses\/(\d+)/.exec(page.url());
    const raceId = Number(raceIdMatch?.[1]);
    expect(raceId).toBeGreaterThan(0);

    // Deux inscriptions par l'API (hors interface) : ré-authentification directement sur la planche QR pour
    // voir ces coureurs sans perdre la session admin sur un rechargement nu (RG7, voir note d'en-tête).
    const runner1 = await api.register(raceId, 'Coureur Un');
    const runner2 = await api.register(raceId, 'Coureur Deux');
    await loginAdmin(page, `/admin/courses/${raceId}/qr`);
    await expect(page.locator('li.qr-card')).toHaveCount(2);
    const runners = await api.adminRunners(raceId);
    for (const runner of runners) {
      const card = page.locator('li.qr-card').filter({ hasText: String(runner.bib) });
      const decoded = await decodeQrImage(card, 'img.qr-image');
      expect(decoded).toBe(runner.qrToken);
    }

    await loginAdmin(page, `/admin/courses/${raceId}`);
    const runnerOneCard = page.locator('li.card').filter({ hasText: 'Coureur Un' });
    await expect(runnerOneCard.locator('app-qr-image')).toHaveCount(0);
    await runnerOneCard.getByRole('button', { name: 'Afficher le QR' }).click();
    const qrDialog = page.locator('app-confirm-dialog').filter({ hasText: 'QR code du coureur' });
    await expect(qrDialog.locator('p.token')).toHaveText(runner1.qrToken);
    await qrDialog.getByRole('button', { name: 'Fermer' }).click();

    // Modification du dossard 2 en 5. En mode édition, la carte devient un formulaire : son texte visible
    // ("Coureur Deux") disparaît (remplacé par des libellés et des valeurs de champs), donc les champs sont
    // ciblés par leur identifiant stable (`#bib-{id}`, `#name-{id}`, RunnerAdminPage), pas par le texte de
    // la carte.
    const runnerTwoCard = page.locator('li.card').filter({ hasText: 'Coureur Deux' });
    await runnerTwoCard.getByRole('button', { name: 'Modifier' }).click();
    const bibInput = page.locator(`#bib-${runner2.runnerId}`);
    await bibInput.fill('5');
    // Un seul coureur est en édition à la fois : le bouton "Enregistrer" est sans ambiguïté sur la page.
    await page.getByRole('button', { name: 'Enregistrer' }).click();
    await expect(page.locator('li.card').filter({ hasText: 'Coureur Deux' })).toContainText('5');

    // Le dossard 5 est bien relu par E13 (écart mineur comblé, reprise du 2026-09-27), pas seulement affiché
    // par l'écran qui vient de l'enregistrer.
    const runnersAfterRename = await api.adminRunners(raceId);
    const renamedRunner = runnersAfterRename.find((entry) => entry.id === runner2.runnerId);
    expect(renamedRunner?.bib).toBe(5);

    // Suppression du dossard 5.
    await page.locator('li.card').filter({ hasText: 'Coureur Deux' }).getByRole('button', { name: 'Supprimer' }).click();
    await page.locator('app-confirm-dialog').filter({ hasText: 'Supprimer le coureur' })
      .getByRole('button', { name: 'Supprimer' }).click();
    await expect(page.locator('li.card').filter({ hasText: 'Coureur Deux' })).toHaveCount(0);
    expect((await api.adminRunners(raceId)).length).toBe(1);

    // Démarrage.
    let startRequests = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith(`/api/admin/races/${raceId}/start`)) {
        startRequests += 1;
      }
    });
    // Le bouton déclencheur (dans la barre d'outils) et le bouton de confirmation (dans le dialogue, toujours
    // présent dans le DOM même fermé) portent tous deux le texte « Démarrer » : on cible le premier par sa
    // barre d'outils, jamais par rôle seul sur toute la page.
    const startTrigger = page.locator('.toolbar').getByRole('button', { name: 'Démarrer' });
    const startDialog = page.locator('app-confirm-dialog').filter({ hasText: 'Démarrer la course' });
    await startTrigger.click();
    await startDialog.getByRole('button', { name: 'Annuler' }).click();
    expect(startRequests).toBe(0);

    await startTrigger.click();
    await startDialog.getByRole('button', { name: 'Démarrer' }).click();
    await expect(page.getByText('En cours', { exact: false })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('départ à', { exact: false })).toBeVisible();
    await expect(startTrigger).toHaveCount(0);
    await expect(page.locator('.toolbar').getByRole('button', { name: 'Supprimer' })).toHaveCount(0);

    // Action hors ligne : message dédié, aucune mise en file, nom inchangé après reconnexion.
    const raceBefore = await api.race(raceId);
    await context.setOffline(true);
    // Le premier ".toolbar" est celui de la course (avant la liste des coureurs, qui a chacun son propre
    // bouton "Modifier").
    await page.locator('.toolbar').first().getByRole('button', { name: 'Modifier' }).click();
    await page.getByLabel('Nom').fill(`${raceName}-modifie`);
    await page.getByRole('button', { name: 'Enregistrer' }).click();
    await expect(page.getByText('Action impossible : serveur injoignable', { exact: false }))
      .toBeVisible({ timeout: 10_000 });
    await context.setOffline(false);
    const raceAfter = await api.race(raceId);
    expect(raceAfter.name).toBe(raceBefore.name);
  });
});

async function countAdminRaces(page: import('@playwright/test').Page): Promise<number> {
  return page.locator('ul.card-list > li.card').count();
}
