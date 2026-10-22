import { type Page } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { avancerHorloge, expect, test } from './aide-horloge';
import { carte, ouvrirAccueil, pageAnonyme, repartition } from './aide-accueil';
import { courseEnPreparation, demarrer, poserMarqueur } from './aide-suivi';

/*
 * Incrément 6.4 CA14 : la Course change de rubrique au rechargement seulement, selon le statut enregistré.
 * Boucle de 60 min, un inscrit qui ne scanne jamais : au départ de la Boucle 2, vainqueur partagé, Course TERMINEE (4.10).
 */
test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

async function statutDuSuivi(page: Page, id: string): Promise<string> {
  const reponse = await page.request.get(`/api/courses/${id}/suivi`);
  return ((await reponse.json()) as { statut: string }).statut;
}

test('CA14 - la Course passe de « À venir » à « En cours » puis « Terminées », au rechargement seulement', async ({ browser, playwright }) => {
  const course = await courseEnPreparation(playwright);
  const page = await pageAnonyme(browser);
  try {
    await ouvrirAccueil(page);
    await poserMarqueur(page);
    expect((await repartition(page, [course.nom]))[course.nom]).toBe('a-venir');

    await demarrer(playwright, course.id);
    // sans rechargement, l'écran ne change pas
    await expect(carte(page, course.nom, 'a-venir')).toHaveCount(1);
    await expect(carte(page, course.nom, 'en-cours')).toHaveCount(0);

    await page.reload();
    await ouvrirAccueil(page);
    await poserMarqueur(page);
    expect((await repartition(page, [course.nom]))[course.nom]).toBe('en-cours');

    await avancerHorloge(playwright, 'PT64M');
    await expect.poll(() => statutDuSuivi(page, course.id), { timeout: 15000 }).toBe('TERMINEE');
    // sans rechargement : toujours « En cours » à l'écran
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);
    await expect(carte(page, course.nom, 'en-cours')).toHaveCount(1);

    await page.reload();
    await ouvrirAccueil(page);
    expect((await repartition(page, [course.nom]))[course.nom]).toBe('terminees');
  } finally {
    await page.context().close();
  }
});
