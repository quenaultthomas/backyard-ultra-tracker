import { expect, test, avancerHorloge } from './aide-horloge';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { compterRequetesSuivi, courseDemarreeAvecLogo, lire, lireSecondes, urlSuivi } from './aide-suivi';

/* Incrément 7.2 CA14 : la demande de bascule au zéro du compte à rebours part dans les 1,5 s (décalage aléatoire <= 1 s). */

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA14 - au zéro du compte à rebours, la bascule est demandée en moins de 1,5 s et le suivi affiche Boucle 2 en cours', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright, { dureeBoucleMinutes: 1, nombreMaxBoucles: 5 });
  const requetes = compterRequetesSuivi(page);
  // Le « 00:00:00 » ne dure qu'un instant : on l'horodate côté page via un MutationObserver, sans dépendre d'un poll.
  await page.addInitScript(() => {
    const w = window as unknown as Record<string, unknown>;
    const noter = () => {
      const el = document.querySelector('[data-testid="suivi-compte-a-rebours"]');
      if (el && w['instantZero'] === undefined && /^00:00:00$/.test((el.textContent ?? '').trim())) {
        w['instantZero'] = Date.now();
      }
    };
    new MutationObserver(noter).observe(document, { subtree: true, childList: true, characterData: true });
  });
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');

  await avancerHorloge(playwright, 'PT45S');
  await expect.poll(() => lireSecondes(page, 'suivi-compte-a-rebours'), { timeout: 8000 }).toBeLessThanOrEqual(15);
  await expect
    .poll(() => page.evaluate(() => (window as unknown as Record<string, unknown>)['instantZero'] !== undefined), { timeout: 20_000 })
    .toBe(true);
  const zero = await page.evaluate(() => (window as unknown as Record<string, number>)['instantZero']);

  // Première requête suivi émise à partir du zéro : au plus 1,5 s après.
  await expect.poll(() => requetes.instants.find((t) => t >= zero) ?? Infinity, { timeout: 5000 }).toBeLessThanOrEqual(zero + 1500);
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 2 sur 5', { timeout: 5000 });
  await expect(lire(page, 'suivi-annonce-boucle')).toContainText('Boucle 2 en cours');
});
