import { expect, test } from '@playwright/test';
import { pseudoUnique } from './aide-connexion';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { ECRANS, ouvrirScene, parcourirAuTab, preparerJeuReference, type JeuReference } from './aide-theme';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

let jeu: JeuReference;
test.beforeAll(async ({ playwright }) => {
  jeu = await preparerJeuReference(playwright);
});

const ecran = (nom: string) => ECRANS.find((e) => e.nom === nom)!;

for (const nom of ['connexion', 'gestion des courses', 'mes inscriptions']) {
  test(`CA14 - la navigation au clavier de « ${nom} » suit l'ordre du DOM avec un contour de 3px visible`, async ({ page }) => {
    test.setTimeout(90_000);
    await ouvrirScene(page, ecran(nom), jeu);
    await page.mouse.move(0, 0);
    const arrets = await parcourirAuTab(page, 40);
    // R.3 : connecté, l'en-tête ne compte plus qu'un arrêt (menu fermé) au lieu de quatre liens alignés.
    expect(arrets.length).toBeGreaterThan(nom === 'mes inscriptions' ? 2 : 3);
    // Ordre : 0, 1, 2… dans l'ordre du DOM.
    expect(arrets.map((a) => a.index)).toEqual(arrets.map((_a, i) => i));
    expect(arrets.some((a) => a.dansEntete), 'au moins un arrêt dans l\'en-tête').toBe(true);
    expect(arrets.some((a) => !a.dansEntete), 'au moins un arrêt hors en-tête').toBe(true);
    for (const a of arrets) {
      expect(a.outlineStyle, `${a.element} : style du contour`).toBe('solid');
      expect(a.outlineWidth, `${a.element} : épaisseur du contour`).toBe('3px');
      expect(a.outlineColor, `${a.element} : couleur du contour`).toBe(a.dansEntete ? 'rgb(242, 140, 40)' : 'rgb(168, 67, 0)');
    }
  });
}

test('CA14 - Entrée sur le bouton principal de la connexion soumet le formulaire', async ({ page }) => {
  await ouvrirScene(page, ecran('connexion'), jeu);
  await page.getByTestId('champ-pseudo').fill(pseudoUnique('inconnu'));
  await page.getByTestId('champ-mot-de-passe').fill('mauvais-mot-de-passe-1');
  await page.getByTestId('champ-mot-de-passe').press('Tab');
  await expect(page.getByTestId('bouton-connexion')).toBeFocused();
  const envoi = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/connexion');
  await page.keyboard.press('Enter');
  await envoi;
  await expect(page.getByTestId('erreur-generale')).toHaveText('Pseudo ou mot de passe incorrect.');
});
