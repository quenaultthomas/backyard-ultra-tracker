import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { creerCompteParApi, MOT_DE_PASSE, pseudoUnique, ouvrirConnexion, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  dateAffichee,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { connecterCoureur, ligneCoureur, placerStatutCourseEnBase, sinscrireParApi } from './aide-coureur';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const LOGO = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));
const RETOUR_COUREUR = /\/connexion\?retour=%2Fcoureur$/;
const estInscription = (r: Request) => r.method() === 'POST' && /^\/api\/coureur\/courses\/[^/]+\/inscriptions$/.test(new URL(r.url()).pathname);
const estListe = (r: Request) => r.method() === 'GET' && new URL(r.url()).pathname === '/api/coureur/courses';

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

async function creerCourse(playwright: PlaywrightLib, { logo = true, nom = nomCourseUnique() } = {}): Promise<{ id: string; nom: string; date: string }> {
  const donnees = courseDeReference({ nom });
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, donnees));
  const id = c['id'] as string;
  if (logo) {
    await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, id, { nom: 'logo.png', type: 'image/png', contenu: LOGO }));
  }
  return { id, nom, date: donnees.date };
}

async function creerCoureur(playwright: PlaywrightLib, prefixe = 'Alice'): Promise<string> {
  const pseudo = pseudoUnique(prefixe);
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  return pseudo;
}

async function supprimerParEcran(page: Page, nom: string): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
  await page.goto('/administration/courses');
  const l = page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });
  await l.getByTestId('course-bouton-supprimer').click();
  await l.getByTestId('course-bouton-confirmer-suppression').click();
  await expect(page.getByTestId('course-message-suppression')).toHaveText(`La course ${nom} a été supprimée.`);
}

