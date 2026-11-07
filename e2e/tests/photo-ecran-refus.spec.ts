import { expect, test, type Page, type Route } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import {
  MESSAGE_ENREGISTREE,
  MESSAGE_ILLISIBLE,
  MESSAGE_IMPOSSIBLE,
  MESSAGE_INDISPONIBLE,
  MESSAGE_TROP_VOLUMINEUX,
  choisir,
  choisirEtPrevisualiser,
  coureurAvecPhoto,
  dimensionsImage,
  enregistrer,
  espionnerPhoto,
  estPut,
  genererGrosJpeg,
  ouvrirMonCompte,
  pngDeTaille,
  t,
  urlPhoto,
} from './aide-photo-ecran';

/*
 * Incrément 10.1b : échecs de préparation dans le navigateur (CA26) et erreurs du serveur sur le PUT (CA28).
 * Le coureur a une photo posée par l'API : elle doit rester affichée après chaque refus.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

async function exigerAucunEnvoiNiApercu(page: Page, puts: () => unknown[]): Promise<void> {
  expect(puts()).toHaveLength(0);
  await expect(t(page, 'mon-compte-photo-apercu')).toHaveCount(0);
  await expect(t(page, 'mon-compte-photo-bouton-envoyer')).toHaveCount(0);
  await expect(t(page, 'mon-compte-photo-preparation')).toHaveCount(0);
}

test.describe('CA26 - échecs de préparation', () => {
  test('CA26 - faux-photo.png et png-corrompu.png : message « pas une image lisible », aucun PUT, photo conservée, rechoix du même fichier puis fichier valide', async ({ page, playwright }) => {
    const { pseudo } = await coureurAvecPhoto(playwright);
    const { puts } = await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);
    const src = await t(page, 'mon-compte-photo-image').getAttribute('src');

    for (const nom of ['faux-photo.png', 'png-corrompu.png', 'png-corrompu.png']) {
      await choisir(page, nom);
      await expect(t(page, 'mon-compte-photo-erreur')).toHaveText(MESSAGE_ILLISIBLE);
      await exigerAucunEnvoiNiApercu(page, puts);
      await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', src!);
    }

    // Après l'échec, un fichier valide fonctionne et efface l'erreur
    await choisirEtPrevisualiser(page, 'photo-2.png');
    await expect(t(page, 'mon-compte-photo-erreur')).toHaveCount(0);
    await enregistrer(page);
    await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
    expect(puts()).toHaveLength(1);
  });

  test('CA26 - 20 971 521 octets : refus sans décodage ; 20 971 520 octets décodables : accepté', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const { pseudo } = await coureurAvecPhoto(playwright);
    // Espionne : tout appel à createImageBitmap est compté (aucun décodage avant le refus)
    await page.addInitScript(() => {
      const w = window as unknown as { __decodages: number };
      w.__decodages = 0;
      const original = window.createImageBitmap.bind(window);
      window.createImageBitmap = ((...args: unknown[]) => {
        w.__decodages++;
        return (original as (...a: unknown[]) => Promise<ImageBitmap>)(...args);
      }) as typeof window.createImageBitmap;
    });
    const { puts } = await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);
    const src = await t(page, 'mon-compte-photo-image').getAttribute('src');

    await choisir(page, { name: 'trop-gros.png', mimeType: 'image/png', buffer: pngDeTaille(20_971_521) });
    await expect(t(page, 'mon-compte-photo-erreur')).toHaveText(MESSAGE_TROP_VOLUMINEUX);
    expect(await page.evaluate(() => (window as unknown as { __decodages: number }).__decodages)).toBe(0);
    await exigerAucunEnvoiNiApercu(page, puts);
    await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', src!);

    await choisirEtPrevisualiser(page, { name: 'limite.png', mimeType: 'image/png', buffer: pngDeTaille(20_971_520) });
    await expect(t(page, 'mon-compte-photo-erreur')).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __decodages: number }).__decodages)).toBe(1);
    await expect(t(page, 'mon-compte-photo-bouton-envoyer')).toBeVisible();
  });

  test('CA26 - échec de canvas.toBlob (null) : « La photo n\'a pas pu être préparée », aucun PUT, aucune erreur non gérée', async ({ page, playwright }) => {
    const { pseudo } = await coureurAvecPhoto(playwright);
    await page.addInitScript(() => {
      HTMLCanvasElement.prototype.toBlob = function (rappel: BlobCallback): void {
        rappel(null);
      };
    });
    const erreursNonGerees: string[] = [];
    page.on('pageerror', (e) => erreursNonGerees.push(e.message));
    page.on('console', (m) => {
      if (m.type() === 'error') erreursNonGerees.push(m.text());
    });
    const { puts } = await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);
    const src = await t(page, 'mon-compte-photo-image').getAttribute('src');

    await choisir(page, 'photo.jpg');
    await expect(t(page, 'mon-compte-photo-erreur')).toHaveText(MESSAGE_IMPOSSIBLE);
    await exigerAucunEnvoiNiApercu(page, puts);
    await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', src!);
    expect(erreursNonGerees, erreursNonGerees.join(' | ')).toEqual([]);
  });

  test('CA26 - changer de fichier pendant la préparation : seul le dernier résultat est prévisualisé', async ({ page, playwright, browser }) => {
    test.setTimeout(180_000);
    const gros = await genererGrosJpeg(browser);
    const pseudo = (await coureurAvecPhoto(playwright)).pseudo;
    // Compte les décodages terminés pour savoir quand la première préparation (lente) a fini
    await page.addInitScript(() => {
      const w = window as unknown as { __termines: number };
      w.__termines = 0;
      const original = window.createImageBitmap.bind(window);
      window.createImageBitmap = ((...args: unknown[]) =>
        (original as (...a: unknown[]) => Promise<ImageBitmap>)(...args).finally(() => w.__termines++)) as typeof window.createImageBitmap;
    });
    await espionnerPhoto(page);
    await ouvrirMonCompte(page, pseudo);

    await choisir(page, { name: 'gros.jpg', mimeType: 'image/jpeg', buffer: gros });
    await choisir(page, 'photo-petite.png');
    await expect.poll(() => page.evaluate(() => (window as unknown as { __termines: number }).__termines), { timeout: 60_000 }).toBe(2);
    await expect(t(page, 'mon-compte-photo-preparation')).toHaveCount(0);
    const apercu = t(page, 'mon-compte-photo-apercu');
    await expect(apercu).toBeVisible();
    expect(await dimensionsImage(apercu)).toEqual({ largeur: 200, hauteur: 100 });
    // L'ancien résultat (512 x 341) n'a pas remplacé l'aperçu
    await expect(apercu).toHaveJSProperty('naturalWidth', 200);
  });
});

/** Réponse simulée du serveur pour le PUT, `fois` fois (toujours si absent), puis requêtes réelles. */
type Simulation = (route: Route) => Promise<void>;
const probleme = (status: number, code: string): Simulation => (route) =>
  route.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({ status, code }) });

