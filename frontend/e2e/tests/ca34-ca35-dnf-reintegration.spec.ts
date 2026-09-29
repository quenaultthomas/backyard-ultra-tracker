import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA34 — DNF manuel avec confirmation (RG41, RG38, RG43) et CA35 — Réintégration avec confirmation (RG42).
 * CA35 est la suite directe de CA34 dans la spec (« Suite de CA34 : Bob DNF au yard 1, course au yard 1 ») :
 * les deux critères sont vérifiés dans un seul parcours admin continu.
 */
test.describe('@INC-4 @smoke @INC4-CA34 @INC4-CA35 DNF manuel puis réintégration, avec confirmation', () => {
  test('DNF avec confirmation (annulation, Échap, confirmation), puis réintégration', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-ADM-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const bob = await api.register(race.id, 'Bob Adm');
    await api.startRace(race.id);

    // L'admin est mémorisé uniquement en mémoire (RG7) : une navigation complète (page.goto) après connexion
    // le perdrait. On se connecte directement avec l'écran de la course comme destination (paramètre
    // `retour`), pour n'effectuer qu'une seule navigation complète, suivie d'une navigation interne.
    await page.goto(`/connexion?retour=${encodeURIComponent(`/admin/courses/${race.id}`)}`);
    await page.getByLabel("Nom d'utilisateur").fill('admin-test');
    await page.getByLabel('Mot de passe').fill('admin-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(`E2E-ADM-${run}`);

    const bobCard = page.locator('li.card').filter({ hasText: bob.name });
    let dnfRequests = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith(`/api/admin/runners/${bob.runnerId}/dnf`)) {
        dnfRequests += 1;
      }
    });

    // Dialogue : dossard + nom, exactement 3 raisons, aucune présélectionnée, confirmer désactivé.
    // Remarque : les assertions de visibilité portent sur l'élément <dialog> natif lui-même, pas sur le
    // composant <app-confirm-dialog> qui l'englobe — une fois ouvert via showModal(), le <dialog> est promu
    // dans le « top layer » du navigateur et ne contribue plus à la boîte englobante de son parent, que
    // Playwright rapporterait alors (à tort) comme invisible.
    await bobCard.getByRole('button', { name: 'Déclarer DNF' }).click();
    const dnfDialog = page.locator('app-confirm-dialog').filter({ hasText: 'Déclarer DNF' });
    const dnfDialogElement = dnfDialog.locator('dialog');
    await expect(dnfDialog).toContainText(`${bob.bib} — ${bob.name}`);
    const reasons = dnfDialog.locator('input[name="dnf-reason"]');
    await expect(reasons).toHaveCount(3);
    for (const value of ['VOLUNTARY', 'MANUAL', 'OTHER']) {
      await expect(page.locator(`#dnf-reason-${value}`)).not.toBeChecked();
    }
    const confirmDnfButton = dnfDialog.getByRole('button', { name: 'Confirmer le DNF' });
    await expect(confirmDnfButton).toBeDisabled();

    // Annuler : aucune requête E17.
    await dnfDialog.getByRole('button', { name: 'Annuler' }).click();
    await expect(dnfDialogElement).toBeHidden();
    expect(dnfRequests).toBe(0);
    let runners = await api.adminRunners(race.id);
    expect(runners.find((r) => r.id === bob.runnerId)?.status).toBe('ACTIVE');

    // Échap : même effet.
    await bobCard.getByRole('button', { name: 'Déclarer DNF' }).click();
    await expect(dnfDialogElement).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dnfDialogElement).toBeHidden();
    expect(dnfRequests).toBe(0);

    // Réouverture, choix "Abandon volontaire", double clic : une seule requête E17.
    await bobCard.getByRole('button', { name: 'Déclarer DNF' }).click();
    await page.locator('#dnf-reason-VOLUNTARY').check();
    await expect(confirmDnfButton).toBeEnabled();
    // Double clic quasi simultané (CL14) : `dispatchEvent` déclenche l'événement directement, sans attendre
    // l'actionabilité (un `.click()` classique échouerait sur le second appel dès que le bouton est
    // désactivé pendant la requête, RG41).
    await Promise.all([confirmDnfButton.dispatchEvent('click'), confirmDnfButton.dispatchEvent('click')]);
    await expect(page.locator('.banner-success')).toContainText('DNF abandon volontaire au yard 1', { timeout: 10_000 });
    expect(dnfRequests).toBe(1);

    runners = await api.adminRunners(race.id);
    const bobAfterDnf = runners.find((r) => r.id === bob.runnerId);
    expect(bobAfterDnf?.status).toBe('DNF');
    expect(bobAfterDnf?.dnfReason).toBe('VOLUNTARY');
    expect(bobAfterDnf?.dnfYard).toBe(1);

    // Aucun bouton de DNF sur /scan : un nouvel onglet (la session ADMIN, en mémoire seulement, RG7, ne doit
    // pas être perdue sur la page d'administration restée ouverte).
    const scanPage = await page.context().newPage();
    await scanPage.goto('/scan');
    await expect(scanPage.getByRole('button', { name: 'Déclarer DNF' })).toHaveCount(0);
    await scanPage.close();

    // CA35 — Réintégration (même page admin, jamais rechargée depuis la connexion).
    let reintegrationRequests = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith(`/api/admin/runners/${bob.runnerId}/reintegration`)) {
        reintegrationRequests += 1;
      }
    });

    await bobCard.getByRole('button', { name: 'Réintégrer' }).click();
    const reintegrationDialog = page.locator('app-confirm-dialog').filter({ hasText: 'Réintégrer' });
    await expect(reintegrationDialog.locator('dialog')).toBeVisible();
    await reintegrationDialog.getByRole('button', { name: 'Annuler' }).click();
    expect(reintegrationRequests).toBe(0);

    await bobCard.getByRole('button', { name: 'Réintégrer' }).click();
    await reintegrationDialog.getByRole('button', { name: 'Confirmer' }).click();
    await expect(page.locator('.banner-success')).toContainText('Aucun passage à recréer', { timeout: 10_000 });
    await expect(bobCard).toContainText('En course');
    expect(reintegrationRequests).toBe(1);

    runners = await api.adminRunners(race.id);
    expect(runners.find((r) => r.id === bob.runnerId)?.status).toBe('ACTIVE');
  });
});