test.describe('Inscription à une course', () => {
  test('CA11 - la coureuse se connecte, voit la course ouverte et s\'inscrit : dossard 1, conservé après rechargement', async ({ page, playwright }) => {
    const alice = await creerCoureur(playwright);
    const course = await creerCourse(playwright);

    await ouvrirConnexion(page);
    await saisir(page, alice, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('lien-espace-coureur')).toBeVisible();
    await page.getByTestId('lien-espace-coureur').click();
    await expect(page).toHaveURL(/\/coureur$/);
    await expect(page.getByTestId('coureur-titre')).toHaveText('Courses ouvertes');

    const l = ligneCoureur(page, course.nom);
    await expect(l).toHaveCount(1);
    await expect(l.getByTestId('coureur-course-logo')).toBeVisible();
    await expect.poll(() => l.getByTestId('coureur-course-logo').evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
    await expect(l.getByTestId('coureur-course-date')).toHaveText(dateAffichee(course.date));
    await expect(l.getByTestId('coureur-course-parametres')).toHaveText('6706 m · 60 min · 120 m D+');
    await expect(l.getByTestId('coureur-course-limites')).toHaveText('24 boucles max · 50 participants max');
    await expect(l.getByTestId('coureur-bouton-sinscrire')).toHaveText("S'inscrire");
    await expect(l.getByTestId('coureur-dossard')).toHaveCount(0);
    await expect(l.getByTestId('coureur-statut-inscription')).toHaveCount(0);

    let navigations = 0;
    page.on('framenavigated', (f) => {
      if (f === page.mainFrame()) navigations++;
    });
    const requete = page.waitForRequest(estInscription);
    let corpsReponse: Promise<string> | undefined;
    let statutReponse = 0;
    page.on('response', (r) => {
      if (estInscription(r.request())) {
        statutReponse = r.status();
        corpsReponse = r.text();
      }
    });
    await l.getByTestId('coureur-bouton-sinscrire').click();
    expect(new URL((await requete).url()).pathname).toBe(`/api/coureur/courses/${course.id}/inscriptions`);
    await expect.poll(() => statutReponse).toBe(201);
    expect(JSON.parse(await corpsReponse!)).not.toHaveProperty('jetonQr');

    await expect(l.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
    await expect(l.getByTestId('coureur-statut-inscription')).toHaveText('Inscrit');
    await expect(l.getByTestId('coureur-bouton-sinscrire')).toHaveCount(0);
    await expect(page.getByTestId('coureur-message-inscription')).toHaveText(`Vous êtes inscrit à ${course.nom} avec le dossard 1.`);
    expect(navigations).toBe(0);

    await page.reload();
    await expect(page.getByTestId('coureur-liste-courses')).toBeVisible();
    await expect(l.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
    await expect(l.getByTestId('coureur-bouton-sinscrire')).toHaveCount(0);

    // ni QR ni jeton dans la page
    await expect(page.locator('canvas, svg[data-testid*="qr"], img[alt*="QR" i]')).toHaveCount(0);
    expect(await page.locator('body').innerText()).not.toMatch(/[A-Za-z0-9_-]{43}/);
  });

  test('CA12 - deux coureurs obtiennent des dossards distincts, numérotés par course', async ({ browser, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const bruno = await creerCoureur(playwright, 'Bruno');
    const course = await creerCourse(playwright);
    const autre = await creerCourse(playwright, { logo: false });

    const ctxAlice = await browser.newContext();
    const ctxBruno = await browser.newContext();
    const pageAlice = await ctxAlice.newPage();
    const pageBruno = await ctxBruno.newPage();
    try {
      await connecterCoureur(pageAlice, alice);
      await connecterCoureur(pageBruno, bruno);
      const la = ligneCoureur(pageAlice, course.nom);
      const lb = ligneCoureur(pageBruno, course.nom);
      await expect(la.getByTestId('coureur-bouton-sinscrire')).toBeVisible();
      await expect(lb.getByTestId('coureur-bouton-sinscrire')).toBeVisible();

      await la.getByTestId('coureur-bouton-sinscrire').click();
      await expect(la.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
      await lb.getByTestId('coureur-bouton-sinscrire').click();
      await expect(lb.getByTestId('coureur-dossard')).toHaveText('Dossard 2');

      await pageAlice.reload();
      await expect(la.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
      await pageBruno.reload();
      await expect(lb.getByTestId('coureur-dossard')).toHaveText('Dossard 2');

      const lbAutre = ligneCoureur(pageBruno, autre.nom);
      await lbAutre.getByTestId('coureur-bouton-sinscrire').click();
      await expect(lbAutre.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
      await expect(lb.getByTestId('coureur-dossard')).toHaveText('Dossard 2');

      // la liste de Bruno ne contient aucune inscription d'Alice (mêmes identifiants d'inscription exclus)
      const inscriptionsAlice = await pageAlice.evaluate(async () => {
        const r = await fetch('/api/coureur/courses');
        return ((await r.json()) as Array<{ monInscription: { id: string } | null }>).flatMap((c) => (c.monInscription ? [c.monInscription.id] : []));
      });
      const listeBruno = await pageBruno.evaluate(async () => {
        const r = await fetch('/api/coureur/courses');
        return (await r.json()) as Array<{ id: string; monInscription: { id: string; dossard: number } | null }>;
      });
      expect(inscriptionsAlice).toHaveLength(1);
      const idsBruno = listeBruno.flatMap((c) => (c.monInscription ? [c.monInscription.id] : []));
      for (const id of inscriptionsAlice) expect(idsBruno).not.toContain(id);
      expect(listeBruno.find((c) => c.id === course.id)?.monInscription?.dossard).toBe(2);
      expect(listeBruno.find((c) => c.id === autre.id)?.monInscription?.dossard).toBe(1);
    } finally {
      await ctxAlice.close();
      await ctxBruno.close();
    }
  });

  test.describe('CA13 - accès et lien d\'en-tête', () => {
    test('CA13 - l\'anonyme est redirigé vers la connexion puis revient sur /coureur ; aucun lien d\'en-tête', async ({ page, playwright }) => {
      const alice = await creerCoureur(playwright);
      await creerCourse(playwright, { logo: false });

      await page.goto('/coureur');
      await expect(page).toHaveURL(RETOUR_COUREUR);
      await expect(page.getByTestId('titre-connexion')).toBeVisible();
      await expect(page.getByTestId('lien-espace-coureur')).toHaveCount(0);

      await saisir(page, alice, MOT_DE_PASSE);
      await page.getByTestId('bouton-connexion').click();
      await expect(page).toHaveURL(/\/coureur$/);
      await expect(page.getByTestId('coureur-titre')).toBeVisible();
      await expect(page.getByTestId('lien-espace-coureur')).toBeVisible();
      await expect(page.getByTestId('lien-espace-coureur')).toHaveText('Espace coureur');
      await expect(page.getByTestId('lien-espace-benevole')).toHaveCount(0);
      await expect(page.getByTestId('lien-administration')).toHaveCount(0);
    });

    for (const role of ['bénévole', 'admin', 'admin master']) {
      test(`CA13 - ${role} : /coureur mène à l'accès refusé, sans lien « Espace coureur »`, async ({ page, playwright }) => {
        if (role === 'admin master') {
          await connecterAdminMaster(page);
          await expect(page).toHaveURL(/\/administration$/);
        } else {
          const pseudo = role === 'admin' ? pseudoAdminUnique() : pseudoBenevoleUnique();
          if (role === 'admin') await avecApi(playwright, (ctx) => creerAdminParApi(ctx, pseudo));
          else await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, pseudo));
          await ouvrirConnexion(page);
          await saisir(page, pseudo, role === 'admin' ? MOT_DE_PASSE_ADMIN_CREE : MOT_DE_PASSE_BENEVOLE_CREE);
          await page.getByTestId('bouton-connexion').click();
          await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
        }
        await expect(page.getByTestId('lien-espace-coureur')).toHaveCount(0);
        await page.goto('/coureur');
        await expect(page).toHaveURL(/\/acces-refuse$/);
        await expect(page.getByTestId('lien-espace-coureur')).toHaveCount(0);
      });
    }
  });

  test.describe('CA14 - retours du serveur à l\'inscription', () => {
    async function preparer(page: Page, playwright: PlaywrightLib, reponse: { status: number; code?: string }): Promise<{ course: { id: string; nom: string }; l: Locator }> {
      const alice = await creerCoureur(playwright);
      const course = await creerCourse(playwright, { logo: false });
      await connecterCoureur(page, alice);
      const l = ligneCoureur(page, course.nom);
      await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeVisible();
      await page.route(
        (url) => url.pathname === `/api/coureur/courses/${course.id}/inscriptions`,
        async (route) => {
          if (route.request().method() !== 'POST') return route.fallback();
          await route.fulfill({
            status: reponse.status,
            contentType: 'application/problem+json',
            body: JSON.stringify({ status: reponse.status, code: reponse.code }),
          });
        },
      );
      return { course, l };
    }

    test('CA14 - 409 INSCRIPTION_DEJA_EXISTANTE : message dans la ligne et liste relue (le dossard apparaît)', async ({ page, playwright }) => {
      const { course, l } = await preparer(page, playwright, { status: 409, code: 'INSCRIPTION_DEJA_EXISTANTE' });
      // l'inscription existe réellement côté serveur (autre onglet) : la relecture la montre
      const pseudo = await page.getByTestId('entete-pseudo').innerText();
      await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, course.id));
      const relecture = page.waitForRequest(estListe);
      await l.getByTestId('coureur-bouton-sinscrire').click();
      await expect(page.getByTestId('coureur-erreur')).toHaveText('Vous êtes déjà inscrit à cette course.');
      await expect(l.getByTestId('coureur-erreur')).toHaveCount(1);
      await relecture;
      await expect(l.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
    });

    test('CA14 - 404 : message « Cette course n\'existe plus. » et ligne disparue après relecture', async ({ page, playwright }) => {
      const { course, l } = await preparer(page, playwright, { status: 404, code: 'COURSE_INTROUVABLE' });
      // la course existe encore : le message est dans la ligne
      const relecture = page.waitForRequest(estListe);
      await l.getByTestId('coureur-bouton-sinscrire').click();
      await expect(page.getByTestId('coureur-erreur')).toHaveText("Cette course n'existe plus.");
      await expect(l.getByTestId('coureur-erreur')).toHaveCount(1);
      await relecture;
      await expect(l).toHaveCount(1);

      // la course est maintenant réellement supprimée : la ligne disparaît, le message reste affiché (hors de la ligne)
      const { supprimerCourseParApi } = await import('./aide-admin');
      expect(await avecApi(playwright, (ctx) => supprimerCourseParApi(ctx, course.id))).toBe(204);
      await l.getByTestId('coureur-bouton-sinscrire').click();
      await expect(l).toHaveCount(0);
      await expect(page.getByTestId('coureur-erreur')).toHaveCount(1);
      await expect(page.getByTestId('coureur-erreur')).toHaveText("Cette course n'existe plus.");
    });

    test('CA14 - 403 CSRF_INVALIDE : message de page expirée, bouton conservé, nouveau jeton demandé', async ({ page, playwright }) => {
      const { l } = await preparer(page, playwright, { status: 403, code: 'CSRF_INVALIDE' });
      const csrf = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/csrf');
      await l.getByTestId('coureur-bouton-sinscrire').click();
      await expect(page.getByTestId('coureur-erreur')).toHaveText('La page a expiré, veuillez réessayer.');
      await csrf;
      await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeVisible();
      await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();
    });

    test('CA14 - 500 : service indisponible, bouton conservé', async ({ page, playwright }) => {
      const { l } = await preparer(page, playwright, { status: 500, code: 'ERREUR_INTERNE' });
      await l.getByTestId('coureur-bouton-sinscrire').click();
      await expect(page.getByTestId('coureur-erreur')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
      await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeVisible();
    });

    test('CA14 - 401 : redirection vers la connexion avec retour', async ({ page, playwright }) => {
      const { l } = await preparer(page, playwright, { status: 401, code: 'NON_AUTHENTIFIE' });
      await l.getByTestId('coureur-bouton-sinscrire').click();
      await expect(page).toHaveURL(RETOUR_COUREUR);
    });

    test('CA14 - 403 ACCES_REFUSE : redirection vers accès refusé', async ({ page, playwright }) => {
      const { l } = await preparer(page, playwright, { status: 403, code: 'ACCES_REFUSE' });
      await l.getByTestId('coureur-bouton-sinscrire').click();
      await expect(page).toHaveURL(/\/acces-refuse$/);
    });

    test('CA14 - un double clic rapide n\'envoie qu\'un seul POST', async ({ page, playwright }) => {
      const alice = await creerCoureur(playwright);
      const course = await creerCourse(playwright, { logo: false });
      await connecterCoureur(page, alice);
      const l = ligneCoureur(page, course.nom);
      await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeVisible();
      let posts = 0;
      page.on('request', (r) => {
        if (estInscription(r)) posts++;
      });
      await l.getByTestId('coureur-bouton-sinscrire').dblclick();
      await expect(l.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
      await expect(page.getByTestId('coureur-erreur')).toHaveCount(0);
      expect(posts).toBe(1);
    });

    test('CA14 - échec de chargement de la liste (500) : message dédié', async ({ page, playwright }) => {
      const alice = await creerCoureur(playwright);
      await connecterCoureur(page, alice);
      await page.route(
        (url) => url.pathname === '/api/coureur/courses',
        (route) => route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'ERREUR_INTERNE' }) }),
      );
      await page.reload();
      await expect(page.getByTestId('coureur-erreur-chargement')).toHaveText('Impossible de charger les courses. Réessayez plus tard.');
      await expect(page.getByTestId('coureur-liste-courses')).toHaveCount(0);
    });

    test('CA14 - sans course ouverte (liste vide) : message d\'absence de course', async ({ page, playwright }) => {
      const alice = await creerCoureur(playwright);
      await connecterCoureur(page, alice);
      await page.route(
        (url) => url.pathname === '/api/coureur/courses',
        (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
      );
      await page.reload();
      await expect(page.getByTestId('coureur-vide')).toHaveText('Aucune course ouverte aux inscriptions pour le moment.');
      await expect(page.getByTestId('coureur-ligne-course')).toHaveCount(0);
    });
  });

  test.describe('CA15 - courses supprimées ou démarrées', () => {
    test('CA15 - la course supprimée par l\'admin master disparaît de /coureur, l\'autre inscription est inchangée', async ({ page, browser, playwright }) => {
      const alice = await creerCoureur(playwright);
      const supprimee = await creerCourse(playwright, { logo: false });
      const conservee = await creerCourse(playwright, { logo: false });
      await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, supprimee.id));
      await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, conservee.id));

      const ctxAlice = await browser.newContext();
      const pageAlice = await ctxAlice.newPage();
      try {
        await connecterCoureur(pageAlice, alice);
        await expect(ligneCoureur(pageAlice, supprimee.nom).getByTestId('coureur-dossard')).toHaveText('Dossard 1');
        await expect(ligneCoureur(pageAlice, conservee.nom).getByTestId('coureur-dossard')).toHaveText('Dossard 1');

        await supprimerParEcran(page, supprimee.nom);

        await pageAlice.goto('/coureur');
        await expect(pageAlice.getByTestId('coureur-liste-courses')).toBeVisible();
        await expect(ligneCoureur(pageAlice, supprimee.nom)).toHaveCount(0);
        await expect(ligneCoureur(pageAlice, conservee.nom).getByTestId('coureur-dossard')).toHaveText('Dossard 1');
        await expect(ligneCoureur(pageAlice, conservee.nom).getByTestId('coureur-statut-inscription')).toHaveText('Inscrit');
      } finally {
        await ctxAlice.close();
      }
    });

    test('CA15 - une course dont le statut est EN_COURS n\'est pas affichée', async ({ page, playwright }) => {
      const alice = await creerCoureur(playwright);
      const demarree = await creerCourse(playwright, { logo: false });
      const ouverte = await creerCourse(playwright, { logo: false });
      await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, ouverte.id));
      await connecterCoureur(page, alice);
      await expect(ligneCoureur(page, demarree.nom)).toHaveCount(1);

      // aucun endpoint de démarrage avant 4.1 : le statut est placé en base, la liste est celle du serveur
      placerStatutCourseEnBase(demarree.id, 'EN_COURS');
      try {
        await page.reload();
        await expect(page.getByTestId('coureur-liste-courses')).toBeVisible();
        await expect(ligneCoureur(page, demarree.nom)).toHaveCount(0);
        await expect(ligneCoureur(page, ouverte.nom).getByTestId('coureur-dossard')).toHaveText('Dossard 1');
      } finally {
        placerStatutCourseEnBase(demarree.id, 'EN_PREPARATION');
      }
      await page.reload();
      await expect(ligneCoureur(page, demarree.nom)).toHaveCount(1);
    });
  });
});
