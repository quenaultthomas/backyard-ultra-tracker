import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
  MOT_DE_PASSE_ADMIN_MASTER,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  dateDansJours,
  demarrerCourseParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { placerStatutCourseEnBase, sinscrireParApi } from './aide-coureur';

/*
 * Incrément 4.2 : écran de pilotage (CA13 à CA19 ; CA20 = non-régression de la suite complète).
 * Aucune horloge serveur pilotable avant 4.3 : CA13 et CA14 utilisent une vraie Course de 1 minute datée du jour
 * à Paris (un test lancé à cheval sur minuit peut échouer : le relancer) ; CA15 à CA17 et CA19 interceptent
 * GET .../pilotage (page.route) et pilotent l'horloge du navigateur (page.clock).
 */
type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const AGRANDIR = 'html{font-size:32px}';
const HORLOGE_DEPART = new Date('2026-06-01T10:00:00Z');
const T0 = Date.parse('2026-10-09T14:03:27Z');
const COURT = { timeout: 200 };

const urlPilotage = (id: string) => `/administration/courses/${id}/pilotage`;
const urlFiche = (id: string) => `/administration/courses/${id}`;

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

async function creerCoureur(playwright: PlaywrightLib, prefixe: string): Promise<string> {
  const pseudo = pseudoUnique(prefixe);
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  return pseudo;
}

interface CourseCreee {
  id: string;
  nom: string;
}

async function creerCourse(playwright: PlaywrightLib, jours: number, inscrits: string[] = [], surcharge = {}): Promise<CourseCreee> {
  const nom = nomCourseUnique();
  const c = await avecApi(playwright, (ctx) =>
    creerCourseParApi(ctx, courseDeReference({ nom, date: dateDansJours(jours), dureeBoucleMinutes: 1, nombreMaxBoucles: 5, ...surcharge })),
  );
  const id = c['id'] as string;
  for (const pseudo of inscrits) await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, id));
  return { id, nom };
}

async function connecterSansAttente(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}

async function connecterMaster(page: Page): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
}

// ---------- instantanés fabriqués ----------

interface Reponse {
  status: number;
  corps: unknown;
}

const heure = (decalageMs: number) => new Date(T0 + decalageMs).toISOString();

function instantane(surcharge: Record<string, unknown> = {}): Reponse {
  return {
    status: 200,
    corps: {
      courseId: '00000000-0000-0000-0000-000000000001',
      nom: 'Backyard simulé',
      logoUrl: null,
      statut: 'EN_COURS',
      heureServeur: heure(30000),
      demarreeLe: '2026-10-09T14:03:27Z',
      dureeBoucleMinutes: 1,
      nombreMaxBoucles: 5,
      boucleCourante: 1,
      derniereBoucle: false,
      boucleMaxTerminee: false,
      debutBoucleCourante: '2026-10-09T14:03:27Z',
      finBoucleCourante: '2026-10-09T14:04:27Z',
      tempsAvantFinBoucleMillisecondes: 30000,
      dureeEcouleeMillisecondes: 30000,
      nombreInscrits: 2,
      inscritsEnCourse: [
        { inscriptionId: '00000000-0000-0000-0000-0000000000a1', dossard: 1, pseudo: 'Bruno', statut: 'EN_COURSE' },
        { inscriptionId: '00000000-0000-0000-0000-0000000000a2', dossard: 2, pseudo: 'Chloé', statut: 'EN_COURSE' },
      ],
      ...surcharge,
    },
  };
}

const probleme = (status: number, code: string): Reponse => ({ status, corps: { type: 'about:blank', status, code } });

interface Etat {
  demandes: number;
  servies: number;
}

/**
 * Intercepte GET .../pilotage, installe l'horloge du navigateur, ouvre l'écran puis fige l'horloge.
 * La première réponse est retenue jusqu'à la mise en pause : l'horloge ne bouge ensuite que par `avancer`.
 */
