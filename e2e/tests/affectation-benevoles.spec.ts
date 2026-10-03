import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs, type Request, type Route } from '@playwright/test';
import { creerCompteParApi, MOT_DE_PASSE, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  affecterBenevolesParApi,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  dateAffichee,
  dateDansJours,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
  type DonneesCourse,
} from './aide-admin';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

async function creerCourse(playwright: PlaywrightLib, donnees: DonneesCourse = courseDeReference()): Promise<{ id: string; nom: string; date: string }> {
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, donnees));
  return { id: c['id'] as string, nom: donnees.nom, date: donnees.date };
}

async function connecter(page: Page, pseudo: string, motDePasse: string, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

const probleme = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code }),
});

const urlFiche = (id: string) => `/administration/courses/${id}`;
const motifFiche = (id: string) => `**/api/administration/courses/${id}`;
const motifPut = (id: string) => `**/api/administration/courses/${id}/benevoles`;
const estPutBenevoles = (r: Request) => r.method() === 'PUT' && r.url().endsWith('/benevoles');

function caseDe(page: Page, pseudo: string) {
  return page.getByLabel(pseudo, { exact: true });
}

/** Ouvre la fiche en interceptant GET (statut et identifiants modifiables) pour les scénarios simulés. */
async function ouvrirFicheSimulee(
  page: Page,
  id: string,
  modifier: (fiche: Record<string, unknown>) => Record<string, unknown>,
): Promise<void> {
  await page.route(motifFiche(id), async (route: Route) => {
    if (route.request().method() !== 'GET') return route.continue();
    const reponse = await route.fetch();
    await route.fulfill({ response: reponse, json: modifier((await reponse.json()) as Record<string, unknown>) });
  });
}

