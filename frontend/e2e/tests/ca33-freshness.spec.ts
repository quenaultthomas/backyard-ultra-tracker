import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA33 — Fraîcheur du tableau de bord (RG33). Service worker désactivé pour un blocage réseau fiable des
 * requêtes E4 (voir LIM-E2E-1, `INC-4-e2e.md`).
 */
test.describe('@INC-4 @INC4-CA33 Fraîcheur du tableau de bord', () => {
  test.use({ serviceWorkers: 'block' });

  test('bandeau "non actualisées" après 10 s de blocage, disparaît après reprise', async ({ page, context }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-FRESH-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    await api.register(race.id, 'Runner Fresh');
    await api.startRace(race.id);

    await page.goto(`/courses/${race.id}`);
    await expect(page.getByText('Mis à jour à', { exact: false })).toBeVisible({ timeout: 10_000 });

    await context.route('**/board', (route) => route.abort('internetdisconnected'));
    const blockedAt = Date.now();

    await expect(page.getByText('Données non actualisées depuis', { exact: false }))
      .toBeVisible({ timeout: 13_000 });
    expect(Date.now() - blockedAt).toBeGreaterThan(9_000);

    // Les dernières données restent affichées.
    await expect(page.getByText('Runner Fresh')).toBeVisible();

    await context.unroute('**/board');
    const unblockedAt = Date.now();
    await expect(page.getByText('Données non actualisées depuis', { exact: false })).toHaveCount(0, {
      timeout: 4_000,
    });
    expect(Date.now() - unblockedAt).toBeLessThan(4_000);
  });
});
