import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA29 — Coupure réseau, reprise FIFO et réactivation (RG14, RG19 à RG24, CL1).
 *
 * Service worker désactivé pour ce test (`serviceWorkers: 'block'`) : une fois la PWA contrôlée par son
 * service worker, les requêtes issues de la page échappent à l'émulation hors ligne et à l'interception
 * réseau de Playwright sous Chromium (limite d'outillage constatée et documentée en LIM-E2E-1 du rapport
 * `INC-4-e2e.md`) — sans quoi les captures « hors ligne » de ce test seraient en réalité envoyées en ligne.
 * Conséquence : l'assertion de la spec « rechargement de /scan hors ligne : la page s'affiche (service
 * worker) » n'est pas rejouée ici avec un véritable hors-ligne réseau (elle l'est par CA39, dédié à cette
 * capacité) ; ce test vérifie à la place la persistance de la file (RG20) par un rechargement réel, en
 * rétablissant le réseau juste le temps du rechargement puis en coupant à nouveau aussitôt.
 *
 * BUG-2 (constaté en mettant ce test au point, voir docs/tests/rapports/INC-4-e2e.md, section 4) : la spec
 * décrit une course à exactement deux coureurs (Alice, Bob) où Bob doit rester `ACTIVE` après l'auto-DNF
 * d'Alice au yard 1. Or la règle métier de victoire immédiate (« un seul actif termine un yard que personne
 * d'autre ne termine ») s'applique alors dès la clôture du yard 1 : avec deux coureurs seulement, dès qu'Alice
 * échoue ce yard et que Bob le termine, Bob devient `WINNER` immédiatement (comportement backend déjà
 * couvert et voulu par `YardClosingServiceTest#ca33_singleActiveFinisherWins`, INC-2). La course s'arrête donc
 * bien avant la réactivation attendue par CA29. Ce n'est pas un bug applicatif : c'est une donnée de test de
 * la spec E2E incompatible avec une règle métier déjà validée. Un troisième coureur (Zoé), qui termine
 * normalement le yard 1 en même temps que Bob, est ajouté ici pour garder deux actifs après l'auto-DNF
 * d'Alice (CA34, « au moins deux finishers : la course continue ») et permettre au scénario de FIFO/
 * réactivation de se dérouler comme décrit.
 */
