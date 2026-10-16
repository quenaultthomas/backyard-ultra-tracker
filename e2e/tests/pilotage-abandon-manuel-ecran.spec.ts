import { expect, test, type Page, type Route } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { connecterPatron, lire, urlPilotage } from './aide-abandon';

/*
 * Incrément 4.11 : écran de pilotage avec réponses fabriquées (page.route), CA17 (erreurs, double clic, requête)
 * et CA18 (mise en page, clavier, accessibilité). Le panneau et les messages sont ceux de l'écran réel ;
 * seules les réponses de GET .../pilotage et de POST .../abandon sont simulées.
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
}

const BRUNO: Coureur = { dossard: 1, pseudo: 'Bruno' };
const TOM: Coureur = { dossard: 3, pseudo: 'Tom' };

const enCourse = (c: Coureur) => ({
  inscriptionId: `00000000-0000-0000-0000-0000000000a${c.dossard}`,
  dossard: c.dossard,
  pseudo: c.pseudo,
  statut: 'EN_COURSE',
  nombreBouclesTerminees: 1,
  boucleCouranteTerminee: false,
});

const enAbandon = (c: Coureur) => ({
  inscriptionId: `00000000-0000-0000-0000-0000000000a${c.dossard}`,
  dossard: c.dossard,
  pseudo: c.pseudo,
  statut: 'ABANDON',
  nombreBouclesTerminees: 1,
  boucleAbandon: 2,
  motifAbandon: 'MANUEL',
});

function instantane(enCours: Coureur[], abandons: Coureur[] = []): Record<string, unknown> {
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
    nombreInscrits: enCours.length + abandons.length,
    nombreAyantBoucle: 0,
    nombreEnBoucle: enCours.length,
    inscritsEnCourse: enCours.map(enCourse),
    nombreAbandons: abandons.length,
    inscritsEnAbandon: abandons.map(enAbandon),
    nombreVainqueurs: 0,
    inscritsVainqueurs: [],
  };
}

interface Simulation {
  pilotage: number;
  posts: Array<{ corps: unknown; entetes: Record<string, string> }>;
}

type RepondrePilotage = (n: number) => Record<string, unknown>;
type RepondreAbandon = (route: Route, n: number) => Promise<void>;

const probleme = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ type: 'about:blank', status, code }),
});

const succes200 = (c: Coureur, statutCourse = 'EN_COURS') => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({
    inscriptionId: `00000000-0000-0000-0000-0000000000a${c.dossard}`,
    dossard: c.dossard,
    statut: 'ABANDON',
    motifAbandon: 'MANUEL',
    boucleAbandon: 2,
    statutCourse,
  }),
});

async function simuler(page: Page, pilotage: RepondrePilotage, abandon: RepondreAbandon = async (route) => route.fulfill(probleme(500, 'ERREUR_INTERNE'))): Promise<Simulation> {
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
  await page.route('**/api/administration/courses/*/inscriptions/*/abandon', async (route) => {
    etat.posts.push({ corps: route.request().postDataJSON(), entetes: route.request().headers() });
    await abandon(route, etat.posts.length);
  });
  return etat;
}

async function ouvrir(page: Page, id = ID_SIMULE): Promise<void> {
  await connecterPatron(page);
  await page.goto(urlPilotage(id));
}

const boutonDe = (page: Page, c: Coureur) => page.getByRole('button', { name: `Abandonner le coureur n° ${c.dossard} ${c.pseudo}` });

async function ouvrirPanneauTom(page: Page, motif: 'VOLONTAIRE' | 'MANUEL' | 'AUTRE' = 'MANUEL'): Promise<void> {
  await boutonDe(page, TOM).click();
  await expect(lire(page, 'pilotage-abandonner-confirmation')).toBeVisible();
  await lire(page, `pilotage-abandonner-motif-${motif}`).check();
}

const DEUX = [BRUNO, TOM];

