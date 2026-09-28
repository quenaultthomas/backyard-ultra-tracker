import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA32 — Tableau de bord : bascule de yard, auto-DNF, badge « corrigé », vainqueur (RG29 à RG31, RG33, CL3,
 * CL6, CL7). Un seul onglet ouvre `/courses/{id}` en anonyme et n'est jamais rechargé pendant tout le
 * parcours (une seule navigation au journal).
 */
test.describe('@INC-4 @INC4-CA32 Tableau de bord : bascule, auto-DNF, vainqueur', () => {
  function runnerRow(page: import('@playwright/test').Page, name: string) {
    return page.locator('tr').filter({ has: page.getByRole('link', { name, exact: true }) });
  }

  test('parcours complet, sans aucun rechargement du tableau de bord', async ({ page }, testInfo) => {
    test.setTimeout(150_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-DASH-${run}`, loopDistance: 1000, loopDuration: 30, loopElevation: 10,
    });
    const alice = await api.register(race.id, 'Alice Dash');
    const bob = await api.register(race.id, 'Bob Dash');
    const chloe = await api.register(race.id, 'Chloé Dash');

    const publicRequests: { url: string; startedAt: number; endedAt: number | null; auth: boolean }[] = [];
    page.on('request', (request) => {
      if (request.url().includes('/api/public/') && request.url().includes('/board')) {
        publicRequests.push({
          url: request.url(), startedAt: Date.now(), endedAt: null,
          auth: request.headers()['authorization'] !== undefined,
        });
      }
    });
    page.on('response', (response) => {
      const req = response.request();
      if (req.url().includes('/api/public/') && req.url().includes('/board')) {
        const entry = [...publicRequests].reverse().find((r) => r.endedAt === null);
        if (entry !== undefined) {
          entry.endedAt = Date.now();
        }
      }
    });

    await page.goto(`/courses/${race.id}`);

    // 1. Avant le départ.
    await expect(page.getByText('Course non démarrée')).toBeVisible();
    await expect(runnerRow(page, 'Alice Dash')).toContainText('0');
    await expect(runnerRow(page, 'Alice Dash')).toContainText('0,00 km');
    await expect(runnerRow(page, 'Alice Dash')).toContainText('—');

    // 2. Démarrage par l'admin.
    const beforeStart = Date.now();
    const started = await api.startRace(race.id);
    const t0 = Date.parse(started.startedAt!);

    await expect(page.getByText('Yard 1', { exact: false })).toBeVisible({ timeout: 13_000 });
    expect(Date.now() - beforeStart).toBeLessThan(13_000);

    // Compte à rebours compris entre 0:30 et 0:15 (loopDuration 30 s, RG31).
    const countdownText = (await page.locator('.countdown').textContent())?.trim() ?? '';
    const countdownMatch = /^(\d+):(\d{2})$/.exec(countdownText);
    expect(countdownMatch, `format de compte à rebours inattendu : "${countdownText}"`).not.toBeNull();
    const remainingSeconds = Number(countdownMatch![1]) * 60 + Number(countdownMatch![2]);
    expect(remainingSeconds).toBeGreaterThanOrEqual(15);
    expect(remainingSeconds).toBeLessThanOrEqual(30);

    // 3. Scans d'Alice et Bob à T0 + 5 s.
    await waitUntil(t0 + 5_000);
    await api.scan(alice.qrToken, new Date().toISOString());
    await api.scan(bob.qrToken, new Date().toISOString());

    await expect(runnerRow(page, 'Alice Dash')).toContainText('1,00 km', { timeout: 11_000 });
    await expect(runnerRow(page, 'Alice Dash')).toContainText('10 m D+');
    await expect(runnerRow(page, 'Alice Dash').locator('td').nth(3)).toHaveText('1');
    await expect(runnerRow(page, 'Alice Dash').locator('td').nth(6)).not.toHaveText('—');
    await expect(runnerRow(page, 'Bob Dash')).toContainText('1,00 km');
    await expect(runnerRow(page, 'Bob Dash').locator('td').nth(3)).toHaveText('1');
    await expect(runnerRow(page, 'Bob Dash').locator('td').nth(6)).not.toHaveText('—');
    await expect(runnerRow(page, 'Chloé Dash')).toContainText('0,00 km');

    // 4. Après la cloche T0 + 30 s.
    await waitUntil(t0 + 32_000);
    await expect(page.getByText('Yard 2', { exact: false })).toBeVisible({ timeout: 4_000 });
    await expect(runnerRow(page, 'Chloé Dash')).toContainText('DNF hors délai au yard 1');
    await expect(runnerRow(page, 'Alice Dash')).toContainText('En course');
    await expect(runnerRow(page, 'Bob Dash')).toContainText('En course');

    // 5. Réintégration de Chloé (admin) à T0 + 38 s.
    await waitUntil(t0 + 38_000);
    await api.reintegrate(chloe.runnerId);
    await expect(runnerRow(page, 'Chloé Dash')).toContainText('En course', { timeout: 4_000 });
    await expect(runnerRow(page, 'Chloé Dash')).toContainText('corrigé');
    await expect(runnerRow(page, 'Chloé Dash')).toContainText('1,00 km');
    await expect(runnerRow(page, 'Chloé Dash').locator('td').nth(6)).toHaveText('—');

    // 6. Alice seule à T0 + 45 s ; vainqueur après la cloche T0 + 60 s.
    await waitUntil(t0 + 45_000);
    await api.scan(alice.qrToken, new Date().toISOString());

    await waitUntil(t0 + 62_000);
    await expect(page.getByText('Course terminée', { exact: false })).toBeVisible({ timeout: 6_000 });
    await expect(page.getByText('Vainqueur : dossard 1 — Alice Dash — 2 tours', { exact: false })).toBeVisible();
    await expect(runnerRow(page, 'Bob Dash')).toContainText('DNF hors délai au yard 2');
    await expect(runnerRow(page, 'Chloé Dash')).toContainText('DNF hors délai au yard 2');
    await expect(runnerRow(page, 'Chloé Dash')).toContainText('corrigé');

    // 7. Journal réseau sur une fenêtre de 10 s pendant le yard 2 : 3 à 5 requêtes E4, jamais 2 simultanées,
    // sans Authorization. On mesure sur la fenêtre [T0+32s, T0+42s] déjà couverte par le journal ci-dessus.
    const windowStart = t0 + 32_000;
    const windowEnd = t0 + 42_000;
    const inWindow = publicRequests.filter((entry) => entry.startedAt >= windowStart && entry.startedAt <= windowEnd);
    expect(inWindow.length).toBeGreaterThanOrEqual(3);
    expect(inWindow.length).toBeLessThanOrEqual(5);
    expect(inWindow.every((entry) => !entry.auth)).toBe(true);

    const sortedWindow = [...inWindow].sort((a, b) => a.startedAt - b.startedAt);
    for (let i = 1; i < sortedWindow.length; i++) {
      const previous = sortedWindow[i - 1];
      const current = sortedWindow[i];
      expect(previous.endedAt, `requête #${i - 1} de la fenêtre doit être terminée`).not.toBeNull();
      expect(current.startedAt).toBeGreaterThanOrEqual(previous.endedAt as number);
    }
  });
});

function waitUntil(targetMs: number): Promise<void> {
  const delay = Math.max(0, targetMs - Date.now());
  return new Promise((resolve) => setTimeout(resolve, delay));
}
