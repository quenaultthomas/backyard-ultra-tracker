import { expect, test, type Page, type Route } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { connecterPatron, lire, urlPilotage } from './aide-abandon';
import { boutonReintegrer } from './aide-reintegration';

/*
 * Incrément 4.12 : écran de pilotage avec réponses fabriquées (page.route), CA17 (erreurs, double clic, requête, badge)
 * et CA18 (mise en page, clavier, accessibilité). Le panneau et les messages sont ceux de l'écran réel ;
 * seules les réponses de GET .../pilotage et de POST .../reintegration sont simulées.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const ID_SIMULE = '00000000-0000-0000-0000-000000000001';
const T0 = Date.parse('2026-10-09T14:03:27Z');
const PSEUDO_LONG = 'Abcdefghij'.repeat(3);

interface Coureur {
  dossard: number;
  pseudo: string;
  corrigees?: number;
}

const BRUNO: Coureur = { dossard: 1, pseudo: 'Bruno' };
const CHLOE: Coureur = { dossard: 2, pseudo: 'Chloé' };
const TOM: Coureur = { dossard: 3, pseudo: 'Tom' };

const idDe = (c: Coureur) => `00000000-0000-0000-0000-0000000000a${c.dossard}`;

const enCourse = (c: Coureur) => ({
  inscriptionId: idDe(c),
  dossard: c.dossard,
  pseudo: c.pseudo,
  statut: 'EN_COURSE',
  nombreBouclesTerminees: 1,
  boucleCouranteTerminee: false,
  nombreBouclesCorrigees: c.corrigees ?? 0,
});

const enAbandon = (c: Coureur) => ({
  inscriptionId: idDe(c),
  dossard: c.dossard,
  pseudo: c.pseudo,
  statut: 'ABANDON',
  nombreBouclesTerminees: 1,
  boucleAbandon: 2,
  motifAbandon: 'HORS_DELAI',
  nombreBouclesCorrigees: c.corrigees ?? 0,
});

const vainqueur = (c: Coureur) => ({
  inscriptionId: idDe(c),
  dossard: c.dossard,
  pseudo: c.pseudo,
  statut: 'VAINQUEUR',
  nombreBouclesTerminees: 2,
  nombreBouclesCorrigees: c.corrigees ?? 0,
});

function instantane(enCours: Coureur[], abandons: Coureur[], vainqueurs: Coureur[] = []): Record<string, unknown> {
  return {
    courseId: ID_SIMULE,
    nom: 'Backyard simulé',
    logoUrl: null,
    statut: 'EN_COURS',
    heureServeur: new Date(T0 + 30000).toISOString(),
    demarreeLe: '2026-10-09T14:03:27Z',
    dureeBoucleMinutes: 60,
    nombreMaxBoucles: 5,
    boucleCourante: 2,
    derniereBoucle: false,
    boucleMaxTerminee: false,
    debutBoucleCourante: '2026-10-09T14:03:27Z',
    finBoucleCourante: '2026-10-09T15:03:27Z',
    tempsAvantFinBoucleMillisecondes: 3570000,
    dureeEcouleeMillisecondes: 30000,
    nombreInscrits: enCours.length + abandons.length + vainqueurs.length,
    nombreAyantBoucle: 0,
    nombreEnBoucle: enCours.length,
    inscritsEnCourse: enCours.map(enCourse),
    nombreAbandons: abandons.length,
    inscritsEnAbandon: abandons.map(enAbandon),
    nombreVainqueurs: vainqueurs.length,
    inscritsVainqueurs: vainqueurs.map(vainqueur),
  };
}

interface Simulation {
  pilotage: number;
  posts: Array<{ corps: string | null; entetes: Record<string, string> }>;
}

type RepondrePilotage = (n: number) => Record<string, unknown>;
type RepondreReintegration = (route: Route, n: number) => Promise<void>;

const probleme = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ type: 'about:blank', status, code }),
});

const succes200 = (c: Coureur, bouclesCorrigees: number[] = [1]) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ inscriptionId: idDe(c), dossard: c.dossard, statut: 'EN_COURSE', bouclesCorrigees }),
});

async function simuler(
  page: Page,
  pilotage: RepondrePilotage,
  reintegration: RepondreReintegration = async (route) => route.fulfill(probleme(500, 'ERREUR_INTERNE')),
): Promise<Simulation> {
  const etat: Simulation = { pilotage: 0, posts: [] };
  await page.route('**/api/administration/courses/*/pilotage', async (route) => {
    const n = ++etat.pilotage;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'cache-control': 'no-store' },
      body: JSON.stringify(pilotage(n)),
    });
  });
  await page.route('**/api/administration/courses/*/inscriptions/*/reintegration', async (route) => {
    etat.posts.push({ corps: route.request().postData(), entetes: route.request().headers() });
    await reintegration(route, etat.posts.length);
  });
  return etat;
}