async function ouvrirSimule(page: Page, id: string, repondre: (n: number) => Reponse | Promise<Reponse>): Promise<Etat> {
  const etat: Etat = { demandes: 0, servies: 0 };
  let liberer!: () => void;
  const feu = new Promise<void>((r) => (liberer = r));
  await page.route(`**/api/administration/courses/${id}/pilotage`, async (route) => {
    const n = ++etat.demandes;
    if (n === 1) await feu;
    const rep = await repondre(n);
    try {
      await route.fulfill({
        status: rep.status,
        contentType: rep.status === 200 ? 'application/json' : 'application/problem+json',
        headers: { 'cache-control': 'no-store' },
        body: JSON.stringify(rep.corps),
      });
    } catch {
      /* page fermée entre-temps */
    }
    etat.servies = n;
  });
  await page.clock.install({ time: HORLOGE_DEPART });
  await page.goto(urlPilotage(id));
  await page.clock.pauseAt(new Date(HORLOGE_DEPART.getTime() + 1000));
  liberer();
  return etat;
}

/** Vérifie une assertion en laissant l'horloge figée avancer de 10 ms entre deux essais (le rendu en dépend). */
async function constater(page: Page, assertion: () => Promise<void>): Promise<void> {
  await expect(async () => {
    await page.clock.runFor(10);
    await assertion();
  }).toPass({ timeout: 10000 });
}

async function avancerSans(page: Page, etat: Etat, ms: number): Promise<void> {
  const avant = etat.demandes;
  await page.clock.runFor(ms);
  expect(etat.demandes).toBe(avant);
}

const uuid = () => crypto.randomUUID();

const lire = (page: Page, testid: string) => page.getByTestId(testid);

function secondes(texte: string): number {
  const m = /^(\d{2,}):(\d{2}):(\d{2})$/.exec(texte.trim());
  expect(m, `format HH:MM:SS attendu, obtenu « ${texte} »`).not.toBeNull();
  return Number(m![1]) * 3600 + Number(m![2]) * 60 + Number(m![3]);
}

// ---------- CA13 ----------