test.describe('Abandon manuel, erreurs et requête (CA17)', () => {
  test('CA17 - 409 INSCRIPTION_NON_EN_COURSE : message, panneau fermé, une requête de pilotage part aussitôt', async ({ page }) => {
    const etat = await simuler(
      page,
      () => instantane(DEUX),
      async (route) => route.fulfill(probleme(409, 'INSCRIPTION_NON_EN_COURSE')),
    );
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await ouvrirPanneauTom(page);
    // caler le clic juste après un passage du polling (prochain dans ~3 s)
    const n0 = etat.pilotage;
    await expect.poll(() => etat.pilotage, { timeout: 8000 }).toBeGreaterThan(n0);
    const avant = etat.pilotage;
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(lire(page, 'pilotage-abandonner-erreur')).toHaveText("Ce coureur n'est plus en course.");
    await expect(lire(page, 'pilotage-abandonner-erreur')).toHaveAttribute('role', 'alert');
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
    await expect.poll(() => etat.pilotage, { timeout: 1500 }).toBeGreaterThan(avant);
    expect(etat.posts).toHaveLength(1);
  });

  test('CA17 - 409 COURSE_TERMINEE : « La course est terminée : aucun abandon n\'est possible. », panneau fermé, rafraîchissement immédiat', async ({ page }) => {
    const etat = await simuler(
      page,
      () => instantane(DEUX),
      async (route) => route.fulfill(probleme(409, 'COURSE_TERMINEE')),
    );
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await ouvrirPanneauTom(page);
    const n0 = etat.pilotage;
    await expect.poll(() => etat.pilotage, { timeout: 8000 }).toBeGreaterThan(n0);
    const avant = etat.pilotage;
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(lire(page, 'pilotage-abandonner-erreur')).toHaveText("La course est terminée : aucun abandon n'est possible.");
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
    await expect.poll(() => etat.pilotage, { timeout: 1500 }).toBeGreaterThan(avant);
  });

  test('CA17 - 404 INSCRIPTION_INTROUVABLE : « Ce coureur est introuvable. », panneau fermé', async ({ page }) => {
    await simuler(page, () => instantane(DEUX), async (route) => route.fulfill(probleme(404, 'INSCRIPTION_INTROUVABLE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(lire(page, 'pilotage-abandonner-erreur')).toHaveText('Ce coureur est introuvable.');
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
  });

  test('CA17 - 500 : « Service indisponible… », panneau et motif conservés, nouvelle confirmation possible', async ({ page }) => {
    const etat = await simuler(
      page,
      () => instantane(DEUX),
      async (route, n) => (n === 1 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.fulfill(succes200(TOM))),
    );
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await ouvrirPanneauTom(page, 'AUTRE');
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(lire(page, 'pilotage-abandonner-erreur')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-abandonner-motif-AUTRE')).toBeChecked();
    await expect(lire(page, 'pilotage-abandonner-confirmer')).toBeEnabled();

    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(lire(page, 'pilotage-abandonner-succes')).toHaveText('Abandon enregistré : coureur n° 3 Tom.');
    await expect(lire(page, 'pilotage-abandonner-erreur')).toHaveCount(0);
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
    expect(etat.posts).toHaveLength(2);
    expect(etat.posts[1].corps).toEqual({ motif: 'AUTRE' });
  });

  test('CA17 - 404 COURSE_INTROUVABLE : retour à la liste des courses avec « Cette course n\'existe plus. »', async ({ page }) => {
    await simuler(page, () => instantane(DEUX), async (route) => route.fulfill(probleme(404, 'COURSE_INTROUVABLE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await expect(page.getByTestId('courses-message-erreur')).toHaveText("Cette course n'existe plus.");
  });

  test('CA17 - 403 : redirection vers « accès refusé »', async ({ page }) => {
    await simuler(page, () => instantane(DEUX), async (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });

  test('CA17 - 401 : redirection vers la connexion avec retour au pilotage', async ({ page }) => {
    await simuler(page, () => instantane(DEUX), async (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await ouvrirPanneauTom(page);
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(page).toHaveURL(/\/connexion\?retour=/);
    expect(new URL(page.url()).search).toBe(`?retour=${encodeURIComponent(urlPilotage(ID_SIMULE))}`);
  });

  test('CA17 - double clic sur « Confirmer l\'abandon » : une seule requête, corps { motif } et jeton CSRF, rien d\'autre', async ({ page }) => {
    const etat = await simuler(
      page,
      () => instantane(DEUX),
      async (route) => {
        await new Promise((r) => setTimeout(r, 600));
        await route.fulfill(succes200(TOM));
      },
    );
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await ouvrirPanneauTom(page, 'VOLONTAIRE');
    await lire(page, 'pilotage-abandonner-confirmer').dblclick();
    await expect(lire(page, 'pilotage-abandonner-succes')).toHaveText('Abandon enregistré : coureur n° 3 Tom.');
    expect(etat.posts).toHaveLength(1);
    expect(etat.posts[0].corps).toEqual({ motif: 'VOLONTAIRE' });
    expect(etat.posts[0].entetes['x-xsrf-token']).toBeTruthy();
    expect(etat.posts[0].entetes['content-type']).toContain('application/json');
  });

  test('CA17 - succès avec statutCourse TERMINEE : le message ajoute « La course est terminée : plus aucun coureur en course. »', async ({ page }) => {
    await simuler(
      page,
      (n) => (n === 1 ? instantane([TOM]) : { ...instantane([], [TOM]), statut: 'TERMINEE', boucleCourante: null, demarreeLe: null, debutBoucleCourante: null, finBoucleCourante: null, tempsAvantFinBoucleMillisecondes: null, dureeEcouleeMillisecondes: null, nombreAyantBoucle: null, nombreEnBoucle: null }),
      async (route) => route.fulfill(succes200(TOM, 'TERMINEE')),
    );
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(1);
    await boutonDe(page, TOM).click();
    await expect(lire(page, 'pilotage-abandonner-avertissement-dernier')).toBeVisible();
    await lire(page, 'pilotage-abandonner-motif-MANUEL').check();
    await lire(page, 'pilotage-abandonner-confirmer').click();
    await expect(lire(page, 'pilotage-abandonner-succes')).toContainText('La course est terminée : plus aucun coureur en course.');
    await expect(lire(page, 'pilotage-aucun-vainqueur')).toHaveText('Aucun vainqueur : tous les coureurs ont abandonné.');
    await expect(lire(page, 'pilotage-inscrit-abandonner')).toHaveCount(0);
  });

  test('CA17 - coureur visé absent d\'un instantané reçu pendant la confirmation : panneau fermé avec « Ce coureur n\'est plus en course. »', async ({ page }) => {
    await simuler(page, (n) => (n === 1 ? instantane(DEUX) : instantane([BRUNO], [TOM])));
    await ouvrir(page);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await boutonDe(page, TOM).click();
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toBeVisible();
    await expect(lire(page, 'pilotage-abandonner-erreur')).toHaveText("Ce coureur n'est plus en course.", { timeout: 8000 });
    await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(1);
  });
});

// ---------- CA18 ----------

async function ouvrirMiseEnPage(page: Page, largeur: number, agrandi: boolean): Promise<void> {
  await page.setViewportSize({ width: largeur, height: 700 });
  const longue: Coureur = { dossard: 3, pseudo: PSEUDO_LONG };
  await simuler(page, () => instantane([BRUNO, longue]));
  await ouvrir(page);
  if (agrandi) await page.addStyleTag({ content: 'html{font-size:32px}' });
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
  await page.getByRole('button', { name: `Abandonner le coureur n° 3 ${PSEUDO_LONG}` }).click();
  await expect(lire(page, 'pilotage-abandonner-confirmation')).toBeVisible();
  // l'erreur de motif est visible aussi : c'est le cas le plus large
  await lire(page, 'pilotage-abandonner-confirmer').click();
  await expect(lire(page, 'pilotage-abandonner-erreur-motif')).toBeVisible();
}

for (const [largeur, agrandi] of [[375, false], [320, false], [320, true]] as const) {
  test(`CA18 - à ${largeur} px${agrandi ? ' avec un texte à 200 %' : ''} : aucun défilement horizontal, panneau contenu dans la carte, cibles de 44 px`, async ({ page }) => {
    await ouvrirMiseEnPage(page, largeur, agrandi);
    const m = await page.evaluate(() => {
      const el = document.documentElement;
      const carte = document.querySelector('main section')!.getBoundingClientRect();
      const boite = (e: Element) => {
        const r = e.getBoundingClientRect();
        return { left: r.left, right: r.right, height: r.height };
      };
      const tous = (id: string) => Array.from(document.querySelectorAll(`[data-testid="${id}"]`)).map(boite);
      return {
        debordeX: el.scrollWidth > el.clientWidth,
        carte: { gauche: carte.left, droite: carte.right },
        panneau: tous('pilotage-abandonner-confirmation'),
        titre: tous('pilotage-abandonner-titre'),
        motifs: tous('pilotage-abandonner-motifs'),
        boutonsLigne: tous('pilotage-inscrit-abandonner'),
        confirmer: tous('pilotage-abandonner-confirmer'),
        annuler: tous('pilotage-abandonner-annuler'),
        tuiles: Array.from(document.querySelectorAll('.motif-abandon')).map(boite),
        pseudos: tous('pilotage-inscrit-pseudo'),
      };
    });
    expect(m.debordeX, 'défilement horizontal').toBe(false);
    for (const [nom, boites] of Object.entries({ panneau: m.panneau, titre: m.titre, motifs: m.motifs, confirmer: m.confirmer, annuler: m.annuler, boutonsLigne: m.boutonsLigne, pseudos: m.pseudos })) {
      expect(boites.length, nom).toBeGreaterThan(0);
      for (const b of boites) {
        expect(b.left, `${nom} déborde à gauche de la carte`).toBeGreaterThanOrEqual(m.carte.gauche - 1);
        expect(b.right, `${nom} déborde à droite de la carte`).toBeLessThanOrEqual(m.carte.droite + 1);
      }
    }
    expect(m.tuiles).toHaveLength(3);
    for (const [nom, boites] of Object.entries({ confirmer: m.confirmer, annuler: m.annuler, boutonsLigne: m.boutonsLigne, tuiles: m.tuiles })) {
      for (const b of boites) expect(b.height, `cible ${nom} de moins de 44 px`).toBeGreaterThanOrEqual(43.5);
    }
  });
}

test('CA18 - clavier : Tab, flèches du groupe de motifs, Entrée confirme, Échap annule et rend le focus, focus visible', async ({ page }) => {
  const etat = await simuler(page, () => instantane(DEUX), async (route) => route.fulfill(succes200(TOM)));
  await page.setViewportSize({ width: 375, height: 700 });
  await ouvrir(page);
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);

  const focusVisible = async (testid: string) => {
    const contour = await page.locator(`[data-testid="${testid}"]:focus`).evaluate((e) => {
      const s = getComputedStyle(e);
      return { style: s.outlineStyle, largeur: parseFloat(s.outlineWidth), ombre: s.boxShadow };
    });
    expect((contour.style !== 'none' && contour.largeur > 0) || contour.ombre !== 'none', `focus invisible sur ${testid} : ${JSON.stringify(contour)}`).toBe(true);
  };

  // atteindre le bouton de Tom au clavier
  const bouton = boutonDe(page, TOM);
  for (let i = 0; i < 40 && !(await bouton.evaluate((e) => e === document.activeElement)); i++) await page.keyboard.press('Tab');
  await expect(bouton).toBeFocused();
  await focusVisible('pilotage-inscrit-abandonner');
  await expect(bouton).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('Enter');
  await expect(lire(page, 'pilotage-abandonner-titre')).toBeFocused();
  await expect(bouton).toHaveAttribute('aria-expanded', 'true');

  // Échap annule et rend le focus
  await page.keyboard.press('Escape');
  await expect(lire(page, 'pilotage-abandonner-confirmation')).toHaveCount(0);
  await expect(bouton).toBeFocused();
  expect(etat.posts).toHaveLength(0);

  // rouvrir, Tab vers le groupe de motifs, flèches
  await page.keyboard.press('Enter');
  await expect(lire(page, 'pilotage-abandonner-titre')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(lire(page, 'pilotage-abandonner-motif-VOLONTAIRE')).toBeFocused();
  await focusVisible('pilotage-abandonner-motif-VOLONTAIRE');
  await page.keyboard.press('ArrowDown');
  await expect(lire(page, 'pilotage-abandonner-motif-MANUEL')).toBeFocused();
  await expect(lire(page, 'pilotage-abandonner-motif-MANUEL')).toBeChecked();
  await page.keyboard.press('ArrowDown');
  await expect(lire(page, 'pilotage-abandonner-motif-AUTRE')).toBeChecked();
  await page.keyboard.press('ArrowUp');
  await expect(lire(page, 'pilotage-abandonner-motif-MANUEL')).toBeChecked();

  // Tab vers « Confirmer l'abandon » (le nombre d'arrêts dans le groupe est vérifié par un test dédié) puis « Annuler » : focus visible
  for (let i = 0; i < 4 && !(await lire(page, 'pilotage-abandonner-confirmer').evaluate((e) => e === document.activeElement)); i++) await page.keyboard.press('Tab');
  await expect(lire(page, 'pilotage-abandonner-confirmer')).toBeFocused();
  await focusVisible('pilotage-abandonner-confirmer');
  await page.keyboard.press('Tab');
  await expect(lire(page, 'pilotage-abandonner-annuler')).toBeFocused();
  await focusVisible('pilotage-abandonner-annuler');

  // Entrée confirme (depuis le groupe de motifs)
  await lire(page, 'pilotage-abandonner-motif-MANUEL').focus();
  await expect(lire(page, 'pilotage-abandonner-motif-MANUEL')).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(lire(page, 'pilotage-abandonner-succes')).toHaveText('Abandon enregistré : coureur n° 3 Tom.');
  expect(etat.posts).toHaveLength(1);
  expect(etat.posts[0].corps).toEqual({ motif: 'MANUEL' });
});

test('CA18 - motif en fieldset / legend avec boutons radio, erreur reliée par aria-describedby, rôles status (succès) et alert (erreurs) seulement', async ({ page }) => {
  await simuler(page, () => instantane(DEUX), async (route) => route.fulfill(succes200(TOM)));
  await ouvrir(page);
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
  await expect(page.locator('main [data-testid="pilotage-abandonner-succes"], main [data-testid="pilotage-abandonner-erreur"]')).toHaveCount(0);
  await boutonDe(page, TOM).click();

  const groupe = lire(page, 'pilotage-abandonner-motifs');
  expect(await groupe.evaluate((e) => e.tagName)).toBe('FIELDSET');
  await expect(groupe.locator('legend')).toHaveText("Motif de l'abandon");
  await expect(groupe.getByRole('radio')).toHaveCount(3);
  await expect(groupe.getByRole('radio', { name: 'Volontaire' })).toBeVisible();
  await expect(groupe.getByRole('radio', { name: 'Manuel' })).toBeVisible();
  await expect(groupe.getByRole('radio', { name: 'Autre' })).toBeVisible();
  await expect(groupe).not.toHaveAttribute('aria-describedby', /.+/);

  await lire(page, 'pilotage-abandonner-confirmer').click();
  const erreurMotif = lire(page, 'pilotage-abandonner-erreur-motif');
  await expect(erreurMotif).toHaveText("Choisissez un motif d'abandon.");
  await expect(erreurMotif).toHaveAttribute('role', 'alert');
  const idErreur = await erreurMotif.getAttribute('id');
  expect(idErreur).toBeTruthy();
  await expect(groupe).toHaveAttribute('aria-describedby', idErreur!);
  await expect(page.locator('[data-testid="pilotage-abandonner-confirmation"] [role="status"]')).toHaveCount(0);

  await lire(page, 'pilotage-abandonner-motif-MANUEL').check();
  await lire(page, 'pilotage-abandonner-confirmer').click();
  await expect(lire(page, 'pilotage-abandonner-succes')).toHaveAttribute('role', 'status');
  await expect(lire(page, 'pilotage-abandonner-succes')).not.toHaveAttribute('role', 'alert');
  await expect(page.locator('main [role="alert"]')).toHaveCount(0);
});

test('CA18 - contraste AA, aucune couleur en dur dans le panneau, prefers-reduced-motion sans animation', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await simuler(page, () => instantane(DEUX), async (route) => route.fulfill(probleme(409, 'INSCRIPTION_NON_EN_COURSE')));
  await page.setViewportSize({ width: 375, height: 700 });
  await ouvrir(page);
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
  await boutonDe(page, BRUNO).click();
  await lire(page, 'pilotage-abandonner-motif-VOLONTAIRE').check();
  await lire(page, 'pilotage-abandonner-confirmer').click();
  await expect(lire(page, 'pilotage-abandonner-erreur')).toBeVisible();
  await boutonDe(page, TOM).click();
  await lire(page, 'pilotage-abandonner-confirmer').click();
  await expect(lire(page, 'pilotage-abandonner-erreur-motif')).toBeVisible();

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
    const ids = [
      'pilotage-abandonner-titre',
      'pilotage-abandonner-erreur',
      'pilotage-abandonner-erreur-motif',
      'pilotage-abandonner-confirmer',
      'pilotage-abandonner-annuler',
      'pilotage-inscrit-abandonner',
    ];
    const ratios = ids.flatMap((id) =>
      Array.from(document.querySelectorAll(`[data-testid="${id}"]`)).map((e) => {
        const l1 = lum(parse(getComputedStyle(e).color));
        const l2 = lum(fond(e));
        return { id, ratio: (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05) };
      }),
    );
    const extras = ['.confirmation__texte', '.motif-abandon label', '.motifs-abandon__legende'].flatMap((sel) =>
      Array.from(document.querySelectorAll(sel)).map((e) => {
        const l1 = lum(parse(getComputedStyle(e).color));
        const l2 = lum(fond(e));
        return { id: sel, ratio: (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05) };
      }),
    );
    const regles: string[] = [];
    for (const feuille of Array.from(document.styleSheets)) {
      let regs: CSSRuleList;
      try {
        regs = feuille.cssRules;
      } catch {
        continue;
      }
      for (const r of Array.from(regs)) {
        if (r instanceof CSSStyleRule && /confirmation|motif-abandon|motifs-abandon/.test(r.selectorText)) regles.push(r.cssText);
        else if (r instanceof CSSContainerRule || r instanceof CSSMediaRule) {
          for (const sr of Array.from(r.cssRules)) {
            if (sr instanceof CSSStyleRule && /confirmation|motif-abandon|motifs-abandon/.test(sr.selectorText)) regles.push(sr.cssText);
          }
        }
      }
    }
    const anime = Array.from(document.querySelectorAll('[data-testid="pilotage-abandonner-confirmation"], [data-testid="pilotage-abandonner-confirmation"] *, [data-testid="pilotage-abandonner-erreur"], [data-testid="pilotage-inscrit-abandonner"]'))
      .filter((e) => {
        const s = getComputedStyle(e);
        const dur = (v: string) => Math.max(0, ...v.split(',').map((x) => parseFloat(x) || 0));
        return (s.animationName !== 'none' && dur(s.animationDuration) > 0) || dur(s.transitionDuration) > 0;
      })
      .map((e) => e.tagName.toLowerCase());
    return { ratios: [...ratios, ...extras], regles, anime };
  });
  expect(mesures.ratios.length).toBeGreaterThanOrEqual(9);
  for (const r of mesures.ratios) expect(r.ratio, `contraste de ${r.id}`).toBeGreaterThanOrEqual(4.5);
  expect(mesures.regles.length).toBeGreaterThan(0);
  for (const t of mesures.regles) expect(t, 'couleur en dur').not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/);
  expect(mesures.anime, 'animation ou transition avec prefers-reduced-motion').toEqual([]);
});

test('CA18 - le groupe de motifs est un vrai groupe de boutons radio : même nom, un seul arrêt de Tab, Tab quitte le groupe vers « Confirmer l\'abandon »', async ({ page }) => {
  await simuler(page, () => instantane(DEUX));
  await ouvrir(page);
  await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
  await boutonDe(page, TOM).click();
  await expect(lire(page, 'pilotage-abandonner-titre')).toBeFocused();
  const noms = await page.locator('[data-testid="pilotage-abandonner-motifs"] input[type="radio"]').evaluateAll((els) => els.map((e) => (e as HTMLInputElement).name));
  expect(new Set(noms).size, `noms des boutons radio : ${JSON.stringify(noms)}`).toBe(1);
  expect(noms[0]).not.toBe('');

  await page.keyboard.press('Tab');
  await expect(lire(page, 'pilotage-abandonner-motif-VOLONTAIRE')).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(lire(page, 'pilotage-abandonner-motif-MANUEL')).toBeChecked();
  await page.keyboard.press('Tab');
  await expect(lire(page, 'pilotage-abandonner-confirmer')).toBeFocused();
});
