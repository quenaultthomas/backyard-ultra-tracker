import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

/**
 * CA43 — Détail coureur (RG34, CL4). Scénario indépendant (pas de dépendance à CA32) : un passage « corrigé »
 * (réintégration, sans heure de scan ni temps de boucle) et un passage « scan » normal (avec heure et temps
 * de boucle).
 *
 * Deux coureurs supplémentaires, qui terminent normalement le yard 1, sont nécessaires pour que la course
 * reste `RUNNING` après le DNF du premier (cf. BUG-2, CA29/CA31) : avec un seul autre coureur, celui-ci
 * deviendrait l'unique finisseur du yard 1 et gagnerait immédiatement (CA33, « un seul actif termine un yard
 * que personne d'autre ne termine ») ; il en faut donc deux pour rester dans le cas CA34 (« au moins deux
 * finishers, la course continue »).
 */
test.describe('@INC-4 @INC4-CA43 Détail coureur', () => {
  test('yard corrigé sans heure ni temps de boucle ; yard scanné avec heure et temps de boucle', async ({
    page,
  }, testInfo) => {
    test.setTimeout(60_000);
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-DETAIL-${run}`, loopDistance: 1000, loopDuration: 30, loopElevation: 10,
    });
    const runner = await api.register(race.id, 'Runner Detail');
    const other1 = await api.register(race.id, 'Autre Coureur Un');
    const other2 = await api.register(race.id, 'Autre Coureur Deux');
    const started = await api.startRace(race.id);
    const t0 = Date.parse(started.startedAt!);
    await api.scan(other1.qrToken, new Date().toISOString());
    await api.scan(other2.qrToken, new Date().toISOString());

    // Aucun scan pour le yard 1 : DNF hors délai, puis réintégration (passage « corrigé » sans scannedAt).
    await api.waitForRunnerStatus(race.id, runner.runnerId, 'DNF', 40_000);
    await api.reintegrate(runner.runnerId);
    await api.waitForRunnerStatus(race.id, runner.runnerId, 'ACTIVE', 5_000);

    // Scan réel du yard 2, à T0 + 38 s (marge d'au moins 5 s par rapport à la cloche du yard 2 à T0 + 30 s,
    // RG57.4, reprise du 2026-09-27 : la version précédente scannait à T0 + 32 s, marge insuffisante de 2 s).
    await waitUntil(t0 + 38_000);
    await api.scan(runner.qrToken, new Date().toISOString());

    await page.goto(`/coureurs/${runner.runnerId}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Runner Detail');
    const yard1Row = page.locator('tbody tr').filter({ has: page.locator('td[data-label="Yard"]', { hasText: '1' }) });
    await expect(yard1Row.locator('td[data-label="Source"]')).toHaveText('corrigé', { timeout: 10_000 });
    await expect(yard1Row.locator('td[data-label="Heure de scan"]')).toHaveText('—');
    await expect(yard1Row.locator('td[data-label="Temps de boucle"]')).toHaveText('—');

    const yard2Row = page.locator('tbody tr').filter({ has: page.locator('td[data-label="Yard"]', { hasText: '2' }) });
    await expect(yard2Row.locator('td[data-label="Source"]')).toHaveText('scan', { timeout: 10_000 });
    await expect(yard2Row.locator('td[data-label="Heure de scan"]')).toHaveText(/^\d{2}:\d{2}:\d{2}$/);
    await expect(yard2Row.locator('td[data-label="Temps de boucle"]')).toHaveText(/^\d+:\d{2}$/);
  });
});

function waitUntil(targetMs: number): Promise<void> {
  const delay = Math.max(0, targetMs - Date.now());
  return new Promise((resolve) => setTimeout(resolve, delay));
}