test.describe('@INC-4 @INC4-CA29 Coupure réseau, FIFO et réactivation', () => {
  test.use({ serviceWorkers: 'block' });

  test('reprise FIFO après coupure, sans perte, avec réactivation', async ({ page, context }, testInfo) => {
    test.setTimeout(120_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-FIFO-${run}`, loopDistance: 1000, loopDuration: 30, loopElevation: 10,
    });
    const alice = await api.register(race.id, 'Alice Fifo');
    const bob = await api.register(race.id, 'Bob Fifo');
    const zoe = await api.register(race.id, 'Zoé Fifo'); // BUG-2 : garde 2 actifs après le DNF d'Alice
    const started = await api.startRace(race.id);
    const t0 = Date.parse(started.startedAt!);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByLabel('Rester connecté 24 h', { exact: false }).check();
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    // Bob scanné en ligne à T0 + 5 s (yard 1). Zoé aussi (par l'API, hors du parcours de scan sous test),
    // pour rester deux actifs après le DNF d'Alice (BUG-2 ci-dessus).
    await waitUntil(t0 + 5_000);
    await page.getByLabel('Code du QR').fill(bob.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText(`Dossard ${bob.bib} — Bob Fifo`, { exact: false })).toBeVisible({ timeout: 5_000 });
    await api.scan(zoe.qrToken, new Date().toISOString());

    // 1. T0 + 8 s : hors ligne. Capture d'Alice à T0 + 10 s.
    await waitUntil(t0 + 8_000);
    await context.setOffline(true);
    await waitUntil(t0 + 10_000);
    await page.getByLabel('Code du QR').fill(alice.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('Enregistré — en attente de réseau', { exact: false })).toBeVisible();
    await expect(page.getByText('1 en attente')).toBeVisible();
    let aliceDetail = await api.runnerDetail(alice.runnerId);
    expect(aliceDetail.passages).toHaveLength(0);

    // 2. Persistance de la file à travers un rechargement (RG20) : réseau rétabli le temps du rechargement
    // seulement (SW bloqué, voir note d'en-tête) pour charger les fichiers de la page, mais toute requête
    // `/api/**` reste bloquée sans interruption pendant toute la séquence, pour ne prendre aucun risque de
    // laisser passer un envoi prématuré qui invaliderait le scénario de réactivation (étapes 3 et suivantes).
    await context.route('**/api/**', (route) => route.abort('internetdisconnected'));
    await context.setOffline(false);
    await page.reload();
    // Attendre le rendu complet de l'écran (et donc le chargement du bloc de route Angular, importé
    // dynamiquement après l'événement `load`) avant de couper à nouveau : sinon ce second import réseau,
    // interrompu par la coupure, laisse la page blanche (constat fait en mettant ce test au point).
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');
    await context.setOffline(true);
    await context.unroute('**/api/**');
    await expect(page.getByText('1 en attente')).toBeVisible();

    // 3. T0 + 33 s (yard 2 ; yard 1 clos) : Alice DNF hors délai au yard 1, Bob toujours actif. On attend
    // explicitement d'être passé la cloche avant d'interroger, pour ne pas consommer le budget de la
    // prochaine attente sur la marge du planificateur (500 ms, RG57.3).
    await waitUntil(t0 + 31_000);
    await api.waitForRunnerStatus(race.id, alice.runnerId, 'DNF', 10_000);
    const boardAfterYard1 = await api.board(race.id);
    const bobEntry = boardAfterYard1.runners.find((entry) => entry.runnerId === bob.runnerId);
    expect(bobEntry?.status).toBe('ACTIVE');

    // 4. T0 + 36 s, toujours hors ligne : Alice puis Bob.
    await waitUntil(t0 + 36_000);
    await page.getByLabel('Code du QR').fill(alice.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('2 en attente')).toBeVisible();
    await page.getByLabel('Code du QR').fill(bob.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('3 en attente')).toBeVisible();

    // 5. T0 + 40 s : retour en ligne. Le journal réseau doit montrer 3 requêtes E6 séquentielles.
    const scanRequestsInFlight: string[] = [];
    const scanRequestOrder: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/api/scan/passages')) {
        scanRequestsInFlight.push(request.url());
        scanRequestOrder.push(request.postData() ?? '');
      }
    });
    await waitUntil(t0 + 40_000);
    await context.setOffline(false);

    await expect(page.getByText('0 en attente')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.rejections')).toHaveCount(0);

    expect(scanRequestOrder).toHaveLength(3);
    const bodies = scanRequestOrder.map((body) => JSON.parse(body) as { qrToken: string });
    expect(bodies[0].qrToken).toBe(alice.qrToken);
    expect(bodies[1].qrToken).toBe(alice.qrToken);
    expect(bodies[2].qrToken).toBe(bob.qrToken);

    // 6. Côté serveur : Alice ACTIVE (réactivée), 2 passages SCAN ; Bob 2 passages.
    aliceDetail = await api.runnerDetail(alice.runnerId);
    expect(aliceDetail.status).toBe('ACTIVE');
    expect(aliceDetail.passages).toHaveLength(2);
    expect(aliceDetail.passages.every((passage) => passage.source === 'SCAN')).toBe(true);
    const bobDetail = await api.runnerDetail(bob.runnerId);
    expect(bobDetail.passages).toHaveLength(2);
  });
});

function waitUntil(targetMs: number): Promise<void> {
  const delay = Math.max(0, targetMs - Date.now());
  return new Promise((resolve) => setTimeout(resolve, delay));
}
