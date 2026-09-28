import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA37 — Deux courses en parallèle (RG35, CL5).
 *
 * Reprise du 2026-09-27 (action corrective 4, arbitrage BUG-3) : les deux onglets de tableau de bord sont
 * remis dans le **même contexte de navigateur** que l'écran `/scan` (même appareil), ouverts **avant** lui,
 * comme l'exige littéralement la spec (RG21 « émetteur effectif », CL12). La version précédente les plaçait
 * dans des navigateurs séparés pour contourner BUG-3 (un onglet de tableau de bord, non authentifié, pouvait
 * devenir l'émetteur et bloquer indéfiniment l'envoi d'un scan capturé sur `/scan`). BUG-3 est corrigé
 * (`core/emitter-role.ts`, `ScanQueue.refresh()` relance désormais `process()`) : ce test vérifie donc à la
 * fois CA37 (indépendance de deux courses) et, en creux, que la correction de BUG-3 permet bien à ce scénario
 * de se dérouler comme la spec le décrit littéralement.
 *
 * BUG-2 (voir CA29, CA31, CA43 ; docs/tests/rapports/INC-4-e2e.md) : la spec décrit chaque course avec un
 * seul coureur (Alice P1 seule, Alice P2 seule). Or la règle métier « course à un seul coureur : victoire dès
 * le premier closing » (`YardClosingServiceTest#ca39_singleRunnerRaceWinsAtFirstClosing`, INC-2) ferait gagner
 * Alice P1 dès la clôture du yard 1, empêchant d'observer « Yard 2 » toujours `RUNNING` comme l'attend CA37.
 * Un second coureur (Bob), qui termine les mêmes yards qu'Alice, est ajouté à chaque course pour rester dans
 * le cas CA34 (« au moins deux finishers, la course continue »). Le tableau de bord liste donc 2 coureurs par
 * course et non 1 comme l'énonce littéralement la spec ; les assertions ci-dessous sont adaptées en
 * conséquence, sans affaiblir ce que CA37 vérifie réellement (indépendance des deux courses, tableaux de bord
 * distincts, comptes à rebours différents).
 */
test.describe('@INC-4 @INC4-CA37 Deux courses en parallèle', () => {
  test('scans sur un seul écran, deux onglets de tableau de bord ouverts avant /scan, même appareil', async ({
    page,
  }, testInfo) => {
    test.setTimeout(120_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const p1 = await api.createRace({ name: `E2E-P1-${run}`, loopDistance: 1000, loopDuration: 30, loopElevation: 10 });
    const p2 = await api.createRace({ name: `E2E-P2-${run}`, loopDistance: 2000, loopDuration: 45, loopElevation: 20 });
    const aliceP1 = await api.register(p1.id, 'Alice P1');
    const bobP1 = await api.register(p1.id, 'Bob P1');
    const aliceP2 = await api.register(p2.id, 'Alice P2');
    const bobP2 = await api.register(p2.id, 'Bob P2');

    const started1 = await api.startRace(p1.id);
    const t1 = Date.parse(started1.startedAt!);
    await waitUntil(t1 + 10_000);
    await api.startRace(p2.id);

    // Deux onglets de tableau de bord, un par course, dans le même contexte de navigateur que /scan (même
    // appareil), ouverts AVANT lui (RG21 « émetteur effectif », CL12).
    const context = page.context();
    const boardP1 = await context.newPage();
    await boardP1.goto(`/courses/${p1.id}`);
    const boardP2 = await context.newPage();
    await boardP2.goto(`/courses/${p2.id}`);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await waitUntil(t1 + 15_000);
    await page.getByLabel('Code du QR').fill(aliceP1.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('Dossard 1 — Alice P1 — yard 1', { exact: false })).toBeVisible({ timeout: 15_000 });
    await api.scan(bobP1.qrToken, new Date().toISOString());

    await waitUntil(t1 + 17_000);
    await page.getByLabel('Code du QR').fill(aliceP2.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('Dossard 1 — Alice P2 — yard 1', { exact: false })).toBeVisible({ timeout: 15_000 });
    await api.scan(bobP2.qrToken, new Date().toISOString());

    await expect(boardP1.getByText('Yard 2', { exact: false })).toBeVisible({ timeout: 30_000 });
    await expect(boardP2.getByText('Yard 1', { exact: false })).toBeVisible();

    // P1 ne liste que les coureurs de P1 (Alice et Bob), pas ceux de P2, et réciproquement (RG35).
    const rowP1 = boardP1.locator('tr').filter({ hasText: 'Alice P1' });
    await expect(rowP1).toContainText('1,00 km');
    await expect(rowP1).toContainText('10 m D+');
    await expect(boardP1.locator('tbody tr')).toHaveCount(2);
    await expect(boardP1.getByText('Alice P2')).toHaveCount(0);

    const rowP2 = boardP2.locator('tr').filter({ hasText: 'Alice P2' });
    await expect(rowP2).toContainText('2,00 km');
    await expect(rowP2).toContainText('20 m D+');
    await expect(boardP2.locator('tbody tr')).toHaveCount(2);
    await expect(boardP2.getByText('Alice P1')).toHaveCount(0);

    const countdown1 = await boardP1.locator('.countdown').textContent();
    const countdown2 = await boardP2.locator('.countdown').textContent();
    expect(countdown1).not.toBe(countdown2);

    const home = await context.newPage();
    await home.goto('/');
    const p1Card = home.locator('li.card').filter({ hasText: p1.name });
    const p2Card = home.locator('li.card').filter({ hasText: p2.name });
    await expect(p1Card).toContainText('En cours');
    await expect(p2Card).toContainText('En cours');
  });
});

function waitUntil(targetMs: number): Promise<void> {
  const delay = Math.max(0, targetMs - Date.now());
  return new Promise((resolve) => setTimeout(resolve, delay));
}