test.describe('Affectation des bénévoles à une course', () => {
  test('CA17 - un admin affecte puis retire des bénévoles depuis la fiche de la course', async ({ page, browser, playwright }) => {
    const leo = pseudoBenevoleUnique();
    const marc = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, leo));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, marc));
    const course = await creerCourse(playwright);

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto('/administration/courses');
    const ligneCourse = page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(course.nom, { exact: true }) });
    await ligneCourse.getByTestId('course-lien-fiche').click();

    await expect(page).toHaveURL(new RegExp(`/administration/courses/${course.id}$`));
    await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
    await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('0 bénévole affecté');
    await expect(caseDe(page, leo)).not.toBeChecked();
    await expect(caseDe(page, marc)).not.toBeChecked();

    await caseDe(page, leo).check();
    await caseDe(page, marc).check();
    await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('2 bénévoles affectés');

    const requete = page.waitForRequest(estPutBenevoles);
    const reponse = page.waitForResponse((r) => estPutBenevoles(r.request()));
    await page.getByTestId('fiche-bouton-enregistrer').click();
    const put = await requete;
    expect(new URL(put.url()).pathname).toBe(`/api/administration/courses/${course.id}/benevoles`);
    expect(put.headers()['content-type']).toContain('application/json');
    expect(put.postDataJSON().benevoleIds).toHaveLength(2);
    expect((await reponse).status()).toBe(200);
    await expect(page.getByTestId('fiche-message-succes')).toHaveText(`Les bénévoles de la course ${course.nom} ont été enregistrés.`);

    await page.reload();
    await expect(caseDe(page, leo)).toBeChecked();
    await expect(caseDe(page, marc)).toBeChecked();

    await caseDe(page, marc).uncheck();
    await page.getByTestId('fiche-bouton-enregistrer').click();
    await expect(page.getByTestId('fiche-benevoles-compteur')).toHaveText('1 bénévole affecté');
    await expect(page.getByTestId('fiche-message-succes')).toBeVisible();
    await page.reload();
    await expect(caseDe(page, leo)).toBeChecked();
    await expect(caseDe(page, marc)).not.toBeChecked();

    await page.getByTestId('fiche-lien-retour').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);

    // même opération avec un ADMIN
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    const contexte = await browser.newContext();
    const pageAdmin = await contexte.newPage();
    await connecter(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pageAdmin).toHaveURL(/\/administration$/);
    await pageAdmin.goto(urlFiche(course.id));
    await expect(caseDe(pageAdmin, leo)).toBeChecked();
    await caseDe(pageAdmin, marc).check();
    await expect(pageAdmin.getByTestId('fiche-benevoles-compteur')).toHaveText('2 bénévoles affectés');
    const reponseAdmin = pageAdmin.waitForResponse((r) => estPutBenevoles(r.request()));
    await pageAdmin.getByTestId('fiche-bouton-enregistrer').click();
    expect((await reponseAdmin).status()).toBe(200);
    await expect(pageAdmin.getByTestId('fiche-message-succes')).toBeVisible();
    await contexte.close();
  });

  test('CA18 - le bénévole voit à son accueil les seules courses auxquelles il est affecté', async ({ page, browser, playwright }) => {
    const benevole = pseudoBenevoleUnique();
    const autre = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, autre));
    const dateA = dateDansJours(20);
    const dateB = dateDansJours(40);
    const courseA = await creerCourse(playwright, courseDeReference({ nom: nomCourseUnique('Course A'), date: dateA }));
    const courseB = await creerCourse(playwright, courseDeReference({ nom: nomCourseUnique('Course B'), date: dateB }));

    await connecter(page, benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page).toHaveURL(/\/benevole$/);
    await expect(page.getByTestId('accueil-benevole-vide')).toHaveText('Aucune course à scanner pour le moment.');

    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, courseA.id, [benevole, autre]));
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, courseB.id, [benevole]));
    await page.reload();
    await expect(page.getByTestId('accueil-benevole-vide')).toHaveCount(0);
    const lignes = page.getByTestId('benevole-ligne-course');
    await expect(lignes).toHaveCount(2);
    await expect(lignes.nth(0).getByTestId('benevole-course-nom')).toHaveText(courseB.nom);
    await expect(lignes.nth(0).getByTestId('benevole-course-date')).toHaveText(dateAffichee(dateB));
    await expect(lignes.nth(0).getByTestId('benevole-course-statut')).toHaveText('En préparation');
    await expect(lignes.nth(1).getByTestId('benevole-course-nom')).toHaveText(courseA.nom);
    await expect(lignes.nth(1).getByTestId('benevole-course-date')).toHaveText(dateAffichee(dateA));
    await expect(page.getByTestId('accueil-benevole-titre')).toHaveText('Espace bénévole');

    // le second bénévole (affecté seulement à A) ne voit pas B
    const ctxAutre = await browser.newContext();
    const pageAutre = await ctxAutre.newPage();
    await connecter(pageAutre, autre, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(pageAutre.getByTestId('benevole-ligne-course')).toHaveCount(1);
    await expect(pageAutre.getByTestId('benevole-course-nom')).toHaveText(courseA.nom);
    await ctxAutre.close();

    // retrait de A par la fiche (UI), sans reconnexion du bénévole
    const ctxAdmin = await browser.newContext();
    const pageAdmin = await ctxAdmin.newPage();
    await connecterAdminMaster(pageAdmin);
    await expect(pageAdmin).toHaveURL(/\/administration$/);
    await pageAdmin.goto(urlFiche(courseA.id));
    await expect(caseDe(pageAdmin, benevole)).toBeChecked();
    await caseDe(pageAdmin, benevole).uncheck();
    await pageAdmin.getByTestId('fiche-bouton-enregistrer').click();
    await expect(pageAdmin.getByTestId('fiche-message-succes')).toBeVisible();

    await page.reload();
    await expect(lignes).toHaveCount(1);
    await expect(page.getByTestId('benevole-course-nom')).toHaveText(courseB.nom);

    await pageAdmin.goto(urlFiche(courseB.id));
    await caseDe(pageAdmin, benevole).uncheck();
    await pageAdmin.getByTestId('fiche-bouton-enregistrer').click();
    await expect(pageAdmin.getByTestId('fiche-message-succes')).toBeVisible();
    await ctxAdmin.close();

    await page.reload();
    await expect(page.getByTestId('accueil-benevole-vide')).toHaveText('Aucune course à scanner pour le moment.');
    await expect(page.getByTestId('accueil-benevole-titre')).toHaveText('Espace bénévole');
  });

  test('CA19 - la fiche s\'adapte au statut de la course et à la liste des bénévoles (réponses interceptées)', async ({ page, playwright }) => {
    const course = await creerCourse(playwright);
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));

    // TERMINEE : verrouillée
    await ouvrirFicheSimulee(page, course.id, (f) => ({ ...f, statut: 'TERMINEE' }));
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-statut')).toHaveText('Terminée');
    await expect(caseDe(page, benevole)).toBeDisabled();
    for (const c of await page.getByTestId('fiche-benevole-case').all()) await expect(c).toBeDisabled();
    await expect(page.getByTestId('fiche-bouton-enregistrer')).toHaveCount(0);
    await expect(page.getByTestId('fiche-benevoles-verrouille')).toBeVisible();

    // EN_COURS : active, enregistrement simulé en 200
    await page.unroute(motifFiche(course.id));
    await ouvrirFicheSimulee(page, course.id, (f) => ({ ...f, statut: 'EN_COURS' }));
    await page.route(motifPut(course.id), (route) =>
      route.fulfill({ status: 200, json: { ...courseSimulee(course), statut: 'EN_COURS', benevoleIds: [] } }),
    );
    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-statut')).toHaveText('En cours');
    await expect(caseDe(page, benevole)).toBeEnabled();
    await expect(page.getByTestId('fiche-bouton-enregistrer')).toBeVisible();
    await expect(page.getByTestId('fiche-benevoles-verrouille')).toHaveCount(0);
    await page.getByTestId('fiche-bouton-enregistrer').click();
    await expect(page.getByTestId('fiche-message-succes')).toBeVisible();

    // aucun bénévole dans l'annuaire
    await page.unroute(motifFiche(course.id));
    await page.route('**/api/administration/benevoles', (route) => route.fulfill({ status: 200, json: [] }));
    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-benevoles-vide')).toBeVisible();
    await expect(page.getByTestId('fiche-lien-benevoles')).toHaveAttribute('href', '/administration/benevoles');

    // identifiant affecté absent de la liste
    await ouvrirFicheSimulee(page, course.id, (f) => ({ ...f, benevoleIds: ['00000000-0000-0000-0000-000000000000'] }));
    await page.goto(urlFiche(course.id));
    const inconnue = page.getByTestId('fiche-ligne-benevole').filter({ hasText: 'Bénévole inconnu' });
    await expect(inconnue).toHaveCount(1);
    await expect(inconnue.getByTestId('fiche-benevole-case')).toBeChecked();
  });

  test('CA20 - la fiche et l\'accueil bénévole affichent les erreurs de l\'API (réponses interceptées)', async ({ page, browser, playwright }) => {
    const course = await creerCourse(playwright);
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);

    const enregistrerAvec = async (reponse: ReturnType<typeof probleme>) => {
      await page.unroute(motifPut(course.id)).catch(() => undefined);
      await page.route(motifPut(course.id), (route) => route.fulfill(reponse));
      await page.goto(urlFiche(course.id));
      await caseDe(page, benevole).check();
      await page.getByTestId('fiche-bouton-enregistrer').click();
    };

    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);

    await page.route(motifPut(course.id), (route) =>
      route.fulfill({
        status: 400,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: 400, code: 'VALIDATION_ECHOUEE', erreurs: [{ champ: 'benevoleIds', code: 'BENEVOLE_INCONNU' }] }),
      }),
    );
    await caseDe(page, benevole).check();
    await page.getByTestId('fiche-bouton-enregistrer').click();
    await expect(page.getByTestId('fiche-erreur')).toHaveText("Un des bénévoles choisis n'existe plus. Rechargez la page.");

    await enregistrerAvec(probleme(409, 'COURSE_TERMINEE'));
    await expect(page.getByTestId('fiche-erreur')).toHaveText('La course est terminée : ses bénévoles ne peuvent plus être modifiés.');

    await enregistrerAvec(probleme(403, 'CSRF_INVALIDE'));
    await expect(page.getByTestId('fiche-erreur')).toHaveText('La page a expiré, veuillez réessayer.');
    await expect(caseDe(page, benevole)).toBeChecked();

    await enregistrerAvec(probleme(500, 'ERREUR_INTERNE'));
    await expect(page.getByTestId('fiche-erreur')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(caseDe(page, benevole)).toBeChecked();

    await enregistrerAvec(probleme(404, 'COURSE_INTROUVABLE'));
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await expect(page.getByTestId('courses-message-erreur')).toHaveText("Cette course n'existe plus.");

    await enregistrerAvec(probleme(403, 'ACCES_REFUSE'));
    await expect(page).toHaveURL(/\/acces-refuse$/);

    await enregistrerAvec(probleme(401, 'NON_AUTHENTIFIE'));
    await expect(page).toHaveURL(new RegExp(`/connexion\\?retour=%2Fadministration%2Fcourses%2F${course.id}$`));

    // GET de la fiche en 404, liste des bénévoles en 500 (nouvelle session admin)
    const ctx = await browser.newContext();
    const p = await ctx.newPage();
    await connecterAdminMaster(p);
    await expect(p).toHaveURL(/\/administration$/);
    await p.route(motifFiche(course.id), (route) => route.request().method() === 'GET' ? route.fulfill(probleme(404, 'COURSE_INTROUVABLE')) : route.continue());
    await p.goto(urlFiche(course.id));
    await expect(p.getByTestId('fiche-erreur-introuvable')).toHaveText("Cette course n'existe plus.");
    await p.unroute(motifFiche(course.id));
    await p.route('**/api/administration/benevoles', (route) => route.fulfill(probleme(500, 'ERREUR_INTERNE')));
    await p.goto(urlFiche(course.id));
    await expect(p.getByTestId('fiche-benevoles-erreur')).toBeVisible();
    await p.unroute('**/api/administration/benevoles');
    await p.route(motifFiche(course.id), (route) => route.request().method() === 'GET' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue());
    await p.goto(urlFiche(course.id));
    await expect(p.getByTestId('fiche-erreur-chargement')).toBeVisible();
    await ctx.close();

    // accueil bénévole en 500
    const ctxB = await browser.newContext();
    const pb = await ctxB.newPage();
    await connecter(pb, benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(pb).toHaveURL(/\/benevole$/);
    await pb.route('**/api/benevole/courses', (route) => route.fulfill(probleme(500, 'ERREUR_INTERNE')));
    await pb.reload();
    await expect(pb.getByTestId('accueil-benevole-erreur')).toHaveText('Impossible de charger vos courses. Réessayez plus tard.');
    await ctxB.close();
  });

  test('CA21 - la fiche est réservée aux admins et l\'accueil bénévole aux bénévoles', async ({ page, browser, request, playwright }) => {
    const course = await creerCourse(playwright);
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));

    for (const [pseudo, mdp] of [[coureur, MOT_DE_PASSE], [benevole, MOT_DE_PASSE_BENEVOLE_CREE]]) {
      const ctx = await browser.newContext();
      const p = await ctx.newPage();
      await connecter(p, pseudo, mdp);
      await expect(p.getByTestId('entete-pseudo')).toHaveText(pseudo);
      await p.goto(urlFiche(course.id));
      await expect(p).toHaveURL(/\/acces-refuse$/);
      await ctx.close();
    }

    const ctxC = await browser.newContext();
    const pc = await ctxC.newPage();
    await connecter(pc, coureur, MOT_DE_PASSE);
    await expect(pc.getByTestId('entete-pseudo')).toHaveText(coureur);
    await pc.goto('/benevole');
    await expect(pc).toHaveURL(/\/acces-refuse$/);
    await ctxC.close();

    await page.goto(urlFiche(course.id));
    await expect(page).toHaveURL(new RegExp(`/connexion\\?retour=%2Fadministration%2Fcourses%2F${course.id}$`));
  });
});

/** Corps de réponse minimal d'une fiche simulée (CourseReponse + benevoleIds). */
function courseSimulee(c: { id: string; nom: string; date: string }): Record<string, unknown> {
  return {
    id: c.id,
    nom: c.nom,
    date: c.date,
    distanceBoucleMetres: 6706,
    dureeBoucleMinutes: 60,
    denivelePositifBoucleMetres: 120,
    nombreMaxParticipants: 50,
    nombreMaxBoucles: 24,
    logoUrl: null,
  };
}
