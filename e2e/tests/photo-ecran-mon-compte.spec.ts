import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { seConnecter } from './aide-connexion';
import {
  MESSAGE_ENREGISTREE,
  URL_PHOTO,
  assurerDonneesDemo,
  choisirEtPrevisualiser,
  dimensionsImage,
  enregistrer,
  espionnerPhoto,
  exigerJpegSansMetadonnees,
  genererGrosJpeg,
  lirePhotoPubliquement,
  nouveauCoureur,
  octetsFixture,
  ouvrirMonCompte,
  partieFichier,
  pixelDe,
  t,
} from './aide-photo-ecran';

/*
 * Incrément 10.1b, écran « Mon compte » : parcours nominal (CA23), contenu des octets envoyés (CA24)
 * et formats d'entrée (CA25). Les comptes sont créés par l'API ; seul l'écran testé est piloté à l'interface.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test('CA23 - coureur sans photo : silhouette, information, aperçu sans requête, annulation, enregistrement (un seul PUT), image publique, rechargement', async ({ page, playwright }) => {
  const pseudo = await nouveauCoureur(playwright);
  const { appels, puts } = await espionnerPhoto(page);
  await ouvrirMonCompte(page, pseudo);

  // État initial
  await expect(t(page, 'mon-compte-photo-silhouette')).toBeVisible();
  await expect(t(page, 'mon-compte-photo-silhouette')).toHaveAttribute('alt', 'Aucune photo de profil');
  await expect(t(page, 'mon-compte-photo-image')).toHaveCount(0);
  const information = t(page, 'mon-compte-photo-information');
  await expect(information).toContainText('publique');
  await expect(information).toContainText('retirer à tout moment');
  await expect(information).toContainText('réduite à 512 pixels');
  await expect(t(page, 'mon-compte-photo-bouton-choisir')).toHaveText('Ajouter une photo');
  await expect(t(page, 'mon-compte-photo-bouton-retirer')).toHaveCount(0);

  // Aperçu sans aucune requête, puis annulation sans appel
  await choisirEtPrevisualiser(page, 'photo.jpg');
  expect(appels).toHaveLength(0);
  await t(page, 'mon-compte-photo-bouton-annuler').click();
  await expect(t(page, 'mon-compte-photo-apercu')).toHaveCount(0);
  expect(appels).toHaveLength(0);

  // Enregistrement : double clic = un seul PUT
  await choisirEtPrevisualiser(page, 'photo.jpg');
  await t(page, 'mon-compte-photo-bouton-envoyer').dblclick();
  await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
  const image = t(page, 'mon-compte-photo-image');
  await expect(image).toBeVisible();
  await expect(image).toHaveAttribute('src', URL_PHOTO);
  await expect.poll(async () => (await dimensionsImage(image)).largeur).toBeGreaterThan(0);
  expect(puts()).toHaveLength(1);
  await expect(t(page, 'mon-compte-photo-bouton-choisir')).toHaveText('Remplacer la photo');
  await expect(t(page, 'mon-compte-photo-bouton-retirer')).toHaveText('Retirer la photo');
  await expect(t(page, 'mon-compte-photo-silhouette')).toHaveCount(0);

  // Chargée sans cookie
  const src = (await image.getAttribute('src'))!;
  const publique = await lirePhotoPubliquement(playwright, src);
  expect(publique.statut).toBe(200);
  expect(publique.type).toContain('image/jpeg');

  // Persistance au rechargement
  await page.reload();
  await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', src);
  await expect(t(page, 'mon-compte-photo-bouton-choisir')).toHaveText('Remplacer la photo');
});

test('CA23 - données de démo : Karim voit sa photo et « Remplacer la photo », Tom voit la silhouette', async ({ page }) => {
  test.setTimeout(240_000);
  assurerDonneesDemo();
  await seConnecter(page, 'Karim');
  await page.goto('/mon-compte');
  await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', URL_PHOTO);
  await expect.poll(async () => (await dimensionsImage(t(page, 'mon-compte-photo-image'))).largeur).toBeGreaterThan(0);
  await expect(t(page, 'mon-compte-photo-bouton-choisir')).toHaveText('Remplacer la photo');
  await expect(t(page, 'mon-compte-photo-silhouette')).toHaveCount(0);

  await ouvrirMonCompte(page, 'Tom');
  await expect(t(page, 'mon-compte-photo-silhouette')).toBeVisible();
  await expect(t(page, 'mon-compte-photo-bouton-choisir')).toHaveText('Ajouter une photo');
});

test.describe('CA24 - octets réellement envoyés', () => {
  test('CA24 - photo-gps.jpg (4 032 x 3 024, EXIF + GPS) devient un JPEG 512 x 384 sans métadonnées, identique côté serveur, aperçu de mêmes dimensions', async ({ page, playwright }) => {
    // Précondition : la fixture contient bien « Exif », une balise GPSInfo (0x8825) et un commentaire GPS
    const origine = octetsFixture('photo-gps.jpg');
    expect(origine.indexOf(Buffer.from('Exif\0\0', 'latin1'))).toBeGreaterThan(-1);
    expect(origine.indexOf(Buffer.from([0x25, 0x88]))).toBeGreaterThan(-1);
    expect(origine.indexOf(Buffer.from('GPS', 'latin1'))).toBeGreaterThan(-1);

    const pseudo = await nouveauCoureur(playwright);
    await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);
    await choisirEtPrevisualiser(page, 'photo-gps.jpg');
    expect(await dimensionsImage(t(page, 'mon-compte-photo-apercu'))).toEqual({ largeur: 512, hauteur: 384 });

    const requete = await enregistrer(page);
    const envoye = partieFichier(requete);
    const analyse = exigerJpegSansMetadonnees(envoye);
    expect([analyse.largeur, analyse.hauteur]).toEqual([512, 384]);
    expect(envoye.length).toBeLessThan(300 * 1024);
    expect(envoye.length).toBeLessThan(2_097_152);

    await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
    const src = (await t(page, 'mon-compte-photo-image').getAttribute('src'))!;
    const servi = await lirePhotoPubliquement(playwright, src);
    expect(servi.statut).toBe(200);
    expect(servi.corps.equals(envoye), 'octets servis = octets envoyés').toBe(true);
  });

  test('CA24 - photo-petite.png (200 x 100) est envoyée en JPEG 200 x 100, sans agrandissement', async ({ page, playwright }) => {
    const pseudo = await nouveauCoureur(playwright);
    await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);
    await choisirEtPrevisualiser(page, 'photo-petite.png');
    expect(await dimensionsImage(t(page, 'mon-compte-photo-apercu'))).toEqual({ largeur: 200, hauteur: 100 });
    const envoye = partieFichier(await enregistrer(page));
    const analyse = exigerJpegSansMetadonnees(envoye);
    expect([analyse.largeur, analyse.hauteur]).toEqual([200, 100]);
  });

  test('CA24 - photo-orientation6.jpg (400 x 300, orientation 6) est envoyée en 300 x 400, orientation appliquée, EXIF supprimé', async ({ page, playwright }) => {
    // Précondition : l'orientation 6 est bien dans le fichier d'origine
    expect(octetsFixture('photo-orientation6.jpg').indexOf(Buffer.from('Exif\0\0', 'latin1'))).toBeGreaterThan(-1);
    const pseudo = await nouveauCoureur(playwright);
    await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);
    await choisirEtPrevisualiser(page, 'photo-orientation6.jpg');
    expect(await dimensionsImage(t(page, 'mon-compte-photo-apercu'))).toEqual({ largeur: 300, hauteur: 400 });
    const envoye = partieFichier(await enregistrer(page));
    const analyse = exigerJpegSansMetadonnees(envoye);
    expect([analyse.largeur, analyse.hauteur]).toEqual([300, 400]);
    // Le coin supérieur gauche vert (fichier stocké) se retrouve en haut à droite après rotation de 90 degrés
    const [r, g, b] = await pixelDe(page, envoye, 270, 30);
    expect(g, `pixel haut droite (${r},${g},${b})`).toBeGreaterThan(180);
    expect(r).toBeLessThan(90);
    expect(b).toBeLessThan(90);
  });

  test('CA24 - photo-transparente.png : la zone transparente devient blanche dans le JPEG envoyé', async ({ page, playwright }) => {
    const pseudo = await nouveauCoureur(playwright);
    await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);
    await choisirEtPrevisualiser(page, 'photo-transparente.png');
    const envoye = partieFichier(await enregistrer(page));
    exigerJpegSansMetadonnees(envoye);
    const [r, g, b] = await pixelDe(page, envoye, 40, 150);
    expect(Math.min(r, g, b), `pixel zone transparente (${r},${g},${b})`).toBeGreaterThanOrEqual(250);
    const [rr, gg, bb] = await pixelDe(page, envoye, 260, 150);
    expect(bb, `pixel zone opaque (${rr},${gg},${bb})`).toBeGreaterThan(rr);
  });
});

test.describe('CA25 - formats d\'entrée', () => {
  for (const nom of ['photo-2.png', 'photo.webp', 'animation.gif', 'photo-animee.webp']) {
    test(`CA25 - ${nom} est envoyé en JPEG et servi en image/jpeg`, async ({ page, playwright }) => {
      const pseudo = await nouveauCoureur(playwright);
      await espionnerPhoto(page);
      await ouvrirMonCompte(page, pseudo);
      await choisirEtPrevisualiser(page, nom);
      const envoye = partieFichier(await enregistrer(page));
      const analyse = exigerJpegSansMetadonnees(envoye);
      expect(analyse.largeur).toBeGreaterThan(0);
      if (nom.startsWith('animation') || nom === 'photo-animee.webp') {
        // Première image (rouge) : pixel central rouge, tolérance de compression
        const [r, g, b] = await pixelDe(page, envoye, Math.floor(analyse.largeur / 2), Math.floor(analyse.hauteur / 2));
        expect(r, `pixel central (${r},${g},${b})`).toBeGreaterThan(200);
        expect(g).toBeLessThan(80);
        expect(b).toBeLessThan(80);
      }
      await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
      const servi = await lirePhotoPubliquement(playwright, (await t(page, 'mon-compte-photo-image').getAttribute('src'))!);
      expect(servi.type).toContain('image/jpeg');
      expect(servi.corps.subarray(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]))).toBe(true);
    });
  }

  test('CA25 - un JPEG de 6 000 x 4 000 d\'environ 8 Mo est accepté et envoyé en moins de 300 Ko', async ({ page, playwright, browser }) => {
    test.setTimeout(180_000);
    const gros = await genererGrosJpeg(browser);
    expect(gros.length).toBeGreaterThan(5_000_000);
    expect(gros.length).toBeLessThan(20 * 1024 * 1024);
    const pseudo = await nouveauCoureur(playwright);
    await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);
    await choisirEtPrevisualiser(page, { name: 'gros.jpg', mimeType: 'image/jpeg', buffer: gros });
    const envoye = partieFichier(await enregistrer(page));
    const analyse = exigerJpegSansMetadonnees(envoye);
    expect([analyse.largeur, analyse.hauteur]).toEqual([512, 341]);
    expect(envoye.length).toBeLessThan(300 * 1024);
    await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
  });
});
