import { expect, test, type Page, type Route } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { compterRequetesSuivi, lire, MOTIF_API_SUIVI, urlSuivi } from './aide-suivi';
import { abandonManuelParApi } from './aide-suivi-coureurs';
import { courseDeQuatre, espionnerImages, marquerNoeuds, marquesDe, type CourseDeQuatre } from './aide-polaroid';
import { octetsFixture } from './aide-photo-ecran';

/*
 * Correctif d'audit du jalon 10 (FRT-02), nouvelles tentatives différées de la photo d'un polaroïd du suivi :
 * CA19 (au plus 3 nouvelles tentatives par adresse), CA20 (reprise dans le même nœud, onglet caché), CA21 (changement
 * d'adresse, abandon, adresses refusées). Remplacent l'ancien CA17 de 10.2. Le temps est piloté par page.clock
 * (installée avant goto, toujours runFor : fastForward ne déclenche pas de façon fiable les échéances successives) ; les délais sont comptés depuis l'échec précédent : 30 s, 2 min, 5 min.
 */

test.setTimeout(240_000);

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const SECONDE = 1000;
const MINUTE = 60 * SECONDE;
const JPEG = octetsFixture('photo.jpg');

/** Serveur de suivi : réponse réelle figée (pas de charge pendant les longues avancées d'horloge), modifiable. */
interface SuiviPilote {
  appels: () => number;
  /** Remplace la `photoUrl` du coureur dans les réponses suivantes. */
  photoUrl: (dossard: number, url: string | null) => void;
  /** Repasse en lecture directe du serveur réel (abandon, etc.), puis fige de nouveau à la demande. */
  reel: () => void;
  figer: () => void;
}

async function piloterSuivi(page: Page): Promise<SuiviPilote> {
  let appels = 0;
  let direct = false;
  let corps: Record<string, unknown> | null = null;
  const urls = new Map<number, string | null>();
  await page.route(MOTIF_API_SUIVI, async (route: Route) => {
    appels++;
    if (direct) return route.continue();
    if (corps === null) corps = (await (await route.fetch()).json()) as Record<string, unknown>;
    const coureurs = (corps['coureursEnCourse'] as { dossard: number; photoUrl: string | null }[]).map((c) => (urls.has(c.dossard) ? { ...c, photoUrl: urls.get(c.dossard)! } : c));
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...corps, coureursEnCourse: coureurs }) });
  });
  return {
    appels: () => appels,
    photoUrl: (d, u) => urls.set(d, u),
    reel: () => (direct = true),
    figer: () => {
      direct = false;
      corps = null;
    },
  };
}

/** Horloge installée avant le chargement, puis suspendue juste après l'échec initial : l'avancée est ensuite exacte. */
async function ouvrir(page: Page, course: CourseDeQuatre): Promise<void> {
  await page.clock.install();
  await page.goto(urlSuivi(course.course.id));
}

async function figerHorloge(page: Page): Promise<void> {
  const maintenant = await page.evaluate(() => Date.now());
  await page.clock.pauseAt(maintenant + 50);
}

const imgDe = (page: Page, dossard: number) => lire(page, `suivi-coureur-${dossard}`).locator('img');

/**
 * Attend que l'échec de l'image soit pris en compte (silhouette, essai suivant planifié). Horloge suspendue : le rendu
 * d'Angular attend un battement, donné par de tout petits pas (20 ms) qui ne modifient pas les échéances de 30 s et plus.
 */
async function attendreEchec(page: Page, dossard: number): Promise<void> {
  await expect
    .poll(async () => {
      await page.clock.runFor(20).catch(() => undefined);
      return imgDe(page, dossard).getAttribute('data-etat-photo');
    })
    .toBe('en-echec');
}

function surveillerErreurs(page: Page): string[] {
  const erreurs: string[] = [];
  page.on('pageerror', (e) => erreurs.push(e.message));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) erreurs.push(m.text());
  });
  return erreurs;
}