async function ouvrir(page: Page, id = ID_SIMULE): Promise<void> {
  await connecterPatron(page);
  await page.goto(urlPilotage(id));
}

async function ouvrirPanneauTom(page: Page): Promise<void> {
  await boutonReintegrer(page, TOM.dossard, TOM.pseudo).click();
  await expect(lire(page, 'pilotage-reintegrer-confirmation')).toBeVisible();
}

/** Cale l'action juste après un passage du polling (prochain dans ~3 s), pour vérifier le rafraîchissement immédiat. */
async function attendreUnPolling(etat: Simulation): Promise<number> {
  const n0 = etat.pilotage;
  await expect.poll(() => etat.pilotage, { timeout: 8000 }).toBeGreaterThan(n0);
  return etat.pilotage;
}

const UN_ABANDON: RepondrePilotage = () => instantane([BRUNO, CHLOE], [TOM]);

test.describe('Réintégration, erreurs et requête (CA17)', () => {
  const refusAvecRafraichissement: Array<[string, number, string, string]> = [
    ['INSCRIPTION_NON_EN_ABANDON', 409, 'INSCRIPTION_NON_EN_ABANDON', "Ce coureur n'est plus en abandon."],
    ['COURSE_TERMINEE', 409, 'COURSE_TERMINEE', "La course est terminée : aucune réintégration n'est possible."],
    ['TEMPS_DE_COURSE_ECOULE', 409, 'TEMPS_DE_COURSE_ECOULE', "Le temps de la dernière boucle est écoulé : la réintégration n'est plus possible."],
  ];

  for (const [nom, status, code, message] of refusAvecRafraichissement) {
    test(`CA17 - ${status} ${nom} : « ${message} », panneau fermé, une requête de pilotage part aussitôt`, async ({ page }) => {
      const etat = await simuler(page, UN_ABANDON, async (route) => route.fulfill(probleme(status, code)));
      await ouvrir(page);
      await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
      await ouvrirPanneauTom(page);
      const avant = await attendreUnPolling(etat);
      await lire(page, 'pilotage-reintegrer-confirmer').click();
      await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveText(message);
      await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveAttribute('role', 'alert');
      await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveCount(0);
      await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
      await expect.poll(() => etat.pilotage, { timeout: 1500 }).toBeGreaterThan(avant);
      expect(etat.posts).toHaveLength(1);
    });
  }

  test('CA17 - 409 HEURE_DE_DEPART_INCONNUE : « L\'heure de départ de la course est inconnue. », panneau fermé', async ({ page }) => {
    await simuler(page, UN_ABANDON, async (route) => route.fulfill(probleme(409, 'HEURE_DE_DEPART_INCONNUE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-reintegrer-confirmer').click();
    await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveText("L'heure de départ de la course est inconnue.");
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
  });

  test('CA17 - 404 INSCRIPTION_INTROUVABLE : « Ce coureur est introuvable. », panneau fermé', async ({ page }) => {
    await simuler(page, UN_ABANDON, async (route) => route.fulfill(probleme(404, 'INSCRIPTION_INTROUVABLE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-reintegrer-confirmer').click();
    await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveText('Ce coureur est introuvable.');
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
  });

  test('CA17 - 500 : « Service indisponible… », panneau conservé, nouvelle confirmation possible puis succès', async ({ page }) => {
    const etat = await simuler(
      page,
      UN_ABANDON,
      async (route, n) => (n === 1 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.fulfill(succes200(TOM, [1]))),
    );
    await ouvrir(page);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-reintegrer-confirmer').click();
    await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-reintegrer-confirmer')).toBeEnabled();
    await expect(lire(page, 'pilotage-reintegrer-annuler')).toBeEnabled();

    await lire(page, 'pilotage-reintegrer-confirmer').click();
    await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveText('Coureur n° 3 Tom réintégré : 1 boucle corrigée.');
    await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveCount(0);
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
    expect(etat.posts).toHaveLength(2);
  });

  test('CA17 - 404 COURSE_INTROUVABLE : retour à la liste des courses avec « Cette course n\'existe plus. »', async ({ page }) => {
    await simuler(page, UN_ABANDON, async (route) => route.fulfill(probleme(404, 'COURSE_INTROUVABLE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-reintegrer-confirmer').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await expect(page.getByTestId('courses-message-erreur')).toHaveText("Cette course n'existe plus.");
  });

  test('CA17 - 403 : redirection vers « accès refusé »', async ({ page }) => {
    await simuler(page, UN_ABANDON, async (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-reintegrer-confirmer').click();
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });

  test('CA17 - 401 : redirection vers la connexion avec retour au pilotage', async ({ page }) => {
    await simuler(page, UN_ABANDON, async (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-reintegrer-confirmer').click();
    await expect(page).toHaveURL(/\/connexion\?retour=/);
    expect(new URL(page.url()).search).toBe(`?retour=${encodeURIComponent(urlPilotage(ID_SIMULE))}`);
  });

  test('CA17 - double clic sur « Confirmer la réintégration » : une seule requête POST, sans corps, avec le jeton CSRF', async ({ page }) => {
    const etat = await simuler(
      page,
      UN_ABANDON,
      async (route) => {
        await new Promise((r) => setTimeout(r, 600));
        await route.fulfill(succes200(TOM, [1, 2]));
      },
    );
    await ouvrir(page);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-reintegrer-confirmer').dblclick();
    await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveText('Coureur n° 3 Tom réintégré : 2 boucles corrigées.');
    expect(etat.posts).toHaveLength(1);
    expect(etat.posts[0].corps ?? '').toBe('');
    expect(etat.posts[0].entetes['x-xsrf-token']).toBeTruthy();
  });

  for (const [corrigees, libelle] of [[0, '0 boucle corrigée'], [1, '1 boucle corrigée'], [2, '2 boucles corrigées']] as const) {
    test(`CA17 - le succès accorde « ${libelle} » selon la réponse du serveur`, async ({ page }) => {
      await simuler(page, UN_ABANDON, async (route) => route.fulfill(succes200(TOM, Array.from({ length: corrigees }, (_, i) => i + 1))));
      await ouvrir(page);
      await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
      await ouvrirPanneauTom(page);
      await lire(page, 'pilotage-reintegrer-confirmer').click();
      await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveText(`Coureur n° 3 Tom réintégré : ${libelle}.`);
      await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveAttribute('role', 'status');
    });
  }

  test('CA17 - coureur visé absent d\'un instantané reçu pendant la confirmation : panneau fermé avec « Ce coureur n\'est plus en abandon. »', async ({ page }) => {
    await simuler(page, (n) => (n === 1 ? instantane([BRUNO, CHLOE], [TOM]) : instantane([BRUNO, CHLOE, TOM], [])));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await ouvrirPanneauTom(page);
    await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveText("Ce coureur n'est plus en abandon.", { timeout: 8000 });
    await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);
  });

  test('CA17 - badge « corrigé » dans les trois sections quand nombreBouclesCorrigees vaut 2, absent à 0', async ({ page }) => {
    const bruno = { ...BRUNO, corrigees: 2 };
    const tom = { ...TOM, corrigees: 2 };
    const chloe = { ...CHLOE, corrigees: 2 };
    const sans = { dossard: 4, pseudo: 'Zoé', corrigees: 0 };
    await simuler(page, () => instantane([bruno, sans], [tom], [chloe]));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    for (const [badge, boucles] of [
      ['pilotage-inscrit-corrige', 'pilotage-inscrit-boucles-terminees'],
      ['pilotage-abandon-corrige', 'pilotage-abandon-boucles-terminees'],
      ['pilotage-vainqueur-corrige', 'pilotage-vainqueur-boucles-terminees'],
    ] as const) {
      await expect(lire(page, badge)).toHaveCount(1);
      await expect(lire(page, badge)).toHaveText('corrigé');
      await expect(lire(page, badge)).toHaveAttribute('aria-hidden', 'true');
      await expect(lire(page, boucles).filter({ has: lire(page, badge) })).toContainText('dont 2 boucles corrigées');
    }
    // la ligne sans correction n'a ni badge ni équivalent accessible
    const ligneSans = lire(page, 'pilotage-inscrit').filter({ hasText: sans.pseudo });
    await expect(ligneSans.getByTestId('pilotage-inscrit-corrige')).toHaveCount(0);
    await expect(ligneSans.getByTestId('pilotage-inscrit-boucles-terminees')).toHaveText('1');
    await expect(ligneSans).not.toContainText('corrig');
  });
});

// ---------- CA18 ----------

async function ouvrirMiseEnPage(page: Page, largeur: number, agrandi: boolean): Promise<void> {
  await page.setViewportSize({ width: largeur, height: 700 });
  const longue: Coureur = { dossard: 3, pseudo: PSEUDO_LONG, corrigees: 2 };
  await simuler(page, () => instantane([{ ...BRUNO, corrigees: 2 }], [longue], [{ ...CHLOE, pseudo: PSEUDO_LONG, corrigees: 2 }]));
  await ouvrir(page);
  if (agrandi) await page.addStyleTag({ content: 'html{font-size:32px}' });
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(1);
  await boutonReintegrer(page, 3, PSEUDO_LONG).click();
  await expect(lire(page, 'pilotage-reintegrer-confirmation')).toBeVisible();
  // l'erreur est visible aussi : c'est le cas le plus large
  await lire(page, 'pilotage-reintegrer-confirmer').click();
  await expect(lire(page, 'pilotage-reintegrer-erreur')).toBeVisible();
}

for (const [largeur, agrandi] of [[375, false], [320, false], [320, true]] as const) {
  test(`CA18 - à ${largeur} px${agrandi ? ' avec un texte à 200 %' : ''} : aucun défilement horizontal, panneau et badges contenus dans la carte, cibles de 44 px`, async ({ page }) => {
    await ouvrirMiseEnPage(page, largeur, agrandi);
    const m = await page.evaluate(() => {
      const el = document.documentElement;
      const carte = document.querySelector('main section')!.getBoundingClientRect();
      const boite = (e: Element) => {
        const r = e.getBoundingClientRect();
        return { left: r.left, right: r.right, height: r.height, largeur: r.width };
      };
      const tous = (id: string) => Array.from(document.querySelectorAll(`[data-testid="${id}"]`)).map(boite);
      return {
        debordeX: el.scrollWidth > el.clientWidth,
        carte: { gauche: carte.left, droite: carte.right },
        panneau: tous('pilotage-reintegrer-confirmation'),
        titre: tous('pilotage-reintegrer-titre'),
        erreur: tous('pilotage-reintegrer-erreur'),
        confirmer: tous('pilotage-reintegrer-confirmer'),
        annuler: tous('pilotage-reintegrer-annuler'),
        boutonLigne: tous('pilotage-abandon-reintegrer'),
        badges: [...tous('pilotage-inscrit-corrige'), ...tous('pilotage-abandon-corrige'), ...tous('pilotage-vainqueur-corrige')],
        pseudos: [...tous('pilotage-abandon-pseudo'), ...tous('pilotage-vainqueur-pseudo')],
      };
    });
    expect(m.debordeX, 'défilement horizontal').toBe(false);
    for (const [nom, boites] of Object.entries({ panneau: m.panneau, titre: m.titre, erreur: m.erreur, confirmer: m.confirmer, annuler: m.annuler, boutonLigne: m.boutonLigne, badges: m.badges, pseudos: m.pseudos })) {
      expect(boites.length, nom).toBeGreaterThan(0);
      for (const b of boites) {
        expect(b.left, `${nom} déborde à gauche de la carte`).toBeGreaterThanOrEqual(m.carte.gauche - 1);
        expect(b.right, `${nom} déborde à droite de la carte`).toBeLessThanOrEqual(m.carte.droite + 1);
      }
    }
    expect(m.badges).toHaveLength(3);
    for (const [nom, boites] of Object.entries({ confirmer: m.confirmer, annuler: m.annuler, boutonLigne: m.boutonLigne })) {
      for (const b of boites) expect(b.height, `cible ${nom} de moins de 44 px`).toBeGreaterThanOrEqual(43.5);
    }
  });
}

test('CA18 - clavier : Tab jusqu\'au bouton, Entrée ouvre (focus sur le titre), Échap annule et rend le focus, Entrée sur « Confirmer » envoie, focus visible', async ({ page }) => {
  const etat = await simuler(page, UN_ABANDON, async (route) => route.fulfill(succes200(TOM)));
  await page.setViewportSize({ width: 375, height: 700 });
  await ouvrir(page);
  await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);

  const focusVisible = async (testid: string) => {
    const contour = await page.locator(`[data-testid="${testid}"]:focus`).evaluate((e) => {
      const s = getComputedStyle(e);
      return { style: s.outlineStyle, largeur: parseFloat(s.outlineWidth), ombre: s.boxShadow };
    });
    expect((contour.style !== 'none' && contour.largeur > 0) || contour.ombre !== 'none', `focus invisible sur ${testid} : ${JSON.stringify(contour)}`).toBe(true);
  };

  const bouton = boutonReintegrer(page, 3, TOM.pseudo);
  for (let i = 0; i < 60 && !(await bouton.evaluate((e) => e === document.activeElement)); i++) await page.keyboard.press('Tab');
  await expect(bouton).toBeFocused();
  await focusVisible('pilotage-abandon-reintegrer');
  await expect(bouton).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('Enter');
  await expect(lire(page, 'pilotage-reintegrer-titre')).toBeFocused();
  await expect(bouton).toHaveAttribute('aria-expanded', 'true');

  await page.keyboard.press('Escape');
  await expect(lire(page, 'pilotage-reintegrer-confirmation')).toHaveCount(0);
  await expect(bouton).toBeFocused();
  expect(etat.posts).toHaveLength(0);

  await page.keyboard.press('Enter');
  await expect(lire(page, 'pilotage-reintegrer-titre')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(lire(page, 'pilotage-reintegrer-confirmer')).toBeFocused();
  await focusVisible('pilotage-reintegrer-confirmer');
  await page.keyboard.press('Tab');
  await expect(lire(page, 'pilotage-reintegrer-annuler')).toBeFocused();
  await focusVisible('pilotage-reintegrer-annuler');
  await page.keyboard.press('Shift+Tab');
  await expect(lire(page, 'pilotage-reintegrer-confirmer')).toBeFocused();

  await page.keyboard.press('Enter');
  await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveText('Coureur n° 3 Tom réintégré : 1 boucle corrigée.');
  expect(etat.posts).toHaveLength(1);
});

test('CA18 - rôles : status pour le seul succès, alert pour les seules erreurs, panneau en groupe nommé par son titre, badge en texte avec équivalent accessible', async ({ page }) => {
  await simuler(page, () => instantane([BRUNO], [{ ...TOM, corrigees: 2 }, CHLOE]), async (route, n) =>
    n === 1 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.fulfill(succes200(CHLOE, [1])),
  );
  await ouvrir(page);
  await expect(lire(page, 'pilotage-abandon')).toHaveCount(2);
  await expect(page.locator('main [data-testid="pilotage-reintegrer-succes"], main [data-testid="pilotage-reintegrer-erreur"]')).toHaveCount(0);

  await boutonReintegrer(page, 3, TOM.pseudo).click();
  const panneau = lire(page, 'pilotage-reintegrer-confirmation');
  await expect(panneau).toHaveAttribute('role', 'group');
  await expect(panneau).toHaveAttribute('aria-labelledby', 'titre-reintegration');
  await expect(page.getByRole('group', { name: `Réintégrer le coureur n° 3 ${TOM.pseudo} ?` })).toBeVisible();
  await expect(page.locator('[data-testid="pilotage-reintegrer-confirmation"] [role="status"], [data-testid="pilotage-reintegrer-confirmation"] [role="alert"]')).toHaveCount(0);

  // badge : texte « corrigé » (aria-hidden) + équivalent accessible dans l'arbre d'accessibilité de la cellule
  const cellule = lire(page, 'pilotage-abandon-boucles-terminees').filter({ has: lire(page, 'pilotage-abandon-corrige') });
  await expect(cellule).toContainText('dont 2 boucles corrigées');
  await expect(page.getByRole('cell', { name: /dont 2 boucles corrigées/ })).toHaveCount(1);
  await expect(page.getByRole('cell', { name: /corrigé(?!e)/ })).toHaveCount(0);
  await expect(lire(page, 'pilotage-abandon-corrige')).toBeVisible();

  await lire(page, 'pilotage-reintegrer-confirmer').click();
  await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveAttribute('role', 'alert');
  await expect(lire(page, 'pilotage-reintegrer-erreur')).not.toHaveAttribute('role', 'status');
  await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveCount(0);

  await lire(page, 'pilotage-reintegrer-confirmer').click();
  await expect(lire(page, 'pilotage-reintegrer-succes')).toHaveAttribute('role', 'status');
  await expect(lire(page, 'pilotage-reintegrer-succes')).not.toHaveAttribute('role', 'alert');
  await expect(lire(page, 'pilotage-reintegrer-erreur')).toHaveCount(0);
  await expect(page.locator('main [role="alert"]')).toHaveCount(0);
});

test('CA18 - contraste AA, aucune couleur en dur, prefers-reduced-motion sans animation ni transition', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await simuler(page, () => instantane([{ ...BRUNO, corrigees: 1 }], [{ ...TOM, corrigees: 2 }]), async (route) => route.fulfill(probleme(500, 'ERREUR_INTERNE')));
  await page.setViewportSize({ width: 375, height: 700 });
  await ouvrir(page);
  await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
  await boutonReintegrer(page, 3, TOM.pseudo).click();
  await lire(page, 'pilotage-reintegrer-confirmer').click();
  await expect(lire(page, 'pilotage-reintegrer-erreur')).toBeVisible();

  const mesures = await page.evaluate(() => {
    const parse = (c: string): number[] => {
      const m = c.match(/rgba?\(([^)]+)\)/);
      if (!m) throw new Error(`couleur inattendue : ${c}`);
      const p = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
      return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
    };
    const lum = ([r, g, b]: number[]) => {
      const f = (v: number) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
      };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    const fond = (e: Element): number[] => {
      for (let n: Element | null = e; n; n = n.parentElement) {
        const c = parse(getComputedStyle(n).backgroundColor);
        if (c[3] === 1) return c;
      }
      return [255, 255, 255, 1];
    };
    const ratio = (e: Element) => {
      const l1 = lum(parse(getComputedStyle(e).color));
      const l2 = lum(fond(e));
      return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    };
    const ids = [
      'pilotage-reintegrer-titre',
      'pilotage-reintegrer-erreur',
      'pilotage-reintegrer-confirmer',
      'pilotage-reintegrer-annuler',
      'pilotage-abandon-reintegrer',
      'pilotage-abandon-corrige',
      'pilotage-inscrit-corrige',
    ];
    const ratios = [
      ...ids.flatMap((id) => Array.from(document.querySelectorAll(`[data-testid="${id}"]`)).map((e) => ({ id, ratio: ratio(e) }))),
      ...Array.from(document.querySelectorAll('.confirmation__texte')).map((e) => ({ id: '.confirmation__texte', ratio: ratio(e) })),
    ];
    const regles: string[] = [];
    const motif = /confirmation|badge-corrige/;
    for (const feuille of Array.from(document.styleSheets)) {
      let regs: CSSRuleList;
      try {
        regs = feuille.cssRules;
      } catch {
        continue;
      }
      for (const r of Array.from(regs)) {
        if (r instanceof CSSStyleRule && motif.test(r.selectorText)) regles.push(r.cssText);
        else if (r instanceof CSSContainerRule || r instanceof CSSMediaRule) {
          for (const sr of Array.from(r.cssRules)) {
            if (sr instanceof CSSStyleRule && motif.test(sr.selectorText)) regles.push(sr.cssText);
          }
        }
      }
    }
    const anime = Array.from(
      document.querySelectorAll(
        '[data-testid="pilotage-reintegrer-confirmation"], [data-testid="pilotage-reintegrer-confirmation"] *, [data-testid="pilotage-reintegrer-erreur"], [data-testid="pilotage-abandon-reintegrer"], [data-testid="pilotage-abandon-corrige"], [data-testid="pilotage-inscrit-corrige"]',
      ),
    )
      .filter((e) => {
        const s = getComputedStyle(e);
        const dur = (v: string) => Math.max(0, ...v.split(',').map((x) => parseFloat(x) || 0));
        return (s.animationName !== 'none' && dur(s.animationDuration) > 0) || dur(s.transitionDuration) > 0;
      })
      .map((e) => e.tagName.toLowerCase());
    return { ratios, regles, anime };
  });
  expect(mesures.ratios.length).toBeGreaterThanOrEqual(7);
  for (const r of mesures.ratios) expect(r.ratio, `contraste de ${r.id}`).toBeGreaterThanOrEqual(4.5);
  expect(mesures.regles.length).toBeGreaterThan(0);
  for (const t of mesures.regles) expect(t, 'couleur en dur').not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/);
  expect(mesures.anime, 'animation ou transition avec prefers-reduced-motion').toEqual([]);
});

test('CA18 - le bouton de confirmation est de style principal (action non destructive), « Annuler » secondaire', async ({ page }) => {
  await simuler(page, UN_ABANDON);
  await ouvrir(page);
  await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
  await ouvrirPanneauTom(page);
  await expect(lire(page, 'pilotage-reintegrer-confirmer')).toHaveClass(/(^|\s)bouton(\s|$)/);
  await expect(lire(page, 'pilotage-reintegrer-confirmer')).not.toHaveClass(/danger|secondaire/);
  await expect(lire(page, 'pilotage-reintegrer-annuler')).toHaveClass(/secondaire/);
});
