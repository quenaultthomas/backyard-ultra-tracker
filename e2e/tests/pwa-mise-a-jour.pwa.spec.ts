import { expect, test, type Locator, type Page } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { lireFile } from './aide-file-scan';
import { seDeconnecterParLeMenu } from './aide-entete';
import {
  afficherLeBandeauDeMiseAJour,
  aUnPictogramme,
  atteindreAuClavier,
  bandeau,
  connexionHorsLigne,
  contraste,
  couleurs,
  couleursEnDur,
  donneesHorsLigne,
  focusVisible,
  marqueur,
  memoriserLaListe,
  ouvrirScanAvecServiceWorker,
  pasDeDefilementHorizontal,
  resteInchange,
  sansAnimation,
} from './aide-pwa';
import { champ, lirePassagesEnBase, scannerParEntree, urlScan } from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const compteur = (page: Page) => page.getByTestId('scan-compteur-attente');
const plusTard = (page: Page) => page.getByTestId('mise-a-jour-plus-tard');
const appliquer = (page: Page) => page.getByTestId('mise-a-jour-appliquer');

test.describe('Mise à jour de l\'application (5.2)', () => {
  test('5.2 CA10 - une nouvelle version affiche le bandeau sans rechargement automatique', async ({ page, playwright }) => {
    await afficherLeBandeauDeMiseAJour(page, playwright);
    await expect(page.getByTestId('mise-a-jour-message')).toContainText("Une nouvelle version de l'application est disponible.");
    await expect(appliquer(page)).toHaveText('Mettre à jour');
    await expect(plusTard(page)).toHaveText('Plus tard');
    await expect(bandeau(page)).toHaveAttribute('role', 'status');
    // Aucun rechargement tout seul : le marqueur posé dans `window` survit.
    await resteInchange(page, () => marqueur(page), 'present', 5000);
    expect(await marqueur(page)).toBe('present');
  });

  test('5.2 CA10 - « Plus tard » masque le bandeau sans recharger et sans toucher au scan en attente', async ({ page, playwright }) => {
    const course = await afficherLeBandeauDeMiseAJour(page, playwright);
    await page.context().setOffline(true);
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');

    await plusTard(page).click();
    await expect(bandeau(page)).toHaveCount(0);
    await resteInchange(page, () => marqueur(page), 'present', 2000);
    expect(await marqueur(page)).toBe('present');
    await expect(compteur(page)).toHaveText('1 en attente');
    expect(await lireFile(page)).toHaveLength(1);
  });

  test('5.2 CA10 - « Mettre à jour » recharge la page et conserve le scan en attente', async ({ page, playwright }) => {
    const course = await afficherLeBandeauDeMiseAJour(page, playwright);
    await page.context().setOffline(true);
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    const identifiant = (await lireFile(page))[0].identifiantScan;

    await appliquer(page).click();
    await expect.poll(() => marqueur(page)).toBeNull();
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await expect(compteur(page)).toHaveText('1 en attente');
    await expect(bandeau(page)).toHaveCount(0);
    const file = await lireFile(page);
    expect(file.map((e) => e.identifiantScan)).toEqual([identifiant]);
    expect(file[0].jetonQr).toBe(course.coureurs[0].jeton);

    await page.context().setOffline(false);
    await expect.poll(() => lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
    await expect(compteur(page)).toHaveCount(0);
  });
});

const AFFICHAGES = [
  { largeur: 375, zoom: false, libelle: '375 px' },
  { largeur: 375, zoom: true, libelle: '375 px, texte à 200 %' },
  { largeur: 320, zoom: false, libelle: '320 px' },
  { largeur: 320, zoom: true, libelle: '320 px, texte à 200 %' },
];

async function afficher(page: Page, largeur: number, zoom: boolean): Promise<void> {
  await page.setViewportSize({ width: largeur, height: 740 });
  await page.evaluate((z) => {
    document.documentElement.style.setProperty('font-size', z ? '200%' : '', 'important');
  }, zoom);
}

async function verifierNotice(page: Page, notice: Locator): Promise<void> {
  await expect(notice).toBeVisible();
  await pasDeDefilementHorizontal(page);
  const boite = (await notice.boundingBox())!;
  const fenetre = page.viewportSize()!;
  expect(boite.x, 'notice dans la fenêtre (gauche)').toBeGreaterThanOrEqual(0);
  expect(boite.x + boite.width, 'notice dans la fenêtre (droite)').toBeLessThanOrEqual(fenetre.width + 0.5);
  expect((await notice.innerText()).trim().length, 'libellé').toBeGreaterThan(0);
  expect(await aUnPictogramme(notice.locator('xpath=self::*[contains(@class,"message-pictogramme")] | .//*[contains(@class,"message-pictogramme")]').first()), 'pictogramme').toBe(true);
  const { texte, fond } = await couleurs(notice);
  expect(contraste(texte, fond), 'contraste AA').toBeGreaterThanOrEqual(4.5);
}

test.describe('Accessibilité des notices hors ligne et de la mise à jour (5.2)', () => {
  test('5.2 CA12 - le bandeau de mise à jour : pas de débordement, cibles de 44 px, clavier, contrastes, sans animation ni couleur en dur', async ({ page, playwright }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await afficherLeBandeauDeMiseAJour(page, playwright);
    for (const { largeur, zoom, libelle } of AFFICHAGES) {
      await test.step(libelle, async () => {
        await afficher(page, largeur, zoom);
        await verifierNotice(page, bandeau(page));
        for (const bouton of [appliquer(page), plusTard(page)]) {
          const boite = (await bouton.boundingBox())!;
          expect(boite.height, 'hauteur du bouton').toBeGreaterThanOrEqual(44);
          expect(boite.width, 'largeur du bouton').toBeGreaterThanOrEqual(44);
          expect(boite.x + boite.width, 'bouton dans la fenêtre').toBeLessThanOrEqual(largeur + 0.5);
          await atteindreAuClavier(page, bouton);
          await focusVisible(bouton);
          await expect(bouton).toBeInViewport();
        }
        const { texte, fond } = await couleurs(appliquer(page));
        expect(contraste(texte, fond), 'contraste du bouton').toBeGreaterThanOrEqual(4.5);
      });
    }
    expect(await sansAnimation(bandeau(page)), 'animations avec prefers-reduced-motion').toEqual([]);
    expect(await couleursEnDur(bandeau(page)), 'couleurs en dur').toEqual([]);
  });

  test('5.2 CA12 - le bandeau ne recouvre ni l\'en-tête ni la confirmation de déconnexion', async ({ page, playwright }) => {
    const course = await afficherLeBandeauDeMiseAJour(page, playwright);
    await page.setViewportSize({ width: 320, height: 740 });
    await page.context().setOffline(true);
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');

    await seDeconnecterParLeMenu(page);
    const confirmation = page.getByTestId('entete-deconnexion-confirmation');
    await expect(confirmation).toBeVisible();
    await expect(bandeau(page)).toBeVisible();
    const entete = (await page.locator('header').first().boundingBox())!;
    const notice = (await bandeau(page).boundingBox())!;
    const dialogue = (await confirmation.boundingBox())!;
    expect(notice.y, 'le bandeau est sous l\'en-tête').toBeGreaterThanOrEqual(entete.y + entete.height - 1);
    const seChevauchent = (a: { x: number; y: number; width: number; height: number }, b: typeof a) =>
      a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
    expect(seChevauchent(notice, dialogue), 'le bandeau recouvre la confirmation').toBe(false);
    await expect(page.getByTestId('entete-deconnexion-confirmer')).toBeInViewport();
    await page.context().setOffline(false);
  });

  test('5.2 CA12 - la notice « liste enregistrée » (accueil bénévole et écran de scan) : lisible, sans débordement, sans animation ni couleur en dur', async ({ page, playwright }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const course = await ouvrirScanAvecServiceWorker(page, playwright);
    await memoriserLaListe(page, course.nom);
    await page.context().setOffline(true);

    for (const chemin of ['/benevole', urlScan(course.id)]) {
      await page.goto(chemin);
      await expect(donneesHorsLigne(page)).toBeVisible();
      for (const { largeur, zoom, libelle } of AFFICHAGES) {
        await test.step(`${chemin} à ${libelle}`, async () => {
          await afficher(page, largeur, zoom);
          await verifierNotice(page, donneesHorsLigne(page));
          if (chemin !== '/benevole') {
            await expect(champ(page)).toBeVisible();
            await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
          }
        });
      }
      expect(await sansAnimation(donneesHorsLigne(page)), `animations (${chemin})`).toEqual([]);
      expect(await couleursEnDur(donneesHorsLigne(page)), `couleurs en dur (${chemin})`).toEqual([]);
    }
    await page.context().setOffline(false);
  });

  test('5.2 CA12 - la notice de connexion hors ligne : lisible, sans débordement, sans animation ni couleur en dur', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/');
    await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
    await page.goto('/connexion');
    await page.context().setOffline(true);
    await expect(connexionHorsLigne(page)).toBeVisible();
    for (const { largeur, zoom, libelle } of AFFICHAGES) {
      await test.step(libelle, async () => {
        await afficher(page, largeur, zoom);
        await verifierNotice(page, connexionHorsLigne(page));
        await expect(connexionHorsLigne(page)).toHaveAttribute('role', 'status');
      });
    }
    expect(await sansAnimation(connexionHorsLigne(page))).toEqual([]);
    expect(await couleursEnDur(connexionHorsLigne(page))).toEqual([]);
    await page.context().setOffline(false);
  });
});
