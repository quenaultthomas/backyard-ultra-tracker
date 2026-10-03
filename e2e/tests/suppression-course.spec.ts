import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { creerCompteParApi, MOT_DE_PASSE, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_BENEVOLE_CREE,
  affecterBenevolesParApi,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
  supprimerCourseParApi,
  MOT_DE_PASSE_ADMIN_CREE,
} from './aide-admin';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const LOGO = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));
const MOTIF_LISTE = '**/api/administration/courses';
const RETOUR_COURSES = /\/connexion\?retour=%2Fadministration%2Fcourses$/;
const estSuppression = (r: Request) => r.method() === 'DELETE' && /\/api\/administration\/courses\/[^/]+$/.test(new URL(r.url()).pathname);
const estListe = (r: Request) => r.method() === 'GET' && new URL(r.url()).pathname === '/api/administration/courses';

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

async function creerCourse(playwright: PlaywrightLib, { logo = true, nom = nomCourseUnique() } = {}): Promise<{ id: string; nom: string }> {
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom })));
  const id = c['id'] as string;
  if (logo) {
    await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, id, { nom: 'logo.png', type: 'image/png', contenu: LOGO }));
  }
  return { id, nom };
}

async function connecter(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}

const ligne = (page: Page, nom: string): Locator =>
  page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });

async function ouvrirListe(page: Page): Promise<void> {
  await page.goto('/administration/courses');
  await expect(page.getByTestId('liste-courses')).toBeVisible();
}

async function ouvrirConfirmation(page: Page, nom: string): Promise<Locator> {
  const l = ligne(page, nom);
  await l.getByTestId('course-bouton-supprimer').click();
  await expect(l.getByTestId('course-confirmation-suppression')).toBeVisible();
  return l;
}