test.describe('Pilotage d\'une course', () => {
  test('CA13 - l\'admin ouvre le pilotage d\'une course démarrée : Boucle 1, compte à rebours qui descend, inscrits', async ({ page, playwright }) => {
    const bruno = await creerCoureur(playwright, 'bruno');
    const chloe = await creerCoureur(playwright, 'chloe');
    const course = await creerCourse(playwright, 0, [bruno, chloe]);
    await connecterMaster(page);
    await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, course.id));
    const avantOuverture = Date.now();
    await page.goto(urlPilotage(course.id));
    await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));

    await expect(lire(page, 'pilotage-titre')).toHaveText(course.nom);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 5');
    await expect(lire(page, 'pilotage-libelle-compte-a-rebours')).toHaveText('Prochain départ dans');

    const reste0 = secondes(await lire(page, 'pilotage-compte-a-rebours').innerText());
    expect(reste0).toBeGreaterThanOrEqual(1);
    expect(reste0).toBeLessThanOrEqual(60);
    expect(secondes(await lire(page, 'pilotage-duree-ecoulee').innerText())).toBeLessThanOrEqual(59);

    const depart = (await lire(page, 'pilotage-depart').innerText()).replace(/\s+/g, ' ').trim();
    const m = /^Départ à (\d{2}):(\d{2}):(\d{2})$/.exec(depart);
    expect(m, `format inattendu : « ${depart} »`).not.toBeNull();
    const instantDepart = new Date();
    instantDepart.setHours(Number(m![1]), Number(m![2]), Number(m![3]), 0);
    expect(Math.abs(instantDepart.getTime() - avantOuverture)).toBeLessThan(2 * 60 * 1000);

    await expect(lire(page, 'pilotage-compteur')).toHaveText('2 coureurs en course sur 2 inscrits');
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await expect(lire(page, 'pilotage-inscrit-dossard')).toHaveText(['1', '2']);
    await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText([bruno, chloe]);
    await expect(lire(page, 'pilotage-inscrit-statut')).toHaveText(['En course', 'En course']);

    // le compte à rebours diminue d'au moins 2 s en 3 s (tolérance 1 s), sans rechargement
    await expect
      .poll(async () => secondes(await lire(page, 'pilotage-compte-a-rebours').innerText()), { timeout: 6000 })
      .toBeLessThanOrEqual(reste0 - 2);
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);
  });

  // ---------- CA14 ----------

  test('CA14 - la bascule de boucle apparaît d\'elle-même : Boucle 2, annonce, compte à rebours repart', async ({ page, playwright }) => {
    test.slow(); // vraie bascule de boucle (environ 60 s), délai 90 s
    const bruno = await creerCoureur(playwright, 'bruno');
    const chloe = await creerCoureur(playwright, 'chloe');
    const course = await creerCourse(playwright, 0, [bruno, chloe]);
    await connecterMaster(page);
    await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, course.id));
    await page.goto(urlPilotage(course.id));
    await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 5');

    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 5', { timeout: 75000 });
    await expect(lire(page, 'pilotage-annonce-boucle')).toContainText('Boucle 2 en cours');
    // le compte à rebours repart (tolérance 5 s autour de la minute, plus la latence de rafraîchissement)
    expect(secondes(await lire(page, 'pilotage-compte-a-rebours').innerText())).toBeGreaterThan(50);
    expect(secondes(await lire(page, 'pilotage-duree-ecoulee').innerText())).toBeGreaterThanOrEqual(60);
    await expect(lire(page, 'pilotage-compte-a-rebours')).not.toHaveAttribute('aria-live');
    await expect(lire(page, 'pilotage-duree-ecoulee')).not.toHaveAttribute('aria-live');
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);
  });

  // ---------- CA15 ----------

  test('CA15 - avancement local, rafraîchissement immédiat au zéro, arrondis, réponse ancienne ignorée, rythme de 3 s', async ({ page }) => {
    await connecterMaster(page);
    const id = uuid();
    let ouvrirDeuxieme!: () => void;
    const deuxieme = new Promise<void>((r) => (ouvrirDeuxieme = r));
    const etat = await ouvrirSimule(page, id, async (n) => {
      switch (n) {
        case 1:
          return instantane({ heureServeur: heure(59000), tempsAvantFinBoucleMillisecondes: 1000, dureeEcouleeMillisecondes: 59000 });
        case 2:
          await deuxieme;
          return instantane({ heureServeur: heure(60500), boucleCourante: 2, tempsAvantFinBoucleMillisecondes: 60000, dureeEcouleeMillisecondes: 60000 });
        case 3:
          return instantane({ heureServeur: heure(90000), boucleCourante: 2, tempsAvantFinBoucleMillisecondes: 30000, dureeEcouleeMillisecondes: 90000 });
        case 4:
          return instantane({ heureServeur: heure(95000), boucleCourante: 2, tempsAvantFinBoucleMillisecondes: 59999, dureeEcouleeMillisecondes: 60001 });
        case 5: // plus ancien (heureServeur antérieur) : ignoré
          return instantane({ heureServeur: heure(2000), boucleCourante: 1, tempsAvantFinBoucleMillisecondes: 5000, dureeEcouleeMillisecondes: 1000 });
        default:
          return instantane({ heureServeur: heure(120000), boucleCourante: 3, tempsAvantFinBoucleMillisecondes: 60000, dureeEcouleeMillisecondes: 120000 });
      }
    });

    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:01', COURT);
      await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveText('00:00:59', COURT);
    });
    expect(etat.demandes).toBe(1);

    // 1 s d'horloge : zéro local, durée à 00:01:00 et nouvelle requête partie immédiatement (bien avant 3 s)
    await page.clock.runFor(1500);
    await expect.poll(() => etat.demandes).toBe(2);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:00', COURT);
      await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveText('00:01:00', COURT);
    });

    ouvrirDeuxieme();
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 5', COURT);
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:01:00', COURT);
      await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveText('00:01:00', COURT);
      await expect(lire(page, 'pilotage-annonce-boucle')).toHaveText('Boucle 2 en cours', COURT);
    });

    // exactement une requête toutes les 3 s hors bascule
    await avancerSans(page, etat, 2500);
    await page.clock.runFor(800);
    await expect.poll(() => etat.demandes).toBe(3);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:30', COURT);
      await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveText('00:01:30', COURT);
    });

    // arrondis : 59 999 ms restants -> 00:01:00 (supérieur), 60 001 ms écoulés -> 00:01:00 (inférieur)
    await avancerSans(page, etat, 2500);
    await page.clock.runFor(800);
    await expect.poll(() => etat.demandes).toBe(4);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:01:00', COURT);
      await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveText('00:01:00', COURT);
    });

    // instantané ancien : ignoré
    await avancerSans(page, etat, 2500);
    await page.clock.runFor(800);
    await expect.poll(() => etat.servies).toBe(5);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 5', COURT);
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText(/^\s*00:00:(4|5)\d\s*$/, COURT);
    });

    // le rythme reprend et la Boucle 3 est annoncée
    await avancerSans(page, etat, 2500);
    await page.clock.runFor(800);
    await expect.poll(() => etat.demandes).toBe(6);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 3 sur 5', COURT);
      await expect(lire(page, 'pilotage-annonce-boucle')).toHaveText('Boucle 3 en cours', COURT);
    });
  });

  // ---------- CA16 ----------

  test('CA16 - dernière boucle : libellé et message dédiés', async ({ page }) => {
    await connecterMaster(page);
    await ouvrirSimule(page, uuid(), () =>
      instantane({ boucleCourante: 5, derniereBoucle: true, tempsAvantFinBoucleMillisecondes: 30000, dureeEcouleeMillisecondes: 270000 }),
    );
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 5 sur 5', COURT);
      await expect(lire(page, 'pilotage-libelle-compte-a-rebours')).toHaveText('Fin de la dernière boucle dans', COURT);
      await expect(lire(page, 'pilotage-message-derniere-boucle')).toHaveText('Dernière boucle autorisée.', COURT);
    });
    await expect(lire(page, 'pilotage-message-boucle-max-terminee')).toHaveCount(0);
  });

  test('CA16 - temps de la dernière boucle écoulé : compte à rebours figé à 00:00:00, aucune requête immédiate', async ({ page }) => {
    await connecterMaster(page);
    const etat = await ouvrirSimule(page, uuid(), () =>
      instantane({
        boucleCourante: 5,
        derniereBoucle: true,
        boucleMaxTerminee: true,
        tempsAvantFinBoucleMillisecondes: 0,
        dureeEcouleeMillisecondes: 330000,
      }),
    );
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:00', COURT);
      await expect(lire(page, 'pilotage-message-boucle-max-terminee')).toHaveText(
        'Le temps de la dernière boucle est écoulé. La fin de course sera constatée automatiquement.',
        COURT,
      );
    });
    expect(etat.demandes).toBe(1);
    await avancerSans(page, etat, 2000);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:00', COURT);
      await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveText(/^\s*00:05:3\d\s*$/, COURT);
    });
    await expect(lire(page, 'pilotage-message-derniere-boucle')).toHaveCount(0);
  });

  test('CA16 - course terminée : pastille Terminée, ni compte à rebours ni durée écoulée', async ({ page }) => {
    await connecterMaster(page);
    await ouvrirSimule(page, uuid(), () =>
      instantane({
        statut: 'TERMINEE',
        demarreeLe: null,
        boucleCourante: null,
        debutBoucleCourante: null,
        finBoucleCourante: null,
        tempsAvantFinBoucleMillisecondes: null,
        dureeEcouleeMillisecondes: null,
        inscritsEnCourse: [],
      }),
    );
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée', COURT);
      await expect(lire(page, 'pilotage-message-terminee')).toHaveText('La course est terminée.', COURT);
    });
    await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveCount(0);
    await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveCount(0);
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveCount(0);
  });

  test('CA16 - course en cours sans heure de départ : message dédié et liste des inscrits affichée', async ({ page }) => {
    await connecterMaster(page);
    await ouvrirSimule(page, uuid(), () =>
      instantane({
        demarreeLe: null,
        boucleCourante: null,
        debutBoucleCourante: null,
        finBoucleCourante: null,
        tempsAvantFinBoucleMillisecondes: null,
        dureeEcouleeMillisecondes: null,
      }),
    );
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-message-sans-depart')).toHaveText(
        "Heure de départ inconnue : le pilotage chronométré n'est pas disponible.",
        COURT,
      );
      await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2, COURT);
    });
    await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveCount(0);
  });

  test('CA16 - aucun coureur en course et durée écoulée au-delà de 24 h', async ({ page }) => {
    await connecterMaster(page);
    await ouvrirSimule(page, uuid(), () =>
      instantane({ inscritsEnCourse: [], nombreInscrits: 2, dureeEcouleeMillisecondes: 97509000, tempsAvantFinBoucleMillisecondes: 30000 }),
    );
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-inscrits-vide')).toHaveText('Aucun coureur en course.', COURT);
      await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveText('27:05:09', COURT);
      await expect(lire(page, 'pilotage-compteur')).toHaveText('0 coureur en course sur 2 inscrits', COURT);
    });
    await expect(lire(page, 'pilotage-inscrits')).toHaveCount(0);
  });

  test('CA16 - course non démarrée (409) : message et lien vers la fiche, sans chronomètre ni liste', async ({ page }) => {
    await connecterMaster(page);
    const id = uuid();
    await ouvrirSimule(page, id, () => probleme(409, 'COURSE_NON_DEMARREE'));
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-message-non-demarree')).toHaveText("La course n'est pas démarrée.", COURT);
    });
    await expect(lire(page, 'pilotage-retour')).toHaveAttribute('href', urlFiche(id));
    await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveCount(0);
    await expect(lire(page, 'pilotage-inscrits')).toHaveCount(0);
  });

  // ---------- CA17 ----------

  test('CA17 - panne après un premier succès : avertissement, valeurs conservées, compte à rebours continu, reprise', async ({ page }) => {
    await connecterMaster(page);
    const etat = await ouvrirSimule(page, uuid(), (n) =>
      n === 2 || n === 3
        ? probleme(500, 'ERREUR_INTERNE')
        : instantane({ heureServeur: heure(30000 + n * 3000), tempsAvantFinBoucleMillisecondes: 50000, dureeEcouleeMillisecondes: 10000 }),
    );
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:50', COURT);
    });
    await expect(lire(page, 'pilotage-avertissement-actualisation')).toHaveCount(0);

    await avancerSans(page, etat, 2500);
    await page.clock.runFor(800);
    await expect.poll(() => etat.servies).toBe(2);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-avertissement-actualisation')).toBeVisible(COURT);
    });
    await expect(lire(page, 'pilotage-avertissement-actualisation')).toHaveText(
      "L'actualisation a échoué : les valeurs affichées peuvent être en retard. Nouvelle tentative en cours.",
    );
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 5');
    expect(secondes(await lire(page, 'pilotage-compte-a-rebours').innerText())).toBeLessThanOrEqual(47);
    await expect(lire(page, 'pilotage-erreur')).toHaveCount(0);

    // nouvelle tentative 3 s plus tard : échec encore
    await avancerSans(page, etat, 2500);
    await page.clock.runFor(800);
    await expect.poll(() => etat.servies).toBe(3);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-avertissement-actualisation')).toBeVisible(COURT);
    });

    // succès : l'avertissement disparaît
    await avancerSans(page, etat, 2500);
    await page.clock.runFor(800);
    await expect.poll(() => etat.servies).toBe(4);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-avertissement-actualisation')).toHaveCount(0, COURT);
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:50', COURT);
    });
  });

  test('CA17 - panne dès le premier chargement : message de service indisponible, tentatives toutes les 3 s', async ({ page }) => {
    await connecterMaster(page);
    const etat = await ouvrirSimule(page, uuid(), () => probleme(500, 'ERREUR_INTERNE'));
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-erreur')).toHaveText('Service indisponible, veuillez réessayer plus tard.', COURT);
    });
    await avancerSans(page, etat, 2500);
    await page.clock.runFor(800);
    await expect.poll(() => etat.demandes).toBe(2);
  });

  test('CA17 - 404 : retour à la liste des courses avec « Cette course n\'existe plus. »', async ({ page }) => {
    await connecterMaster(page);
    await ouvrirSimule(page, uuid(), () => probleme(404, 'COURSE_INTROUVABLE'));
    await constater(page, async () => {
      await expect(page).toHaveURL(/\/administration\/courses$/, COURT);
    });
    await constater(page, async () => {
      await expect(page.getByTestId('courses-message-erreur')).toHaveText("Cette course n'existe plus.", COURT);
    });
  });

  test('CA17 - 401 : redirection vers la connexion avec retour au pilotage', async ({ page }) => {
    await connecterMaster(page);
    const id = uuid();
    await ouvrirSimule(page, id, () => probleme(401, 'NON_AUTHENTIFIE'));
    await constater(page, async () => {
      await expect(page).toHaveURL(/\/connexion\?retour=/, COURT);
    });
    expect(new URL(page.url()).search).toBe(`?retour=${encodeURIComponent(urlPilotage(id))}`);
  });

  test('CA17 - 403 : redirection vers l\'accès refusé', async ({ page }) => {
    await connecterMaster(page);
    await ouvrirSimule(page, uuid(), () => probleme(403, 'ACCES_REFUSE'));
    await constater(page, async () => {
      await expect(page).toHaveURL(/\/acces-refuse$/, COURT);
    });
  });

  test('CA17 - le rafraîchissement s\'arrête en quittant l\'écran', async ({ page }) => {
    await connecterMaster(page);
    const etat = await ouvrirSimule(page, uuid(), () => instantane());
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-boucle-courante')).toBeVisible(COURT);
    });
    expect(etat.demandes).toBe(1);
    await lire(page, 'pilotage-retour').click();
    await expect(page).toHaveURL(/\/administration\/courses\/[0-9a-f-]{36}$/);
    const avant = etat.demandes;
    await page.clock.runFor(10000);
    expect(etat.demandes).toBe(avant);
  });

  // ---------- CA18 ----------

  test('CA18 - la fiche propose le lien de pilotage après le démarrage, sans rechargement, et mène à l\'écran', async ({ page, playwright }) => {
    const bruno = await creerCoureur(playwright, 'bruno');
    const course = await creerCourse(playwright, 0, [bruno]);
    await connecterMaster(page);
    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
    await expect(page.getByTestId('fiche-lien-pilotage')).toHaveCount(0);

    await page.getByTestId('fiche-bouton-demarrer').click();
    const reponse = page.waitForResponse((r) => r.request().method() === 'POST' && /\/demarrage$/.test(new URL(r.url()).pathname));
    await page.getByTestId('fiche-bouton-confirmer-demarrage').click();
    expect((await reponse).status()).toBe(200);
    await expect(page.getByTestId('fiche-message-demarrage')).toBeVisible();
    await expect(page.getByTestId('fiche-lien-pilotage')).toHaveText('Ouvrir le pilotage');

    await page.getByTestId('fiche-lien-pilotage').click();
    await expect(page).toHaveURL(new RegExp(`${urlPilotage(course.id)}$`));
    await expect(lire(page, 'pilotage-titre')).toHaveText(course.nom);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  });

  test('CA18 - la fiche et le pilotage d\'une course terminée', async ({ page, playwright }) => {
    const bruno = await creerCoureur(playwright, 'bruno');
    const course = await creerCourse(playwright, 5, [bruno]);
    placerStatutCourseEnBase(course.id, 'TERMINEE');
    await connecterMaster(page);
    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-lien-pilotage')).toBeVisible();
    await page.getByTestId('fiche-lien-pilotage').click();
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée');
    await expect(lire(page, 'pilotage-message-terminee')).toHaveText('La course est terminée.');
  });

  test('CA18 - le pilotage d\'une course en préparation affiche « La course n\'est pas démarrée. »', async ({ page, playwright }) => {
    const course = await creerCourse(playwright, 5);
    await connecterMaster(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-message-non-demarree')).toHaveText("La course n'est pas démarrée.");
    await expect(lire(page, 'pilotage-retour')).toHaveAttribute('href', urlFiche(course.id));
  });

  test('CA18 - un coureur, un bénévole ou un anonyme qui tape l\'URL est refusé ou renvoyé à la connexion', async ({ page, playwright, browser }) => {
    const course = await creerCourse(playwright, 5);
    const coureur = await creerCoureur(playwright, 'coureur');
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));

    // anonyme
    await page.goto(urlPilotage(course.id));
    await expect(page).toHaveURL(/\/connexion\?retour=/);
    expect(decodeURIComponent(page.url())).toContain(`retour=${urlPilotage(course.id)}`);

    // coureur
    await connecterSansAttente(page, coureur, MOT_DE_PASSE);
    await page.goto(urlPilotage(course.id));
    await expect(page).toHaveURL(/\/acces-refuse$/);

    // bénévole (contexte neuf)
    const ctxBenevole = await browser.newContext();
    const pageBenevole = await ctxBenevole.newPage();
    try {
      await connecterSansAttente(pageBenevole, benevole, MOT_DE_PASSE_BENEVOLE_CREE);
      await pageBenevole.goto(urlPilotage(course.id));
      await expect(pageBenevole).toHaveURL(/\/acces-refuse$/);
    } finally {
      await ctxBenevole.close();
    }
  });

  test('CA18 - un admin simple accède au pilotage comme l\'admin master', async ({ page, playwright }) => {
    const nadia = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    const bruno = await creerCoureur(playwright, 'bruno');
    const course = await creerCourse(playwright, 0, [bruno]);
    await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, course.id, { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER }));
    await connecterSansAttente(page, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-titre')).toHaveText(course.nom);
    await expect(lire(page, 'pilotage-boucle-courante')).toContainText('Boucle');
    await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText([bruno]);
  });

  // ---------- CA19 ----------

  async function verifierMiseEnPage320(page: Page, agrandi: boolean): Promise<void> {
    await page.setViewportSize({ width: 320, height: 640 });
    await connecterMaster(page);
    const pseudoLong = 'Abcdefghij'.repeat(3);
    let ouvrirSecond!: () => void;
    const second = new Promise<void>((r) => (ouvrirSecond = r));
    const etat = await ouvrirSimule(page, uuid(), async (n) => {
      const base = {
        nombreInscrits: 3,
        inscritsEnCourse: [
          { inscriptionId: '00000000-0000-0000-0000-0000000000a1', dossard: 1, pseudo: pseudoLong, statut: 'EN_COURSE' },
          { inscriptionId: '00000000-0000-0000-0000-0000000000a2', dossard: 2, pseudo: 'Chloé', statut: 'EN_COURSE' },
          { inscriptionId: '00000000-0000-0000-0000-0000000000a3', dossard: 3, pseudo: 'Tom', statut: 'EN_COURSE' },
        ],
      };
      if (n === 1) return instantane({ ...base, heureServeur: heure(59000), tempsAvantFinBoucleMillisecondes: 1000 + 58000, dureeEcouleeMillisecondes: 59000 });
      await second;
      return instantane({ ...base, heureServeur: heure(62000), boucleCourante: 2, tempsAvantFinBoucleMillisecondes: 60000, dureeEcouleeMillisecondes: 60000 });
    });
    if (agrandi) await page.addStyleTag({ content: AGRANDIR });
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:59', COURT);
      await expect(lire(page, 'pilotage-duree-ecoulee')).toHaveText('00:00:59', COURT);
      await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3, COURT);
    });

    const mesures = async () =>
      page.evaluate(() => {
        const q = (id: string) => {
          const r = document.querySelector(`[data-testid="${id}"]`)!.getBoundingClientRect();
          return { left: r.left, right: r.right, width: r.width, height: r.height, top: r.top + window.scrollY };
        };
        const carte = document.querySelector('main section')!.getBoundingClientRect();
        const el = document.documentElement;
        return {
          debordeX: el.scrollWidth > el.clientWidth,
          carte: { gauche: carte.left, droite: carte.right },
          compte: q('pilotage-compte-a-rebours'),
          tableau: q('pilotage-inscrits'),
          pseudo: q('pilotage-inscrit-pseudo'),
          retour: q('pilotage-retour'),
        };
      });
    const a = await mesures();
    expect(a.debordeX, 'défilement horizontal').toBe(false);
    for (const [nom, r] of Object.entries({ compte: a.compte, tableau: a.tableau, pseudo: a.pseudo, retour: a.retour })) {
      expect(r.left, `${nom} déborde à gauche de la carte`).toBeGreaterThanOrEqual(a.carte.gauche - 1);
      expect(r.right, `${nom} déborde à droite de la carte`).toBeLessThanOrEqual(a.carte.droite + 1);
    }
    // Spec CA19 : « cibles de 44 px ». La charte (--cible-min) ne vise que boutons et champs ; le lien de retour est un
    // lien textuel `.lien` partagé par tous les écrans (24 px, minimum WCAG 2.5.8). Ambiguïté signalée, seuil WCAG vérifié.
    expect(a.retour.height, 'cible du lien de retour').toBeGreaterThanOrEqual(24);

    // clavier : Tab atteint le lien de retour avec un contour visible
    const retour = lire(page, 'pilotage-retour');
    for (let i = 0; i < 40 && !(await retour.evaluate((e) => e === document.activeElement)); i++) await page.keyboard.press('Tab');
    await expect(retour).toBeFocused();
    const contour = await retour.evaluate((e) => {
      const s = getComputedStyle(e);
      return { style: s.outlineStyle, largeur: parseFloat(s.outlineWidth), ombre: s.boxShadow };
    });
    expect(
      (contour.style !== 'none' && contour.largeur > 0) || contour.ombre !== 'none',
      `contour de focus invisible : ${JSON.stringify(contour)}`,
    ).toBe(true);

    // la bascule 00:00:59 -> 00:01:00 ne déplace rien et le rafraîchissement ne retire pas le focus
    ouvrirSecond();
    await page.clock.runFor(3200);
    await expect.poll(() => etat.servies).toBeGreaterThanOrEqual(2);
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:01:00', COURT);
    });
    const b = await mesures();
    expect(b.compte.width).toBeCloseTo(a.compte.width, 0);
    expect(b.compte.left).toBeCloseTo(a.compte.left, 0);
    expect(b.tableau.top).toBeCloseTo(a.tableau.top, 0);
    expect(b.debordeX).toBe(false);
    await expect(retour).toBeFocused();
  }

  test('CA19 - à 320 px : rien ne déborde, pseudo long coupé, clavier, focus conservé, largeur stable', async ({ page }) => {
    await verifierMiseEnPage320(page, false);
  });

  test('CA19 - à 320 px avec un texte à 200 % : rien ne déborde de la carte', async ({ page }) => {
    await verifierMiseEnPage320(page, true);
  });

  test('CA19 - contrastes AA du chronométrage, des inscrits et du lien de retour', async ({ page }) => {
    await connecterMaster(page);
    await ouvrirSimule(page, uuid(), () => instantane());
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2, COURT);
    });
    const ratios = await page.evaluate((ids) => {
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
      return ids.map((id) => {
        const e = document.querySelector(`[data-testid="${id}"]`)!;
        const l1 = lum(parse(getComputedStyle(e).color));
        const l2 = lum(fond(e));
        return { id, ratio: (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05) };
      });
    }, ['pilotage-boucle-courante', 'pilotage-compte-a-rebours', 'pilotage-duree-ecoulee', 'pilotage-depart', 'pilotage-compteur', 'pilotage-inscrit-pseudo', 'pilotage-inscrit-statut', 'pilotage-retour']);
    for (const r of ratios) expect(r.ratio, `contraste de ${r.id}`).toBeGreaterThanOrEqual(4.5);
  });

  test('CA19 - prefers-reduced-motion : aucune animation ni transition sur l\'écran', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await connecterMaster(page);
    await ouvrirSimule(page, uuid(), () => instantane());
    await constater(page, async () => {
      await expect(lire(page, 'pilotage-boucle-courante')).toBeVisible(COURT);
    });
    const fautifs = await page.evaluate(() =>
      Array.from(document.querySelectorAll('main, main *'))
        .map((e) => {
          const s = getComputedStyle(e);
          const dur = (v: string) => Math.max(0, ...v.split(',').map((x) => parseFloat(x) || 0));
          const anime = s.animationName !== 'none' && dur(s.animationDuration) > 0;
          return anime || dur(s.transitionDuration) > 0 ? e.tagName.toLowerCase() + '.' + e.className : null;
        })
        .filter((x) => x !== null),
    );
    expect(fautifs).toEqual([]);
  });
});

// Évite l'avertissement d'import inutilisé sur les helpers réservés aux évolutions.
void pseudoUnique;
