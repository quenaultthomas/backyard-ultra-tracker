import { expect, test } from '@playwright/test';
import { MOT_DE_PASSE_ADMIN_CREE, MOT_DE_PASSE_ADMIN_MASTER, PSEUDO_ADMIN_MASTER, connecterParApi, creerAdminParApi, exigerIdentifiantsAdminMaster, pseudoAdminUnique } from './aide-admin';
import { MOT_DE_PASSE } from './aide-connexion';
import { avecApi } from './aide-recherche-coureurs';
import { chercher, confirmerPassage, estPassage, ouvrirConfirmation, ouvrirEnAdmin } from './aide-passage-benevole';
import { atteindreAuClavier, contraste, couleurs, focusVisible } from './aide-pwa';
import {
  MESSAGE_ENREGISTREE,
  MESSAGE_ILLISIBLE,
  MESSAGE_RETIREE,
  URL_PHOTO,
  choisir,
  choisirEtPrevisualiser,
  coureurAvecPhoto,
  enregistrer,
  espionnerPhoto,
  lirePhotoPubliquement,
  nouveauCoureur,
  ouvrirMonCompte,
  t,
} from './aide-photo-ecran';

/*
 * Incrément 10.1b : remplacement et retrait (CA27), comptes non coureurs (CA30) et accessibilité (CA31).
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA27 - remplacer la photo (ancienne URL en 404) puis la retirer sans confirmation (un seul DELETE, silhouette, messages effacés à chaque action)', async ({ page, playwright }) => {
  const { pseudo, jeton } = await coureurAvecPhoto(playwright);
  const ancienne = `/api/photos/${jeton}`;
  const { deletes, puts } = await espionnerPhoto(page);
  await ouvrirMonCompte(page, pseudo);
  await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', ancienne);
  await expect(t(page, 'mon-compte-photo-bouton-choisir')).toHaveText('Remplacer la photo');

  // Remplacement
  await choisirEtPrevisualiser(page, 'photo-2.png');
  await enregistrer(page);
  await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
  const nouvelle = (await t(page, 'mon-compte-photo-image').getAttribute('src'))!;
  expect(nouvelle).toMatch(URL_PHOTO);
  expect(nouvelle).not.toBe(ancienne);
  expect(puts()).toHaveLength(1);
  expect((await lirePhotoPubliquement(playwright, ancienne)).statut).toBe(404);
  expect((await lirePhotoPubliquement(playwright, nouvelle)).statut).toBe(200);

  // Retrait : sans confirmation, un seul DELETE, le message de succès précédent est remplacé
  await t(page, 'mon-compte-photo-bouton-retirer').click();
  await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_RETIREE);
  await expect(t(page, 'mon-compte-photo-silhouette')).toBeVisible();
  await expect(t(page, 'mon-compte-photo-image')).toHaveCount(0);
  await expect(t(page, 'mon-compte-photo-bouton-choisir')).toHaveText('Ajouter une photo');
  await expect(t(page, 'mon-compte-photo-bouton-retirer')).toHaveCount(0);
  expect(deletes()).toHaveLength(1);
  expect((await lirePhotoPubliquement(playwright, nouvelle)).statut).toBe(404);

  // Choisir un nouveau fichier efface le message
  await choisir(page, 'photo.jpg');
  await expect(t(page, 'mon-compte-photo-message')).toHaveCount(0);

  await ouvrirMonCompte(page, pseudo);
  await expect(t(page, 'mon-compte-photo-silhouette')).toBeVisible();
});

test.describe('CA30 - comptes non coureurs', () => {
  test('CA30 - Nadia (ADMIN) et Patron (ADMIN_MASTER) sans photo : pas de bloc photo', async ({ page, playwright }) => {
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    await ouvrirMonCompte(page, admin, MOT_DE_PASSE_ADMIN_CREE, false);
    await expect(page.getByTestId('mon-compte-role')).toBeVisible();
    await expect(t(page, 'mon-compte-photo')).toHaveCount(0);

    await ouvrirMonCompte(page, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER, false);
    await expect(page.getByTestId('mon-compte-role')).toBeVisible();
    await expect(t(page, 'mon-compte-photo')).toHaveCount(0);
  });

  test('CA30 - coureur avec photo promu bénévole : photo, information publique et « Retirer la photo » seulement ; retirer fait disparaître le bloc', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const { pseudo, jeton } = await coureurAvecPhoto(playwright, 'logo.png', 'promu');
    const nadia = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));

    // Promotion par l'admin (écran Bénévoles)
    await ouvrirEnAdmin(page, nadia, '/administration/benevoles', 'titre-benevoles');
    await chercher(page, pseudo);
    await ouvrirConfirmation(page, pseudo);
    const promotion = page.waitForResponse((r) => estPassage(r.request()));
    await confirmerPassage(page);
    expect((await promotion).status()).toBe(200);

    // Reconnecté en bénévole
    await page.context().clearCookies();
    await connecterParApi(page.request, pseudo, MOT_DE_PASSE);
    await page.goto('/mon-compte');
    const bloc = t(page, 'mon-compte-photo');
    await expect(bloc).toBeVisible();
    await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', `/api/photos/${jeton}`);
    await expect(t(page, 'mon-compte-photo-information')).toContainText('publique');
    await expect(t(page, 'mon-compte-photo-information')).not.toContainText('512');
    await expect(t(page, 'mon-compte-photo-bouton-retirer')).toHaveText('Retirer la photo');
    await expect(t(page, 'mon-compte-photo-bouton-choisir')).toHaveCount(0);
    await expect(t(page, 'mon-compte-photo-champ')).toHaveCount(0);
    await expect(bloc).not.toContainText('Ajouter');
    await expect(bloc).not.toContainText('réduite à 512');
    // La photo est conservée tant qu'elle n'est pas retirée
    expect((await lirePhotoPubliquement(playwright, `/api/photos/${jeton}`)).statut).toBe(200);

    await t(page, 'mon-compte-photo-bouton-retirer').click();
    await expect(bloc).toHaveCount(0);
    expect((await lirePhotoPubliquement(playwright, `/api/photos/${jeton}`)).statut).toBe(404);
  });
});

async function exigerCible(page: import('@playwright/test').Page, id: string): Promise<void> {
  const cible = t(page, id);
  const boite = await cible.boundingBox();
  expect(boite, id).not.toBeNull();
  expect(boite!.height, `${id} : hauteur de cible`).toBeGreaterThanOrEqual(44);
  expect(boite!.width, `${id} : largeur de cible`).toBeGreaterThanOrEqual(44);
}

async function exigerContraste(page: import('@playwright/test').Page, id: string): Promise<void> {
  const { texte, fond } = await couleurs(t(page, id));
  expect(contraste(texte, fond), `${id} : ${texte} sur ${fond}`).toBeGreaterThanOrEqual(4.5);
}

test.describe('CA31 - accessibilité', () => {
  test('CA31 - ordre de Tab, contour de focus visible, cibles de 44 px et contrastes', async ({ page, playwright }) => {
    const { pseudo } = await coureurAvecPhoto(playwright);
    await ouvrirMonCompte(page, pseudo);
    await choisirEtPrevisualiser(page, 'photo.jpg');

    await atteindreAuClavier(page, t(page, 'mon-compte-photo-bouton-choisir'));
    await focusVisible(t(page, 'mon-compte-photo-bouton-choisir'));
    for (const suivant of ['mon-compte-photo-bouton-retirer', 'mon-compte-photo-bouton-envoyer', 'mon-compte-photo-bouton-annuler']) {
      await page.keyboard.press('Tab');
      await expect(t(page, suivant)).toBeFocused();
      await focusVisible(t(page, suivant));
    }
    // Ensuite, le reste de la page (hors du bloc photo)
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => document.activeElement?.closest('[data-testid="mon-compte-photo"]') ?? null)).toBeNull();
    expect(await page.evaluate(() => document.activeElement !== document.body)).toBe(true);

    for (const id of ['mon-compte-photo-bouton-choisir', 'mon-compte-photo-bouton-retirer', 'mon-compte-photo-bouton-envoyer', 'mon-compte-photo-bouton-annuler']) {
      await exigerCible(page, id);
      await exigerContraste(page, id);
    }
    await exigerContraste(page, 'mon-compte-photo-information');
  });

  test('CA31 - alt, role=status (préparation, succès) et role=alert (erreur), contrastes des messages', async ({ page, playwright }) => {
    const { pseudo } = await coureurAvecPhoto(playwright);
    // La préparation est brève : un observateur de DOM note son rôle dès qu'elle apparaît
    await page.addInitScript(() => {
      const w = window as unknown as { __rolePreparation: string | null };
      w.__rolePreparation = null;
      new MutationObserver(() => {
        const p = document.querySelector('[data-testid="mon-compte-photo-preparation"]');
        if (p) w.__rolePreparation = p.getAttribute('role');
      }).observe(document, { childList: true, subtree: true, attributes: true });
    });
    await ouvrirMonCompte(page, pseudo);
    await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('alt', 'Ma photo de profil');

    await choisir(page, 'faux-photo.png');
    const erreur = t(page, 'mon-compte-photo-erreur');
    await expect(erreur).toHaveText(MESSAGE_ILLISIBLE);
    await expect(erreur).toHaveAttribute('role', 'alert');
    await exigerContraste(page, 'mon-compte-photo-erreur');

    await choisirEtPrevisualiser(page, 'photo-gps.jpg');
    expect(await page.evaluate(() => (window as unknown as { __rolePreparation: string | null }).__rolePreparation)).toBe('status');
    await expect(t(page, 'mon-compte-photo-apercu')).toHaveAttribute('alt', 'Aperçu de la nouvelle photo');
    await t(page, 'mon-compte-photo-bouton-envoyer').click();
    const succes = t(page, 'mon-compte-photo-message');
    await expect(succes).toHaveText(MESSAGE_ENREGISTREE);
    await expect(succes).toHaveAttribute('role', 'status');
    await exigerContraste(page, 'mon-compte-photo-message');

    await t(page, 'mon-compte-photo-bouton-retirer').click();
    await expect(t(page, 'mon-compte-photo-silhouette')).toHaveAttribute('alt', 'Aucune photo de profil');
  });

  for (const largeur of [320, 360]) {
    test(`CA31 - aucun débordement horizontal à ${largeur} px avec une image de 4 032 x 3 024`, async ({ page, playwright }) => {
      const pseudo = await nouveauCoureur(playwright);
      await page.setViewportSize({ width: largeur, height: 700 });
      await ouvrirMonCompte(page, pseudo);
      const debordement = () => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(await debordement()).toBeLessThanOrEqual(0);
      await choisirEtPrevisualiser(page, 'photo-gps.jpg');
      expect(await debordement(), 'aperçu ouvert').toBeLessThanOrEqual(0);
      await t(page, 'mon-compte-photo-bouton-envoyer').click();
      await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
      expect(await debordement(), 'photo enregistrée').toBeLessThanOrEqual(0);
    });
  }

  test('CA31 - le focus revient au bouton « Ajouter / Remplacer » après envoi, annulation et retrait', async ({ page, playwright }) => {
    const pseudo = await nouveauCoureur(playwright);
    await ouvrirMonCompte(page, pseudo);
    const choisirBouton = t(page, 'mon-compte-photo-bouton-choisir');

    // Annulation
    await choisirEtPrevisualiser(page, 'photo.jpg');
    await t(page, 'mon-compte-photo-bouton-annuler').click();
    await expect(choisirBouton).toBeFocused();

    // Envoi
    await choisirEtPrevisualiser(page, 'photo.jpg');
    await t(page, 'mon-compte-photo-bouton-envoyer').click();
    await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
    await expect(choisirBouton).toHaveText('Remplacer la photo');
    await expect(choisirBouton).toBeFocused();

    // Retrait
    await t(page, 'mon-compte-photo-bouton-retirer').click();
    await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_RETIREE);
    await expect(choisirBouton).toHaveText('Ajouter une photo');
    await expect(choisirBouton).toBeFocused();
  });
});
