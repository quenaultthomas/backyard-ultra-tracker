import { expect, test } from '@playwright/test';
import config from '../playwright.config';

test.describe('Garde-fous du service worker dans la suite existante (5.2)', () => {
  test('5.2 CA11 - le service worker est bloqué dans ce projet : aucun contrôleur, aucune exception non gérée', async ({ page }) => {
    const exceptions: Error[] = [];
    page.on('pageerror', (e) => exceptions.push(e));
    expect(test.info().project.use.serviceWorkers).toBe('block');
    await page.goto('/');
    await expect(page.getByTestId('titre')).toBeVisible();
    expect(await page.evaluate(() => navigator.serviceWorker.controller)).toBeNull();
    expect(await page.evaluate(() => navigator.serviceWorker.getRegistrations().then((r) => r.length))).toBe(0);
    expect(exceptions).toEqual([]);
  });

  test('5.2 CA11 - configuration des projets : service worker bloqué partout sauf pwa, chromium ignore les .pwa.spec.ts, pwa dépend de chromium et camera', () => {
    const projets = Object.fromEntries((config.projects ?? []).map((p) => [p.name, p]));
    for (const nom of ['chromium', 'camera', 'horloge']) expect(projets[nom].use?.serviceWorkers, nom).toBe('block');
    expect(projets['pwa'].use?.serviceWorkers).toBe('allow');
    expect(projets['pwa'].fullyParallel).toBe(false);
    expect(projets['pwa'].dependencies).toEqual(expect.arrayContaining(['chromium', 'camera']));
    expect(projets['horloge'].dependencies).toEqual(expect.arrayContaining(['chromium', 'camera', 'pwa']));
    const ignores = [projets['chromium'].testIgnore].flat() as RegExp[];
    expect(ignores.some((r) => r.test('x.pwa.spec.ts'))).toBe(true);
    expect(String(projets['pwa'].testMatch)).toContain('pwa');
  });
});
