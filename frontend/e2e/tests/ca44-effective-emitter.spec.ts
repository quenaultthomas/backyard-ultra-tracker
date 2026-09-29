import { expect, Page, Request, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA44 — Émetteur effectif avec plusieurs onglets sur le même appareil (RG21, RG22, CL12).
 * Ajouté le 2026-09-27 (BUG-3, verdict NO-GO de la première validation INC-4) : plusieurs onglets du même
 * contexte de navigateur, tableau de bord ouvert avant `/scan`. Vérifie la correction de BUG-3
 * (`core/emitter-role.ts`, `ScanQueue.refresh()` relance désormais `process()`).
 *
 * Étape 4 : ajoutée par l'arbitrage OBS-T2 du 2026-09-27 (RG50 complétée). La correction de production
 * correspondante (« le contexte de capture affiche le retour, qu'il soit émetteur ou non ») est en place :
 * cette étape passe réellement sur Chromium et WebKit (confirmé sur plusieurs exécutions consécutives,
 * `docs/tests/rapports/INC-4-e2e.md`).
 *
 * Service worker bloqué (`serviceWorkers: 'block'`, LIM-E2E-1) : le verrou d'émetteur et la diffusion entre
 * onglets (BroadcastChannel, Web Locks) n'en dépendent pas.
 */
test.describe('@INC-4 @INC4-CA44 Émetteur effectif avec plusieurs onglets', () => {
  test.use({ serviceWorkers: 'block' });

  test('un seul émetteur, aucun envoi bloqué, quel que soit l\'onglet qui capture', async ({ context }, testInfo) => {
    test.setTimeout(120_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-TABS-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const alice = await api.register(race.id, 'Alice');
    const bob = await api.register(race.id, 'Bob');
    const chloe = await api.register(race.id, 'Chloé');
    const dan = await api.register(race.id, 'Dan');
    await api.startRace(race.id);

    // Journal réseau des requêtes E6 sur tout le contexte (tous les onglets), avec l'instant de départ et de
    // fin de chaque requête, pour vérifier ensuite qu'aucune ne recouvre une autre (RG21).
    const scanRequests: { startedAt: number; endedAt: number | null }[] = [];
    context.on('request', (request: Request) => {
      if (request.method() === 'POST' && request.url().endsWith('/api/scan/passages')) {
        scanRequests.push({ startedAt: Date.now(), endedAt: null });
      }
    });
    context.on('response', (response) => {
      const request = response.request();
      if (request.method() === 'POST' && request.url().endsWith('/api/scan/passages')) {
        const entry = scanRequests.slice().reverse().find((r) => r.endedAt === null);
        if (entry !== undefined) {
          entry.endedAt = Date.now();
        }
      }
    });

    async function loginScanner(scanPage: Page, rememberMe: boolean): Promise<void> {
      await scanPage.getByRole('link', { name: 'Se connecter' }).click();
      await scanPage.getByLabel("Nom d'utilisateur").fill('scanner-test');
      await scanPage.getByLabel('Mot de passe').fill('scanner-secret');
      if (rememberMe) {
        await scanPage.getByLabel('Rester connecté 24 h', { exact: false }).check();
      }
      await scanPage.getByRole('button', { name: 'Se connecter' }).click();
      await expect(scanPage.getByRole('heading', { level: 1 })).toHaveText('Scan');
    }

    async function captureAndExpect(scanPage: Page, qrToken: string, bandeau: string): Promise<void> {
      await scanPage.getByLabel('Code du QR').fill(qrToken);
      await scanPage.getByRole('button', { name: 'Valider' }).click();
      await expect(scanPage.getByText(bandeau, { exact: false })).toBeVisible({ timeout: 5_000 });
      await expect(scanPage.getByText('0 en attente')).toBeVisible({ timeout: 5_000 });
    }

    // 1. Onglet A : tableau de bord anonyme, ouvert en premier.
    const pageA = await context.newPage();
    await pageA.goto(`/courses/${race.id}`);
    await expect(pageA.getByRole('heading', { level: 1 })).toContainText(race.name);

    // Onglet B : /scan, connexion SCANNER sans « Rester connecté ». Capture d'Alice.
    const pageB = await context.newPage();
    await pageB.goto('/scan');
    await loginScanner(pageB, false);
    await captureAndExpect(pageB, alice.qrToken, `Dossard 1 — ${alice.name} — yard 1`);
    const aliceDetail = await api.runnerDetail(alice.runnerId);
    expect(aliceDetail.passages).toHaveLength(1);
    expect(aliceDetail.passages[0].source).toBe('SCAN');
    expect(aliceDetail.passages[0].yardNumber).toBe(1);

    // 2. A reste ouvert. Rechargement de B, reconnexion, capture de Bob.
    await pageB.reload();
    await loginScanner(pageB, false);
    await captureAndExpect(pageB, bob.qrToken, `Dossard 2 — ${bob.name} — yard 1`);
    const bobDetail = await api.runnerDetail(bob.runnerId);
    expect(bobDetail.passages).toHaveLength(1);
    expect(bobDetail.passages[0].source).toBe('SCAN');

    // 3. Fermeture de B. Ouverture de C, connexion SCANNER AVEC « Rester connecté ». Capture de Chloé.
    await pageB.close();
    const pageC = await context.newPage();
    await pageC.goto('/scan');
    await loginScanner(pageC, true);
    await captureAndExpect(pageC, chloe.qrToken, `Dossard 3 — ${chloe.name} — yard 1`);
    const chloeDetail = await api.runnerDetail(chloe.runnerId);
    expect(chloeDetail.passages).toHaveLength(1);
    expect(chloeDetail.passages[0].source).toBe('SCAN');

    // 4. (Étape ajoutée par l'arbitrage OBS-T2 du 2026-09-27.) Fermeture de C. Rechargement de A, qui reprend
    // les identifiants SCANNER mémorisés à l'étape 3 et devient ainsi le seul contexte capable d'émettre.
    // Ouverture de D sur /scan : connecté par les identifiants mémorisés, sans nouvelle saisie. Capture de Dan.
    await pageC.close();
    await pageA.reload();
    await expect(pageA.getByRole('heading', { level: 1 })).toContainText(race.name);

    const pageD = await context.newPage();
    await pageD.goto('/scan');
    await expect(pageD.getByRole('heading', { level: 1 })).toHaveText('Scan');
    // Connecté par les identifiants mémorisés : aucune nouvelle saisie, l'indicateur affiche « scanner ».
    await expect(pageD.getByText('scanner', { exact: true })).toBeVisible();

    // RG50 (arbitrage OBS-T2) : c'est le contexte de capture (D) qui doit afficher le bandeau vert et
    // « 0 en attente », qu'il soit émetteur ou non. La correction de production correspondante est en place
    // et cette assertion passe réellement.
    await captureAndExpect(pageD, dan.qrToken, `Dossard 4 — ${dan.name} — yard 1`);
    const danDetail = await api.runnerDetail(dan.runnerId);
    expect(danDetail.passages).toHaveLength(1);
    expect(danDetail.passages[0].source).toBe('SCAN');
    expect(danDetail.passages[0].yardNumber).toBe(1);

    // 5. Journal réseau sur tout le parcours : exactement 4 requêtes E6, jamais deux en cours ensemble.
    expect(scanRequests).toHaveLength(4);
    const sorted = [...scanRequests].sort((a, b) => a.startedAt - b.startedAt);
    for (let i = 1; i < sorted.length; i++) {
      const previous = sorted[i - 1];
      const current = sorted[i];
      expect(previous.endedAt, `requête #${i - 1} doit être terminée avant le départ de la requête #${i}`)
        .not.toBeNull();
      expect(current.startedAt).toBeGreaterThanOrEqual(previous.endedAt as number);
    }
  });
});
