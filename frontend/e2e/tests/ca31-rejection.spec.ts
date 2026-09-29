import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA31 — Rejet définitif et poursuite de la file (RG24, RG25, CL2). Service worker désactivé pour un contrôle
 * réseau fiable (voir LIM-E2E-1, `INC-4-e2e.md`).
 *
 * BUG-2 (voir CA29 et `docs/tests/rapports/INC-4-e2e.md`) : avec seulement deux coureurs, dès que Chloé
 * échoue le yard 1 (auto-DNF) et que Dan le termine, Dan deviendrait immédiatement `WINNER` (règle métier
 * « un seul actif termine un yard que personne d'autre ne termine », déjà validée par
 * `YardClosingServiceTest#ca33_singleActiveFinisherWins`, INC-2), ce qui arrêterait la course avant les
 * yards 2 et 3 attendus par ce parcours. Une troisième coureuse (Zoé), qui termine les mêmes yards que Dan,
 * est ajoutée pour garder deux actifs (CA34) et laisser le scénario de rejet se dérouler comme décrit.
 */
test.describe('@INC-4 @INC4-CA31 Rejet définitif et poursuite de la file', () => {
  test.use({ serviceWorkers: 'block' });

  test('rejet de Chloé, Dan traité ensuite ; marquer vu et renvoyer', async ({ page, context }, testInfo) => {
    test.setTimeout(120_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-REJ-${run}`, loopDistance: 1000, loopDuration: 30, loopElevation: 10,
    });
    const chloe = await api.register(race.id, 'Chloé Rej');
    const dan = await api.register(race.id, 'Dan Rej');
    const zoe = await api.register(race.id, 'Zoé Rej'); // BUG-2 : garde 2 actifs après le DNF de Chloé
    const started = await api.startRace(race.id);
    const t0 = Date.parse(started.startedAt!);

    // Journal des requêtes E6 abouties (corps complet, avec l'instant de la réponse) pour vérifier l'ordre
    // Chloé puis Dan à la reprise réseau, et l'identité du corps renvoyé par « Renvoyer » (RG23, RG25).
    // Écouté sur `response`, pas `request` : hors ligne, le navigateur émet quand même un événement `request`
    // pour chaque tentative qui échoue immédiatement (erreur réseau, RG22 la classe TRANSITOIRE et la retente
    // avec backoff), ce qui compterait à tort des tentatives avortées comme des requêtes E6. Seule une requête
    // qui obtient une réponse du serveur compte ici.
    const scanBodies: { qrToken: string; scannedAt: string; startedAt: number }[] = [];
    page.on('response', (response) => {
      const request = response.request();
      if (request.method() === 'POST' && request.url().endsWith('/api/scan/passages')) {
        const body = request.postData();
        if (body !== null) {
          scanBodies.push({ ...(JSON.parse(body) as { qrToken: string; scannedAt: string }), startedAt: Date.now() });
        }
      }
    });

    // Corps réel de la réponse 409 de Chloé (RG24) : capturé pour comparer le texte affiché au `detail`
    // exact renvoyé par le serveur, et pas seulement au code HTTP « 409 ».
    const rejectionResponses: { status: number; code: string | null; detail: string }[] = [];
    page.on('response', (response) => {
      const request = response.request();
      if (request.method() === 'POST' && request.url().endsWith('/api/scan/passages') && response.status() === 409) {
        void response.json().then((body: { status?: number; code?: string; detail?: string }) => {
          rejectionResponses.push({
            status: body.status ?? response.status(),
            code: body.code ?? null,
            detail: body.detail ?? '',
          });
        }).catch(() => undefined);
      }
    });

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await waitUntil(t0 + 5_000);
    await page.getByLabel('Code du QR').fill(dan.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText(`Dossard ${dan.bib} — ${dan.name}`, { exact: false })).toBeVisible({ timeout: 5_000 });
    await api.scan(zoe.qrToken, new Date().toISOString());

    await waitUntil(t0 + 35_000);
    await page.getByLabel('Code du QR').fill(dan.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText(`yard 2`, { exact: false })).toBeVisible({ timeout: 5_000 });
    await api.scan(zoe.qrToken, new Date().toISOString());

    // Chloé DNF au yard 1 depuis T0 + 30 s.
    await waitUntil(t0 + 31_000);
    await api.waitForRunnerStatus(race.id, chloe.runnerId, 'DNF', 10_000);

    await waitUntil(t0 + 65_000);
    await context.setOffline(true);
    // `context.setOffline` s'applique au niveau CDP juste avant que cette promesse ne se résolve, mais l'état
    // « hors ligne » de l'application (écouteur `window.addEventListener('offline', …)`) peut ne se propager
    // qu'un instant après : attendre l'indicateur affiché, un état observable, plutôt qu'un délai fixe
    // (RG57.4), évite de capturer alors que la première requête pourrait encore partir en ligne.
    await expect(page.locator('.indicator').filter({ hasText: 'Hors ligne' })).toBeVisible();
    await page.getByLabel('Code du QR').fill(chloe.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await page.getByLabel('Code du QR').fill(dan.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('2 en attente')).toBeVisible();

    await waitUntil(t0 + 68_000);
    const onlineAt = Date.now();
    await context.setOffline(false);

    await expect(page.locator('.indicator').filter({ hasText: /rejeté/ })).toContainText('1 rejeté', { timeout: 15_000 });
    await expect(page.getByText(`Dossard ${dan.bib} — ${dan.name} — yard 3`, { exact: false }))
      .toBeVisible({ timeout: 15_000 });

    const rejectionCard = page.locator('.rejections li');
    await expect(rejectionCard).toHaveCount(1);
    // Le `detail` réellement renvoyé par le serveur, pas seulement le code HTTP : reconstruit le texte
    // exact affiché (statut, code, detail, puis la consigne ajoutée par `rejectionHint` pour ce couple
    // statut/code) et le compare littéralement à `.rejections li` (RG24), sur le même modèle que la
    // comparaison exacte au `detail` de `ca36-admin-crud.spec.ts:83-85`.
    await expect.poll(() => rejectionResponses.length, { timeout: 15_000 }).toBeGreaterThan(0);
    const chloeRejection = rejectionResponses[0];
    expect(chloeRejection.status).toBe(409);
    expect(chloeRejection.code).toBe('BUSINESS_CONFLICT');
    expect(chloeRejection.detail.length).toBeGreaterThan(0);
    const expectedRejectionHint = chloeRejection.status === 409 && chloeRejection.code === 'BUSINESS_CONFLICT'
      ? " — À signaler à l'organisateur"
      : '';
    const expectedRejectionText = `${chloeRejection.status} ${chloeRejection.code} : ${chloeRejection.detail}${expectedRejectionHint}`;
    await expect(rejectionCard.locator('p').nth(1)).toHaveText(expectedRejectionText);

    // Ordre FIFO à la reprise réseau (RG21) : Dan est envoyé après Chloé, jamais avant. On isole les requêtes
    // émises depuis la reconnexion (t0 + 68 s), pour les deux tokens concernés, triées par instant de départ.
    const replay = scanBodies
      .filter((entry) => entry.startedAt >= onlineAt && [chloe.qrToken, dan.qrToken].includes(entry.qrToken))
      .sort((a, b) => a.startedAt - b.startedAt);
    expect(replay).toHaveLength(2);
    expect(replay[0].qrToken).toBe(chloe.qrToken);
    expect(replay[1].qrToken).toBe(dan.qrToken);
    const chloeFirstAttempt = replay[0];

    await rejectionCard.getByRole('button', { name: 'Marquer comme vu' }).click();
    await expect(page.locator('.indicator-error')).toHaveCount(0);
    await expect(page.locator('.rejections li')).toHaveCount(1); // toujours listé

    // « Renvoyer » (RG25) : une nouvelle requête E6 est émise, de corps identique (RG23 : même scannedAt,
    // fixé à la capture), de nouveau rejetée (409). Le rejet reste seul dans la liste.
    const countBeforeResend = scanBodies.length;
    await page.locator('.rejections li').getByRole('button', { name: 'Renvoyer' }).click();
    await expect(page.locator('.rejections li')).toHaveCount(1, { timeout: 10_000 });
    await expect(page.locator('.rejections li')).toContainText('409', { timeout: 10_000 });
    // La réponse de la requête de renvoi (événement `response`) peut arriver un instant après que le DOM se
    // soit déjà stabilisé sur le même texte affiché : attente bornée sur l'état observable réel (la longueur
    // du journal réseau), jamais un délai fixe (RG57.4).
    await expect.poll(() => scanBodies.length, { timeout: 5_000 }).toBe(countBeforeResend + 1);
    const resent = scanBodies[scanBodies.length - 1];
    expect(resent.qrToken).toBe(chloeFirstAttempt.qrToken);
    expect(resent.scannedAt).toBe(chloeFirstAttempt.scannedAt);
  });
});

function waitUntil(targetMs: number): Promise<void> {
  const delay = Math.max(0, targetMs - Date.now());
  return new Promise((resolve) => setTimeout(resolve, delay));
}
