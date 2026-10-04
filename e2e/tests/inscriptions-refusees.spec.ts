import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs, type Request, type Response } from '@playwright/test';
import { creerCompteParApi, pseudoUnique } from './aide-connexion';
import { courseDeReference, creerCourseParApi, exigerIdentifiantsAdminMaster, nomCourseUnique } from './aide-admin';
import { connecterCoureur, ligneCoureur, placerStatutCourseEnBase, sinscrireParApi } from './aide-coureur';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const estInscription = (r: Request) => r.method() === 'POST' && /^\/api\/coureur\/courses\/[^/]+\/inscriptions$/.test(new URL(r.url()).pathname);
const estListe = (r: Request) => r.method() === 'GET' && new URL(r.url()).pathname === '/api/coureur/courses';
const estReponseInscription = (r: Response) => estInscription(r.request());

/**
 * Observe la vraie réponse du serveur : la requête est relayée telle quelle (`route.fetch`), son corps est lu
 * avant de la rendre au navigateur (la lecture après coup échoue quand la page relit la liste).
 */
async function observerCorpsInscription(page: Page): Promise<{ corps: () => Promise<{ status: number; code?: string }> }> {
  let dernier: { status: number; code?: string } = { status: 0 };
  await page.route(
    (url) => /^\/api\/coureur\/courses\/[^/]+\/inscriptions$/.test(url.pathname),
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      const reponse = await route.fetch();
      const texte = await reponse.text();
      let code: string | undefined;
      try {
        code = (JSON.parse(texte) as { code?: string }).code;
      } catch {
        code = undefined;
      }
      dernier = { status: reponse.status(), code };
      await route.fulfill({ response: reponse, body: texte });
    },
  );
  return { corps: async () => dernier };
}

const SERVICE_INDISPONIBLE = 'Service indisponible, veuillez réessayer plus tard.';

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

async function creerCourse(playwright: PlaywrightLib, nombreMaxParticipants = 50, prefixe?: string): Promise<{ id: string; nom: string }> {
  const nom = nomCourseUnique(prefixe);
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, nombreMaxParticipants })));
  return { id: c['id'] as string, nom };
}