test.describe('Suppression d\'une course', () => {
  test('CA11 - l\'admin master annule puis confirme la suppression d\'une course avec logo et bénévole', async ({ page, playwright }) => {
    const leo = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, leo));
    const course = await creerCourse(playwright);
    const voisine = await creerCourse(playwright);
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [leo]));

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);
    const l = ligne(page, course.nom);
    const textVoisine = await ligne(page, voisine.nom).innerText();

    const suppressions: string[] = [];
    page.on('request', (r) => {
      if (estSuppression(r)) suppressions.push(r.url());
    });

    await l.getByTestId('course-bouton-supprimer').click();
    await expect(l.getByTestId('course-confirmation-suppression')).toContainText(
      `Supprimer définitivement la course ${course.nom} ? Son logo et les affectations de ses bénévoles seront supprimés. Cette action est irréversible.`,
    );
    expect(suppressions).toHaveLength(0);

    await l.getByTestId('course-bouton-annuler-suppression').click();
    await expect(l.getByTestId('course-confirmation-suppression')).toHaveCount(0);
    await expect(l).toBeVisible();
    expect(suppressions).toHaveLength(0);

    await l.getByTestId('course-bouton-supprimer').click();
    const requete = page.waitForRequest(estSuppression);
    const reponse = page.waitForResponse((r) => estSuppression(r.request()));
    await l.getByTestId('course-bouton-confirmer-suppression').click();
    expect(new URL((await requete).url()).pathname).toBe(`/api/administration/courses/${course.id}`);
    expect((await reponse).status()).toBe(204);

    await expect(ligne(page, course.nom)).toHaveCount(0);
    await expect(page.getByTestId('course-message-suppression')).toHaveText(`La course ${course.nom} a été supprimée.`);
    expect(await ligne(page, voisine.nom).innerText()).toBe(textVoisine);

    await page.reload();
    await expect(page.getByTestId('liste-courses')).toBeVisible();
    await expect(ligne(page, course.nom)).toHaveCount(0);
    await expect(ligne(page, voisine.nom)).toHaveCount(1);

    await page.goto(`/administration/courses/${course.id}`);
    await expect(page.getByTestId('fiche-erreur-introuvable')).toBeVisible();
    const logo = await page.request.get(`/api/courses/${course.id}/logo`);
    expect(logo.status()).toBe(404);
    const logoVoisine = await page.request.get(`/api/courses/${voisine.id}/logo`);
    expect(logoVoisine.status()).toBe(200);
  });

  test('CA12 - la course supprimée disparaît de l\'accueil du bénévole sans reconnexion', async ({ page, browser, playwright }) => {
    const leo = pseudoBenevoleUnique();
    const marc = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, leo));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, marc));
    const a = await creerCourse(playwright, { nom: nomCourseUnique('Course A') });
    const b = await creerCourse(playwright, { nom: nomCourseUnique('Course B') });
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, a.id, [leo, marc]));
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, b.id, [leo]));

    const ctxBenevole = await browser.newContext();
    const pageLeo = await ctxBenevole.newPage();
    const ctxMarc = await browser.newContext();
    const pageMarc = await ctxMarc.newPage();
    try {
      await connecter(pageLeo, leo, MOT_DE_PASSE_BENEVOLE_CREE);
      await expect(pageLeo).toHaveURL(/\/benevole$/);
      await expect(pageLeo.getByTestId('benevole-ligne-course')).toHaveCount(2);
      await connecter(pageMarc, marc, MOT_DE_PASSE_BENEVOLE_CREE);
      await expect(pageMarc.getByTestId('benevole-ligne-course')).toHaveCount(1);

      await connecterAdminMaster(page);
      await expect(page).toHaveURL(/\/administration$/);
      await ouvrirListe(page);
      await (await ouvrirConfirmation(page, a.nom)).getByTestId('course-bouton-confirmer-suppression').click();
      await expect(page.getByTestId('course-message-suppression')).toHaveText(`La course ${a.nom} a été supprimée.`);

      await pageLeo.reload();
      await expect(pageLeo.getByTestId('benevole-ligne-course')).toHaveCount(1);
      await expect(pageLeo.getByTestId('benevole-course-nom')).toHaveText(b.nom);

      await (await ouvrirConfirmation(page, b.nom)).getByTestId('course-bouton-confirmer-suppression').click();
      await expect(page.getByTestId('course-message-suppression')).toHaveText(`La course ${b.nom} a été supprimée.`);

      await pageLeo.reload();
      await expect(pageLeo.getByTestId('accueil-benevole-vide')).toHaveText('Aucune course à scanner pour le moment.');
      await pageMarc.reload();
      await expect(pageMarc.getByTestId('accueil-benevole-vide')).toHaveText('Aucune course à scanner pour le moment.');
    } finally {
      await ctxBenevole.close();
      await ctxMarc.close();
    }
  });

  test('CA13 - le bouton de suppression est réservé à l\'admin master et aux courses en préparation', async ({ page, browser, playwright }) => {
    const course = await creerCourse(playwright);
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));

    // ADMIN : aucun bouton, DELETE refusé
    const ctxAdmin = await browser.newContext();
    const pageAdmin = await ctxAdmin.newPage();
    try {
      await connecter(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
      await ouvrirListe(pageAdmin);
      const l = ligne(pageAdmin, course.nom);
      await expect(l).toHaveCount(1);
      await expect(l.getByTestId('course-lien-fiche')).toBeVisible();
      await expect(l.getByTestId('course-bouton-modifier')).toBeVisible();
      await expect(pageAdmin.getByTestId('course-bouton-supprimer')).toHaveCount(0);

      const statut = await pageAdmin.evaluate(async (id) => {
        await fetch('/api/csrf');
        const jeton = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '');
        const r = await fetch(`/api/administration/courses/${id}`, { method: 'DELETE', headers: { 'X-XSRF-TOKEN': jeton } });
        return { status: r.status, code: ((await r.json()) as { code: string }).code };
      }, course.id);
      expect(statut).toEqual({ status: 403, code: 'ACCES_REFUSE' });
      await pageAdmin.reload();
      await expect(ligne(pageAdmin, course.nom)).toHaveCount(1);
    } finally {
      await ctxAdmin.close();
    }

    // ADMIN_MASTER : bouton présent ; absent pour EN_COURS et TERMINEE (liste interceptée)
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirListe(page);
    await expect(ligne(page, course.nom).getByTestId('course-bouton-supprimer')).toBeVisible();

    for (const statutSimule of ['EN_COURS', 'TERMINEE']) {
      await page.route(MOTIF_LISTE, async (route) => {
        if (route.request().method() !== 'GET') return route.continue();
        const reponse = await route.fetch();
        const corps = (await reponse.json()) as Array<Record<string, unknown>>;
        await route.fulfill({
          response: reponse,
          json: corps.map((c) => (c['id'] === course.id ? { ...c, statut: statutSimule } : c)),
        });
      });
      await ouvrirListe(page);
      await expect(ligne(page, course.nom)).toHaveCount(1);
      await expect(ligne(page, course.nom).getByTestId('course-bouton-supprimer')).toHaveCount(0);
      await page.unroute(MOTIF_LISTE);
    }
    await ouvrirListe(page);
    await expect(ligne(page, course.nom).getByTestId('course-bouton-supprimer')).toBeVisible();

    // la fiche n'a pas de bouton de suppression
    await page.goto(`/administration/courses/${course.id}`);
    await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);
    await expect(page.getByTestId('course-bouton-supprimer')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /supprimer/i })).toHaveCount(0);
  });

  test.describe('CA14 - retours du serveur à la suppression', () => {
    async function preparer(page: Page, playwright: PlaywrightLib, reponse: { status: number; code?: string }): Promise<{ course: { id: string; nom: string }; l: Locator }> {
      const course = await creerCourse(playwright, { logo: false });
      await connecterAdminMaster(page);
      await expect(page).toHaveURL(/\/administration$/);
      await ouvrirListe(page);
      await page.route(
        (url) => /\/api\/administration\/courses\/[^/]+$/.test(url.pathname),
        async (route) => {
          if (route.request().method() !== 'DELETE') return route.fallback();
          await route.fulfill({
            status: reponse.status,
            contentType: 'application/problem+json',
            body: JSON.stringify({ status: reponse.status, code: reponse.code }),
          });
        },
      );
      const l = await ouvrirConfirmation(page, course.nom);
      return { course, l };
    }

    test('CA14 - 409 COURSE_NON_SUPPRIMABLE : message d\'erreur et liste relue', async ({ page, playwright }) => {
      const { course, l } = await preparer(page, playwright, { status: 409, code: 'COURSE_NON_SUPPRIMABLE' });
      const relecture = page.waitForRequest(estListe);
      await l.getByTestId('course-bouton-confirmer-suppression').click();
      await expect(l.getByTestId('course-suppression-erreur')).toHaveText("La course n'est plus en préparation : elle ne peut plus être supprimée.");
      await relecture;
      await expect(l.getByTestId('course-confirmation-suppression')).toHaveCount(0);
      await expect(ligne(page, course.nom)).toHaveCount(1);
    });

    test('CA14 - 404 : la ligne disparaît et le message indique que la course n\'existe plus', async ({ page, playwright }) => {
      const { course, l } = await preparer(page, playwright, { status: 404, code: 'COURSE_INTROUVABLE' });
      await avecApi(playwright, (ctx) => supprimerCourseParApi(ctx, course.id));
      await l.getByTestId('course-bouton-confirmer-suppression').click();
      await expect(page.getByTestId('course-message-suppression')).toHaveText("Cette course n'existe plus.");
      await expect(ligne(page, course.nom)).toHaveCount(0);
    });

    test('CA14 - 403 CSRF_INVALIDE : message de page expirée, confirmation conservée', async ({ page, playwright }) => {
      const { l } = await preparer(page, playwright, { status: 403, code: 'CSRF_INVALIDE' });
      const csrf = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/csrf');
      await l.getByTestId('course-bouton-confirmer-suppression').click();
      await expect(l.getByTestId('course-suppression-erreur')).toHaveText('La page a expiré, veuillez réessayer.');
      await csrf;
      await expect(l.getByTestId('course-confirmation-suppression')).toBeVisible();
    });

    test('CA14 - 500 : service indisponible, confirmation conservée', async ({ page, playwright }) => {
      const { l } = await preparer(page, playwright, { status: 500, code: 'ERREUR_INTERNE' });
      await l.getByTestId('course-bouton-confirmer-suppression').click();
      await expect(l.getByTestId('course-suppression-erreur')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
      await expect(l.getByTestId('course-confirmation-suppression')).toBeVisible();
    });

    test('CA14 - 401 : redirection vers la connexion avec retour', async ({ page, playwright }) => {
      const { l } = await preparer(page, playwright, { status: 401, code: 'NON_AUTHENTIFIE' });
      await l.getByTestId('course-bouton-confirmer-suppression').click();
      await expect(page).toHaveURL(RETOUR_COURSES);
    });

    test('CA14 - 403 ACCES_REFUSE : redirection vers accès refusé', async ({ page, playwright }) => {
      const { l } = await preparer(page, playwright, { status: 403, code: 'ACCES_REFUSE' });
      await l.getByTestId('course-bouton-confirmer-suppression').click();
      await expect(page).toHaveURL(/\/acces-refuse$/);
    });
  });

  test('CA15 - la gestion des courses reste inaccessible au coureur, au bénévole et à l\'anonyme', async ({ page, browser, playwright }) => {
    const coureur = pseudoUnique('coureur');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, coureur));
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));

    await page.goto('/administration/courses');
    await expect(page).toHaveURL(RETOUR_COURSES);

    for (const [pseudo, mdp] of [[coureur, MOT_DE_PASSE], [benevole, MOT_DE_PASSE_BENEVOLE_CREE]]) {
      const contexte = await browser.newContext();
      const p = await contexte.newPage();
      try {
        await connecter(p, pseudo, mdp);
        await p.goto('/administration/courses');
        await expect(p).toHaveURL(/\/acces-refuse$/);
        await expect(p.getByTestId('course-bouton-supprimer')).toHaveCount(0);
      } finally {
        await contexte.close();
      }
    }
  });
});