const refuser = (statut: number) => (route: Route) =>
  route.fulfill({ status: statut, contentType: 'application/problem+json', body: JSON.stringify({ code: statut === 404 ? 'PHOTO_INTROUVABLE' : 'TROP_DE_REQUETES' }) });

for (const statut of [404, 429]) {
  test(`CA19 - la photo de Karim répond toujours ${statut} : 4 requêtes au total (0, 30 s, 2 min 30, 7 min 30), silhouette sans message ni exception, polling continu, aucune requête pour les autres`, async ({
    page,
    playwright,
  }) => {
    const quatre = await courseDeQuatre(playwright);
    const { karim, jetonKarim } = quatre;
    const chemin = `/api/photos/${jetonKarim}`;
    const erreurs = surveillerErreurs(page);
    await page.route(`**${chemin}`, refuser(statut));
    const images = espionnerImages(page);
    const suivi = await piloterSuivi(page);
    await ouvrir(page, quatre);

    const li = lire(page, `suivi-coureur-${karim.dossard}`);
    await attendreEchec(page, karim.dossard);
    await figerHorloge(page);
    await expect(li.getByTestId('suivi-polaroid-silhouette')).toBeVisible();
    await expect(li.getByTestId('suivi-polaroid-photo')).toHaveCount(0);
    await expect(li).toContainText(karim.pseudo);
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(images.vers(chemin)).toBe(1);

    await page.clock.runFor(29 * SECONDE);
    expect(images.vers(chemin), 'après 29 s').toBe(1);

    await page.clock.runFor(SECONDE);
    await expect.poll(() => images.vers(chemin), { message: 'après 30 s' }).toBe(2);
    await attendreEchec(page, karim.dossard);

    await page.clock.runFor(2 * MINUTE);
    await expect.poll(() => images.vers(chemin), { message: 'après 2 min de plus' }).toBe(3);
    await attendreEchec(page, karim.dossard);

    await page.clock.runFor(5 * MINUTE);
    await expect.poll(() => images.vers(chemin), { message: 'après 5 min de plus' }).toBe(4);
    await attendreEchec(page, karim.dossard);

    await page.clock.runFor(30 * MINUTE);
    expect(images.vers(chemin), 'après 30 min de plus : plafond de 3 nouvelles tentatives').toBe(4);
    await expect(li.getByTestId('suivi-polaroid-silhouette')).toBeVisible();
    expect(suivi.appels(), 'le polling continue').toBeGreaterThanOrEqual(3);
    const autres = images.urls().filter((u) => u !== chemin);
    expect(autres, 'aucune requête d\'image pour les autres polaroïds').toEqual([`/api/photos/${quatre.jetonSophie}`]);
    expect(erreurs).toEqual([]);

    // Le rechargement de la page repart de zéro : nouvelle première requête.
    await page.reload();
    await expect
      .poll(async () => {
        await page.clock.runFor(50).catch(() => undefined);
        return images.vers(chemin);
      }, { message: 'le rechargement réessaie' })
      .toBe(5);
  });
}