const CAS_SERVEUR: Array<{ nom: string; simulation: Simulation; message: string }> = [
  { nom: '413 sans corps', simulation: (r) => r.fulfill({ status: 413, body: '' }), message: 'La photo ne doit pas dépasser 2 Mo.' },
  { nom: '415 PHOTO_FORMAT_INVALIDE', simulation: probleme(415, 'PHOTO_FORMAT_INVALIDE'), message: 'La photo doit être une image PNG, JPEG ou WebP.' },
  { nom: '400 PHOTO_REQUISE', simulation: probleme(400, 'PHOTO_REQUISE'), message: 'Le fichier de la photo est obligatoire.' },
  { nom: '500', simulation: probleme(500, 'ERREUR_INTERNE'), message: MESSAGE_INDISPONIBLE },
  { nom: 'erreur réseau', simulation: (r) => r.abort('failed'), message: MESSAGE_INDISPONIBLE },
];

test.describe('CA28 - erreurs du serveur sur le PUT', () => {
  for (const cas of CAS_SERVEUR) {
    test(`CA28 - ${cas.nom} : « ${cas.message} », aperçu conservé, photo précédente affichée`, async ({ page, playwright }) => {
      const { pseudo } = await coureurAvecPhoto(playwright);
      await ouvrirMonCompte(page, pseudo);
      const src = await t(page, 'mon-compte-photo-image').getAttribute('src');
      await page.route(urlPhoto, (route) => (route.request().method() === 'PUT' ? cas.simulation(route) : route.continue()));

      await choisirEtPrevisualiser(page, 'photo.jpg');
      await t(page, 'mon-compte-photo-bouton-envoyer').click();
      await expect(t(page, 'mon-compte-photo-erreur')).toHaveText(cas.message);
      await expect(t(page, 'mon-compte-photo-apercu')).toBeVisible();
      await expect(t(page, 'mon-compte-photo-bouton-envoyer')).toBeEnabled();
      await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', src!);
      await expect(t(page, 'mon-compte-photo-message')).toHaveCount(0);
    });
  }

  test('CA28 - 403 CSRF_INVALIDE : « La page a expiré », aperçu conservé, un second clic sans repréparation réussit', async ({ page, playwright }) => {
    const { pseudo } = await coureurAvecPhoto(playwright);
    await ouvrirMonCompte(page, pseudo);
    const src = await t(page, 'mon-compte-photo-image').getAttribute('src');
    let simule = false;
    await page.route(urlPhoto, (route) => {
      if (route.request().method() === 'PUT' && !simule) {
        simule = true;
        return probleme(403, 'CSRF_INVALIDE')(route);
      }
      return route.continue();
    });

    await choisirEtPrevisualiser(page, 'photo.jpg');
    await t(page, 'mon-compte-photo-bouton-envoyer').click();
    await expect(t(page, 'mon-compte-photo-erreur')).toHaveText('La page a expiré, veuillez réessayer.');
    await expect(t(page, 'mon-compte-photo-apercu')).toBeVisible();
    await expect(t(page, 'mon-compte-photo-image')).toHaveAttribute('src', src!);

    const reponse = page.waitForResponse((r) => estPut(r.request()));
    await t(page, 'mon-compte-photo-bouton-envoyer').click();
    expect((await reponse).status()).toBe(200);
    await expect(t(page, 'mon-compte-photo-message')).toHaveText(MESSAGE_ENREGISTREE);
    await expect(t(page, 'mon-compte-photo-erreur')).toHaveCount(0);
    await expect(t(page, 'mon-compte-photo-image')).not.toHaveAttribute('src', src!);
  });

  test('CA28 - 401 : redirection vers /connexion?retour=%2Fmon-compte', async ({ page, playwright }) => {
    const { pseudo } = await coureurAvecPhoto(playwright);
    await ouvrirMonCompte(page, pseudo);
    await page.route(urlPhoto, (route) => (route.request().method() === 'PUT' ? probleme(401, 'NON_AUTHENTIFIE')(route) : route.continue()));
    await choisirEtPrevisualiser(page, 'photo.jpg');
    await t(page, 'mon-compte-photo-bouton-envoyer').click();
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fmon-compte$/);
  });

  test('CA28 - 403 ACCES_REFUSE : redirection vers /acces-refuse', async ({ page, playwright }) => {
    const { pseudo } = await coureurAvecPhoto(playwright);
    await ouvrirMonCompte(page, pseudo);
    await page.route(urlPhoto, (route) => (route.request().method() === 'PUT' ? probleme(403, 'ACCES_REFUSE')(route) : route.continue()));
    await choisirEtPrevisualiser(page, 'photo.jpg');
    await t(page, 'mon-compte-photo-bouton-envoyer').click();
    await expect(page).toHaveURL(/\/acces-refuse/);
  });
});
