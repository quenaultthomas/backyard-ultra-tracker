import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import {
  ANCIENNES_VALEURS,
  ECRANS,
  ETATS,
  RGB_PALETTE,
  RGB_TRANSPARENT,
  ouvrirScene,
  parcourirAuTab,
  preparerJeuReference,
  type EcartCouleur,
  type JeuReference,
  type MesureContraste,
} from './aide-theme';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const AUTORISEES = [...RGB_PALETTE, RGB_TRANSPARENT];
const SCENES = [...ECRANS, ...ETATS];

test.describe('Palette et contraste des écrans', () => {
  let jeu: JeuReference;
  test.beforeAll(async ({ playwright }) => {
    jeu = await preparerJeuReference(playwright);
  });

  for (const scene of SCENES) {
    test(`CA5 - « ${scene.nom} » n'emploie que les couleurs de la palette`, async ({ page }) => {
      test.setTimeout(90_000);
      // Fin de transition immédiate : aucune couleur intermédiaire après un survol (150 ms).
      await page.emulateMedia({ reducedMotion: 'reduce' });
      const nettoyage = await ouvrirScene(page, scene, jeu);
      await page.mouse.move(0, 0);
      const ecarts = (await page.evaluate((a) => (window as unknown as { __t: { horsPalette(a: string[]): EcartCouleur[] } }).__t.horsPalette(a), AUTORISEES)) as EcartCouleur[];
      expect(ecarts, 'couleurs hors palette').toEqual([]);
      const valeurs = await page.evaluate(() => {
        const toutes = new Set<string>();
        for (const el of document.querySelectorAll('*')) {
          const cs = getComputedStyle(el);
          for (const p of [cs.color, cs.backgroundColor, cs.borderTopColor, cs.outlineColor]) toutes.add(p);
        }
        return Array.from(toutes);
      });
      for (const ancienne of ANCIENNES_VALEURS) expect(valeurs).not.toContain(ancienne);

      if (scene.nom === 'mes inscriptions') {
        const qr = await page.evaluate(() => {
          const racine = document.querySelector('[data-testid="inscriptions-qr"]');
          const remplissages = Array.from(racine?.querySelectorAll('[fill]') ?? []).map((e) => e.getAttribute('fill'));
          return { present: racine !== null, remplissages: Array.from(new Set(remplissages)).sort() };
        });
        expect(qr.present).toBe(true);
        expect(qr.remplissages).toEqual(['#000', '#fff']);
      }
      await nettoyage?.();
    });

    test(`CA6 - « ${scene.nom} » respecte les contrastes AA`, async ({ page }) => {
      test.setTimeout(90_000);
      await page.emulateMedia({ reducedMotion: 'reduce' });
      const nettoyage = await ouvrirScene(page, scene, jeu);
      await page.mouse.move(0, 0);
      const mesures = (await page.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
      expect(mesures.length).toBeGreaterThan(0);
      const sousSeuil = mesures.filter((m) => m.ratio + 1e-9 < m.seuil).map((m) => `${m.element} [${m.type}] ${m.couleur} sur ${m.fond} = ${m.ratio.toFixed(2)} < ${m.seuil}`);
      expect(sousSeuil, 'couples sous le seuil AA').toEqual([]);

      // Contours de focus : >= 3 contre leur fond.
      if (!scene.etat) {
        const arrets = await parcourirAuTab(page, 25);
        expect(arrets.length).toBeGreaterThan(0);
        const faibles = arrets.filter((a) => a.ratioContour < 3).map((a) => `${a.element} contour ${a.outlineColor} ratio ${a.ratioContour.toFixed(2)}`);
        expect(faibles, 'contours de focus sous 3').toEqual([]);
      }
      await nettoyage?.();
    });
  }

  test('CA6 - aucune information n\'est portée par la couleur seule : chaque pastille et chaque message a un texte', async ({ page }) => {
    const pastilles = ECRANS.find((e) => e.nom === 'mes inscriptions')!;
    await ouvrirScene(page, pastilles, jeu);
    for (const id of ['inscriptions-statut', 'inscriptions-course-statut']) {
      const textes = await page.getByTestId(id).allInnerTexts();
      expect(textes.length).toBeGreaterThan(0);
      for (const t of textes) expect(t.trim().length).toBeGreaterThan(0);
    }
    await page.context().clearCookies();
    const erreur = ETATS.find((e) => e.nom === 'connexion avec erreur affichee')!;
    await ouvrirScene(page, erreur, jeu);
    await expect(page.getByRole('alert').filter({ hasText: /\S/ })).toBeVisible();
  });
});
