import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { ECRANS, ouvrirScene, preparerJeuReference, type JeuReference, type MesuresResponsive } from './aide-theme';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test.describe('Responsive téléphone d\'abord', () => {
  let jeu: JeuReference;
  test.beforeAll(async ({ playwright }) => {
    jeu = await preparerJeuReference(playwright);
  });

  for (const largeur of [360, 320]) {
    for (const ecran of ECRANS) {
      test(`CA15 - « ${ecran.nom} » à ${largeur} px : pas de défilement horizontal, cibles >= 44 px, texte >= 13 px`, async ({ page }) => {
        test.setTimeout(60_000);
        await page.setViewportSize({ width: largeur, height: 640 });
        await ouvrirScene(page, ecran, jeu);
        const m = (await page.evaluate(() => (window as unknown as { __t: { responsive(): MesuresResponsive } }).__t.responsive())) as MesuresResponsive;
        expect(m.scrollWidth, 'scrollWidth').toBeLessThanOrEqual(m.clientWidth);
        expect(m.cibles, 'boutons et champs de moins de 44 px').toEqual([]);
        expect(m.petits, 'textes de moins de 13 px').toEqual([]);
        for (const carte of m.cartes) {
          expect(Math.round(carte.largeur), carte.element).toBe(m.clientWidth - 32);
          expect(Math.round(carte.gauche), carte.element).toBe(16);
        }
      });
    }
  }

  test('CA15 - à 1280 px la carte de connexion mesure 416 px', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await ouvrirScene(page, ECRANS.find((e) => e.nom === 'connexion')!, jeu);
    const carte = page.locator('section.carte').first();
    expect((await carte.boundingBox())!.width).toBe(416);
  });
});