test.describe('Inscriptions refusées', () => {
  test("CA10 - une course pleine reste listée « Complète » avec le bouton désactivé, sans effet sur les autres courses", async ({ browser, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const bruno = await creerCoureur(playwright, 'Bruno');
    const chloe = await creerCoureur(playwright, 'Chloé');
    const duo = await creerCourse(playwright, 2, 'Backyard duo');
    const autre = await creerCourse(playwright, 50);

    const contextes = [await browser.newContext(), await browser.newContext(), await browser.newContext()];
    const [pageAlice, pageBruno, pageChloe] = await Promise.all(contextes.map((c) => c.newPage()));
    try {
      await connecterCoureur(pageAlice, alice);
      await connecterCoureur(pageBruno, bruno);
      const la = ligneCoureur(pageAlice, duo.nom);
      const lb = ligneCoureur(pageBruno, duo.nom);
      await la.getByTestId('coureur-bouton-sinscrire').click();
      await expect(la.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
      await lb.getByTestId('coureur-bouton-sinscrire').click();
      await expect(lb.getByTestId('coureur-dossard')).toHaveText('Dossard 2');

      await connecterCoureur(pageChloe, chloe);
      const lc = ligneCoureur(pageChloe, duo.nom);
      await expect(lc).toHaveCount(1);
      await expect(lc.getByTestId('coureur-course-complete')).toHaveText('Complète');
      await expect(lc.getByTestId('coureur-bouton-sinscrire')).toBeDisabled();
      let posts = 0;
      pageChloe.on('request', (r) => {
        if (estInscription(r)) posts++;
      });
      await lc.getByTestId('coureur-bouton-sinscrire').click({ force: true });
      await expect(lc.getByTestId('coureur-bouton-sinscrire')).toBeDisabled();
      expect(posts).toBe(0);
      await expect(pageChloe.getByTestId('coureur-erreur')).toHaveCount(0);

      // la coureuse inscrite garde son dossard, sans indicateur « Complète »
      await pageAlice.reload();
      await expect(la.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
      await expect(la.getByTestId('coureur-statut-inscription')).toHaveText('Inscrit');
      await expect(la.getByTestId('coureur-course-complete')).toHaveCount(0);

      // une autre course reste ouverte
      const lAutre = ligneCoureur(pageChloe, autre.nom);
      await expect(lAutre.getByTestId('coureur-course-complete')).toHaveCount(0);
      await expect(lAutre.getByTestId('coureur-bouton-sinscrire')).toHaveText("S'inscrire");
      await expect(lAutre.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();
    } finally {
      await Promise.all(contextes.map((c) => c.close()));
    }
  });

  test("CA10 - la course devient pleine entre l'affichage et le clic : 409 COURSE_COMPLETE, ligne relue « Complète »", async ({ page, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const bruno = await creerCoureur(playwright, 'Bruno');
    const chloe = await creerCoureur(playwright, 'Chloé');
    const duo = await creerCourse(playwright, 2, 'Backyard duo');
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, duo.id));

    await connecterCoureur(page, chloe);
    const l = ligneCoureur(page, duo.nom);
    await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();
    await expect(l.getByTestId('coureur-course-complete')).toHaveCount(0);

    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, bruno, duo.id));

    const observe = await observerCorpsInscription(page);
    const reponse = page.waitForResponse(estReponseInscription);
    const relecture = page.waitForRequest(estListe);
    await l.getByTestId('coureur-bouton-sinscrire').click();
    const r = await reponse;
    expect(r.request().method()).toBe('POST');
    expect(r.status()).toBe(409);
    expect((await observe.corps()).code).toBe('COURSE_COMPLETE');
    await relecture;

    await expect(page.getByTestId('coureur-erreur')).toHaveText('Cette course est complète.');
    await expect(page.getByTestId('coureur-erreur')).not.toContainText('Service indisponible');
    await expect(l.getByTestId('coureur-course-complete')).toHaveText('Complète');
    await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeDisabled();
  });

  test("CA11 - la course démarre entre l'affichage et le clic : inscriptions closes, la course disparaît, aucune inscription créée", async ({ page, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const course = await creerCourse(playwright);
    const autre = await creerCourse(playwright);

    await connecterCoureur(page, alice);
    const l = ligneCoureur(page, course.nom);
    await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();

    placerStatutCourseEnBase(course.id, 'EN_COURS');
    try {
      const observe = await observerCorpsInscription(page);
      const reponse = page.waitForResponse(estReponseInscription);
      await l.getByTestId('coureur-bouton-sinscrire').click();
      const r = await reponse;
      expect(r.status()).toBe(409);
      expect((await observe.corps()).code).toBe('COURSE_NON_OUVERTE');

      await expect(page.getByTestId('coureur-erreur')).toHaveText('Les inscriptions à cette course sont closes.');
      await expect(l).toHaveCount(0);
      await expect(page.getByTestId('coureur-erreur')).toHaveCount(1);

      // l'inscription à une autre course ouverte reste possible
      const lAutre = ligneCoureur(page, autre.nom);
      await lAutre.getByTestId('coureur-bouton-sinscrire').click();
      await expect(lAutre.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
    } finally {
      placerStatutCourseEnBase(course.id, 'EN_PREPARATION');
    }

    // remise en préparation : la course réapparaît sans inscription (aucune créée par le refus)
    await page.reload();
    await expect(l).toHaveCount(1);
    await expect(l.getByTestId('coureur-bouton-sinscrire')).toHaveText("S'inscrire");
    await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();
    await expect(l.getByTestId('coureur-dossard')).toHaveCount(0);
    await expect(l.getByTestId('coureur-statut-inscription')).toHaveCount(0);
  });

  test.describe('CA12 - messages dérivés du code du 409', () => {
    const DETAIL_DIFFERENT = 'DETAIL-SERVEUR-DIFFERENT-A-NE-PAS-AFFICHER';
    const cas: Array<{ titre: string; code?: string; message: string; relue: boolean }> = [
      { titre: 'COURSE_COMPLETE', code: 'COURSE_COMPLETE', message: 'Cette course est complète.', relue: true },
      { titre: 'COURSE_NON_OUVERTE', code: 'COURSE_NON_OUVERTE', message: 'Les inscriptions à cette course sont closes.', relue: true },
      { titre: 'INSCRIPTION_DEJA_EXISTANTE', code: 'INSCRIPTION_DEJA_EXISTANTE', message: 'Vous êtes déjà inscrit à cette course.', relue: true },
      { titre: 'code inconnu AUTRE_CONFLIT', code: 'AUTRE_CONFLIT', message: SERVICE_INDISPONIBLE, relue: false },
      { titre: 'sans corps', message: SERVICE_INDISPONIBLE, relue: false },
    ];

    for (const c of cas) {
      test(`CA12 - 409 ${c.titre} : « ${c.message} »`, async ({ page, playwright }) => {
        const alice = await creerCoureur(playwright, 'Alice');
        const course = await creerCourse(playwright);
        await connecterCoureur(page, alice);
        const l = ligneCoureur(page, course.nom);
        await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();

        await page.route(
          (url) => url.pathname === `/api/coureur/courses/${course.id}/inscriptions`,
          async (route) => {
            if (route.request().method() !== 'POST') return route.fallback();
            if (c.code === undefined) return route.fulfill({ status: 409, body: '' });
            await route.fulfill({
              status: 409,
              contentType: 'application/problem+json',
              body: JSON.stringify({ type: 'about:blank', title: 'Conflit', status: 409, detail: DETAIL_DIFFERENT, code: c.code }),
            });
          },
        );

        const relecture = c.relue ? page.waitForRequest(estListe) : undefined;
        await l.getByTestId('coureur-bouton-sinscrire').click();
        await expect(page.getByTestId('coureur-erreur')).toHaveText(c.message);
        await expect(page.getByTestId('coureur-erreur')).not.toContainText(DETAIL_DIFFERENT);
        if (relecture) await relecture;
        await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeVisible();
        await expect(l.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();
      });
    }
  });

  test('CA13 - double inscription depuis deux onglets : 409 INSCRIPTION_DEJA_EXISTANTE puis ligne « Dossard 1 » sans bouton', async ({ browser, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const course = await creerCourse(playwright);
    const contexte = await browser.newContext();
    const onglet1 = await contexte.newPage();
    try {
      await connecterCoureur(onglet1, alice);
      const onglet2 = await contexte.newPage();
      await onglet2.goto('/coureur');
      const l1 = ligneCoureur(onglet1, course.nom);
      const l2 = ligneCoureur(onglet2, course.nom);
      await expect(l1.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();
      await expect(l2.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();

      await l1.getByTestId('coureur-bouton-sinscrire').click();
      await expect(l1.getByTestId('coureur-dossard')).toHaveText('Dossard 1');

      const observe = await observerCorpsInscription(onglet2);
      const reponse = onglet2.waitForResponse(estReponseInscription);
      const relecture = onglet2.waitForRequest(estListe);
      await l2.getByTestId('coureur-bouton-sinscrire').click();
      const r = await reponse;
      expect(r.request().method()).toBe('POST');
      expect(r.status()).toBe(409);
      expect((await observe.corps()).code).toBe('INSCRIPTION_DEJA_EXISTANTE');
      await relecture;

      await expect(onglet2.getByTestId('coureur-erreur')).toHaveText('Vous êtes déjà inscrit à cette course.');
      await expect(l2.getByTestId('coureur-dossard')).toHaveText('Dossard 1');
      await expect(l2.getByTestId('coureur-statut-inscription')).toHaveText('Inscrit');
      await expect(l2.getByTestId('coureur-bouton-sinscrire')).toHaveCount(0);

      // une seule inscription côté serveur : un nouveau POST forcé renvoie encore « déjà inscrit », le dossard reste 1
      const liste = await onglet2.evaluate(async () => {
        const rep = await fetch('/api/coureur/courses');
        return (await rep.json()) as Array<{ id: string; monInscription: { dossard: number } | null }>;
      });
      expect(liste.find((c) => c.id === course.id)?.monInscription?.dossard).toBe(1);
    } finally {
      await contexte.close();
    }
  });
});