for (const premier of [429, 500]) {
  test(`CA20 - ${premier} au premier appel puis 200 : la photo s'affiche dans le même nœud img après 30 s, sans rechargement ni nouvelle requête`, async ({ page, playwright }) => {
    const quatre = await courseDeQuatre(playwright);
    const { karim, jetonKarim } = quatre;
    const chemin = `/api/photos/${jetonKarim}`;
    let appels = 0;
    await page.route(`**${chemin}`, (route) => (++appels === 1 ? refuser(premier)(route) : route.fulfill({ status: 200, contentType: 'image/jpeg', body: JPEG })));
    const images = espionnerImages(page);
    await piloterSuivi(page);
    await ouvrir(page, quatre);
    await attendreEchec(page, karim.dossard);
    await figerHorloge(page);
    await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));
    await marquerNoeuds(page);
    const avant = await marquesDe(page, karim.dossard);
    await expect(lire(page, `suivi-coureur-${karim.dossard}`).getByTestId('suivi-polaroid-silhouette')).toBeVisible();
    expect(images.vers(chemin)).toBe(1);

    await page.clock.runFor(30 * SECONDE);

    const photo = lire(page, `suivi-coureur-${karim.dossard}`).getByTestId('suivi-polaroid-photo');
    // Horloge suspendue : le rendu d'Angular attend un battement (pas de 20 ms) une fois l'image chargée.
    await expect
      .poll(async () => {
        await page.clock.runFor(20);
        return photo.count();
      })
      .toBe(1);
    await expect(photo).toBeVisible();
    await expect(photo).toHaveAttribute('alt', `Photo de ${karim.pseudo}`);
    await expect.poll(() => photo.evaluate((el) => (el as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(await marquesDe(page, karim.dossard)).toEqual(avant);
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement']), 'sans rechargement').toBe(1);
    expect(images.vers(chemin)).toBe(2);

    await page.clock.runFor(10 * MINUTE);
    expect(images.vers(chemin), 'plus aucune requête vers cette adresse').toBe(2);
  });
}

test('CA20 - onglet caché pendant 2 min simulées : aucune nouvelle tentative, une seule au retour à l\'état visible', async ({ page, playwright }) => {
  const quatre = await courseDeQuatre(playwright);
  const { karim, jetonKarim } = quatre;
  const chemin = `/api/photos/${jetonKarim}`;
  await page.route(`**${chemin}`, refuser(404));
  const images = espionnerImages(page);
  await piloterSuivi(page);
  await ouvrir(page, quatre);
  await attendreEchec(page, karim.dossard);
  await figerHorloge(page);
  expect(images.vers(chemin)).toBe(1);

  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.clock.runFor(2 * MINUTE);
  expect(images.vers(chemin), 'aucune tentative tant que l\'onglet est caché').toBe(1);
  await expect(imgDe(page, karim.dossard)).toHaveAttribute('data-etat-photo', 'en-echec');

  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect
    .poll(async () => {
      await page.clock.runFor(20);
      return images.vers(chemin);
    }, { message: 'une seule tentative au retour' })
    .toBe(2);
  await attendreEchec(page, karim.dossard);
  // Aucune autre tentative immédiate : la suivante n'est due que 2 min plus tard (la tentative n'a consommé qu'un essai).
  await page.clock.runFor(MINUTE);
  expect(images.vers(chemin)).toBe(2);
});

test('CA21 - une autre photoUrl pendant l\'attente : chargée aussitôt, l\'ancienne n\'est plus demandée, le compteur repart de zéro', async ({ page, playwright }) => {
  const quatre = await courseDeQuatre(playwright);
  const { karim, jetonKarim } = quatre;
  const ancien = `/api/photos/${jetonKarim}`;
  const nouveau = `/api/photos/${'ab'.repeat(16)}`;
  await page.route(`**${ancien}`, refuser(404));
  await page.route(`**${nouveau}`, refuser(404));
  const images = espionnerImages(page);
  const suivi = await piloterSuivi(page);
  await ouvrir(page, quatre);
  await attendreEchec(page, karim.dossard);
  await figerHorloge(page);

  // Une nouvelle tentative de l'ancienne adresse est consommée, puis on attend la suivante (due dans 2 min).
  await page.clock.runFor(30 * SECONDE);
  await expect.poll(() => images.vers(ancien)).toBe(2);
  await attendreEchec(page, karim.dossard);
  await page.clock.runFor(MINUTE);
  expect(images.vers(ancien)).toBe(2);

  suivi.photoUrl(karim.dossard, nouveau);
  const appelsAvant = suivi.appels();
  await expect
    .poll(async () => {
      await page.clock.runFor(SECONDE);
      return images.vers(nouveau);
    })
    .toBe(1);
  expect(suivi.appels()).toBeGreaterThan(appelsAvant);
  await attendreEchec(page, karim.dossard);

  // Nouvelle série complète : 30 s, 2 min, 5 min, puis plus rien ; l'ancienne adresse n'est plus jamais demandée.
  await page.clock.runFor(30 * SECONDE);
  await expect.poll(() => images.vers(nouveau)).toBe(2);
  await attendreEchec(page, karim.dossard);
  await page.clock.runFor(2 * MINUTE);
  await expect.poll(() => images.vers(nouveau)).toBe(3);
  await attendreEchec(page, karim.dossard);
  await page.clock.runFor(5 * MINUTE);
  await expect.poll(() => images.vers(nouveau)).toBe(4);
  await attendreEchec(page, karim.dossard);
  await page.clock.runFor(10 * MINUTE);
  expect(images.vers(nouveau)).toBe(4);
  expect(images.vers(ancien), 'l\'ancienne adresse n\'est plus demandée').toBe(2);
});

test('CA21 - photoUrl null pendant l\'attente : silhouette sans attribut de tentative, aucune requête vers l\'ancienne adresse', async ({ page, playwright }) => {
  const quatre = await courseDeQuatre(playwright);
  const { karim, jetonKarim } = quatre;
  const ancien = `/api/photos/${jetonKarim}`;
  await page.route(`**${ancien}`, refuser(404));
  const images = espionnerImages(page);
  const suivi = await piloterSuivi(page);
  await ouvrir(page, quatre);
  await attendreEchec(page, karim.dossard);
  await figerHorloge(page);

  suivi.photoUrl(karim.dossard, null);
  await expect
    .poll(async () => {
      await page.clock.runFor(SECONDE);
      return imgDe(page, karim.dossard).getAttribute('data-etat-photo');
    })
    .toBeNull();
  await expect(lire(page, `suivi-coureur-${karim.dossard}`).getByTestId('suivi-polaroid-silhouette')).toBeVisible();
  await page.clock.runFor(10 * MINUTE);
  expect(images.vers(ancien)).toBe(1);
});

test('CA21 - Karim passe en abandon pendant l\'attente : son polaroïd disparaît, aucune requête vers l\'ancienne adresse ensuite', async ({ page, playwright }) => {
  const quatre = await courseDeQuatre(playwright);
  const { karim, jetonKarim } = quatre;
  const ancien = `/api/photos/${jetonKarim}`;
  await page.route(`**${ancien}`, refuser(404));
  const images = espionnerImages(page);
  const suivi = await piloterSuivi(page);
  await ouvrir(page, quatre);
  await attendreEchec(page, karim.dossard);
  await figerHorloge(page);

  suivi.reel();
  await abandonManuelParApi(playwright, quatre.course, karim, 'MANUEL');
  await expect
    .poll(async () => {
      await page.clock.runFor(SECONDE);
      return lire(page, `suivi-coureur-${karim.dossard}`).count();
    })
    .toBe(0);
  await page.clock.runFor(10 * MINUTE);
  expect(images.vers(ancien)).toBe(1);
});

for (const adresse of ['https://exemple.test/x.jpg', 'javascript:alert(1)']) {
  test(`CA21 - photoUrl ${adresse} : aucune requête ni tentative, silhouette`, async ({ page, playwright }) => {
    const quatre = await courseDeQuatre(playwright);
    const { karim, jetonKarim } = quatre;
    const externes: string[] = [];
    const dialogues: string[] = [];
    page.on('dialog', async (d) => {
      dialogues.push(d.message());
      await d.dismiss();
    });
    page.on('request', (r) => {
      if (/exemple\.test|^javascript:/.test(r.url())) externes.push(r.url());
    });
    await page.route('**://exemple.test/**', (route) => route.abort());
    const images = espionnerImages(page);
    const suivi = await piloterSuivi(page);
    suivi.photoUrl(karim.dossard, adresse);
    await ouvrir(page, quatre);
    const li = lire(page, `suivi-coureur-${karim.dossard}`);
    await expect(li.getByTestId('suivi-polaroid-silhouette')).toBeVisible();
    await expect(li.getByTestId('suivi-polaroid-photo')).toHaveCount(0);
    await figerHorloge(page);
    await page.clock.runFor(10 * MINUTE);
    expect(externes).toEqual([]);
    expect(dialogues).toEqual([]);
    expect(images.vers(`/api/photos/${jetonKarim}`)).toBe(0);
    await expect(li.getByTestId('suivi-polaroid-silhouette')).toBeVisible();
  });
}
