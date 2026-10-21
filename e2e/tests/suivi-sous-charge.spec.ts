import { type Page } from '@playwright/test';
import { expect, test } from '@playwright/test';
import { courseDemarreeAvecLogo, lire, MOTIF_API_SUIVI, urlSuivi } from './aide-suivi';

/*
 * Incrément 7.2 : suivi public sous charge, comportement du front (CA12, CA13).
 * Espacement progressif sur erreur (3 s, 6 s, 12 s...) avec aléa de 0 à 10 %, Retry-After respecté sur 429.
 */

test.setTimeout(90_000);

/** Intercepte le suivi : les `reponses` données aux premiers appels, puis passage normal. Renvoie les instants des appels. */
async function simuler(page: Page, reponses: Array<{ status: number; headers?: Record<string, string> }>): Promise<number[]> {
  const instants: number[] = [];
  await page.route(MOTIF_API_SUIVI, (route) => {
    const rang = instants.length;
    instants.push(Date.now());
    if (rang < reponses.length) {
      const r = reponses[rang];
      return route.fulfill({
        status: r.status,
        headers: r.headers,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: r.status }),
      });
    }
    return route.continue();
  });
  return instants;
}

const ecarts = (instants: number[]) => instants.slice(1).map((t, i) => t - instants[i]);

test('CA12 - trois 503 consécutifs avant toute donnée : tentatives à 3 s puis 6 s, suivi-erreur affiché puis rythme normal', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright);
  const instants = await simuler(page, [{ status: 503 }, { status: 503 }, { status: 503 }]);
  await page.goto(urlSuivi(course.id));

  await expect(lire(page, 'suivi-erreur')).toBeVisible();
  await expect(lire(page, 'suivi-nom')).toHaveCount(0);
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5', { timeout: 25_000 });
  await expect(lire(page, 'suivi-erreur')).toHaveCount(0);
  await expect.poll(() => instants.length, { timeout: 10_000 }).toBeGreaterThanOrEqual(5);

  const e = ecarts(instants);
  expect(e[0], 'après 1 échec : >= 3 s').toBeGreaterThanOrEqual(2900);
  expect(e[1], 'après 2 échecs : >= 6 s').toBeGreaterThanOrEqual(5900);
  expect(e[2], 'après 3 échecs : >= 12 s').toBeGreaterThanOrEqual(11_900);
  expect(e[3], 'rythme normal rétabli après succès').toBeGreaterThanOrEqual(2900);
  expect(e[3], 'rythme normal rétabli après succès').toBeLessThanOrEqual(3700);
});

test('CA12 - suivi déjà affiché puis deux 503 : avertissement, données conservées, retour au rythme normal', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright);
  const instants: number[] = [];
  let echecs = 0;
  await page.route(MOTIF_API_SUIVI, (route) => {
    instants.push(Date.now());
    if (instants.length >= 2 && echecs < 2) {
      echecs++;
      return route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ status: 503 }) });
    }
    return route.continue();
  });
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await expect(lire(page, 'suivi-avertissement-actualisation')).toBeVisible({ timeout: 8000 });
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await expect(lire(page, 'suivi-avertissement-actualisation')).toHaveCount(0, { timeout: 15_000 });
  await expect.poll(() => instants.length, { timeout: 10_000 }).toBeGreaterThanOrEqual(5);

  const e = ecarts(instants);
  expect(e[1], 'après 1 échec : >= 3 s').toBeGreaterThanOrEqual(2900);
  expect(e[2], 'après 2 échecs : >= 6 s').toBeGreaterThanOrEqual(5900);
  expect(e[3], 'rythme normal rétabli').toBeLessThanOrEqual(3700);
  expect(e[3], 'rythme normal rétabli').toBeGreaterThanOrEqual(2900);
});

test('CA13 - un 429 avec Retry-After de 8 s retarde la tentative suivante d\'au moins 8 s, puis rythme normal', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright);
  const instants = await simuler(page, [{ status: 429, headers: { 'Retry-After': '8' } }]);
  await page.goto(urlSuivi(course.id));

  await expect(lire(page, 'suivi-erreur')).toBeVisible();
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5', { timeout: 15_000 });
  await expect.poll(() => instants.length, { timeout: 10_000 }).toBeGreaterThanOrEqual(3);

  const e = ecarts(instants);
  expect(e[0], 'Retry-After respecté (et non 3 s)').toBeGreaterThanOrEqual(7900);
  expect(e[1], 'rythme normal après le 200').toBeGreaterThanOrEqual(2900);
  expect(e[1]).toBeLessThanOrEqual(3700);
});
