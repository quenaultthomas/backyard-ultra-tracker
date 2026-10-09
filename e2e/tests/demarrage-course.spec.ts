import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs, type Request, type Route } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir, seConnecter } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  affecterBenevolesParApi,
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
import { ligneCoureur, sinscrireParApi } from './aide-coureur';

/*
 * Incrément 4.1 : démarrer une course depuis la fiche (CA14 à CA18 ; CA19 est dans donnees-demo.spec.ts).
 * Aucune horloge pilotable avant 4.3 : les courses à démarrer sont datées du jour à Paris (dateDansJours(0)).
 * Un test lancé à cheval sur minuit (Paris) peut échouer : le relancer.
 */
type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const AGRANDIR = 'html{font-size:32px}';

const TEXTE_AIDE = "Le démarrage n'est possible que le jour de la course et si au moins un coureur est inscrit. Il lance la première boucle immédiatement.";
const MESSAGE_HORS_DATE = 'La course ne peut être démarrée que le jour de sa date.';
const MESSAGE_SANS_INSCRIT = "La course ne peut pas être démarrée : aucun coureur n'est inscrit.";
const MESSAGE_NON_DEMARRABLE = "La course n'est plus en préparation : elle ne peut plus être démarrée.";

const urlFiche = (id: string) => `/administration/courses/${id}`;
const motifDemarrage = (id: string) => `**/api/administration/courses/${id}/demarrage`;
const motifFiche = (id: string) => `**/api/administration/courses/${id}`;
const estDemarrage = (r: Request) => r.method() === 'POST' && /\/api\/administration\/courses\/[^/]+\/demarrage$/.test(new URL(r.url()).pathname);

const probleme = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code }),
});

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

/** Crée une course (admin master, API) à `jours` jours d'aujourd'hui à Paris, avec les coureurs donnés inscrits. */
async function creerCourse(playwright: PlaywrightLib, jours: number, inscrits: string[] = [], prefixe = 'Course E2E'): Promise<CourseCreee> {
  const nom = nomCourseUnique(prefixe);
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date: dateDansJours(jours) })));
  const id = c['id'] as string;
  for (const pseudo of inscrits) await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, id));
  return { id, nom };
}

async function connecter(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

function ligneListe(page: Page, nom: string): Locator {
  return page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });
}

async function ouvrirFiche(page: Page, course: CourseCreee): Promise<void> {
  await page.goto(urlFiche(course.id));
  await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);
}

/** Texte normalisé de « Démarrée le jj/mm/aaaa à HH:mm » et instant correspondant (heure locale du navigateur). */
async function lireDemarreeLe(page: Page): Promise<{ texte: string; instant: number }> {
  const brut = await page.getByTestId('fiche-demarree-le').innerText();
  const texte = brut.replace(/\s+/g, ' ').trim();
  const m = /^Démarrée le (\d{2})\/(\d{2})\/(\d{4}) à (\d{2}):(\d{2})$/.exec(texte);
  expect(m, `format inattendu : « ${texte} »`).not.toBeNull();
  const [, jj, mm, aaaa, hh, min] = m!;
  return { texte, instant: new Date(Number(aaaa), Number(mm) - 1, Number(jj), Number(hh), Number(min)).getTime() };
}

/** Ouvre la confirmation, confirme et attend la réponse du POST de démarrage. */
async function demarrerParInterface(page: Page): Promise<number> {
  await page.getByTestId('fiche-bouton-demarrer').click();
  const reponse = page.waitForResponse((r) => estDemarrage(r.request()));
  await page.getByTestId('fiche-bouton-confirmer-demarrage').click();
  return (await reponse).status();
}

test.describe('Démarrage d\'une course depuis la fiche', () => {
  test('CA14 - l\'admin démarre une course du jour depuis la fiche : confirmation, double clic, mise à jour en place', async ({ page, browser, playwright }) => {
    const bruno = await creerCoureur(playwright, 'bruno');
    const chloe = await creerCoureur(playwright, 'chloe');
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    const course = await creerCourse(playwright, 0, [bruno, chloe]);
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [benevole]));

    const posts: string[] = [];
    page.on('request', (r) => {
      if (estDemarrage(r)) posts.push(r.url());
    });

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirFiche(page, course);

    // état initial
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
    await expect(page.getByTestId('fiche-bouton-demarrer')).toBeVisible();
    await expect(page.getByTestId('fiche-bouton-demarrer')).toBeEnabled();
    await expect(page.getByTestId('fiche-bouton-demarrer')).toHaveText('Démarrer la course');
    await expect(page.getByTestId('fiche-aide-demarrage')).toHaveText(TEXTE_AIDE);
    await expect(page.getByTestId('fiche-demarree-le')).toHaveCount(0);
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toHaveCount(0);

    // ouverture de la confirmation : aucun appel réseau
    await page.getByTestId('fiche-bouton-demarrer').click();
    const confirmation = page.getByTestId('fiche-confirmation-demarrage');
    await expect(confirmation).toBeVisible();
    await expect(confirmation).toContainText(`Démarrer la course ${course.nom} maintenant ?`);
    await expect(confirmation).toContainText('Le départ de la première boucle est immédiat.');
    await expect(confirmation).toContainText("Les inscriptions et les désinscriptions seront fermées et les paramètres de la course ne pourront plus être modifiés.");
    await expect(confirmation).toContainText('Cette action est irréversible.');
    await expect(page.getByTestId('fiche-bouton-confirmer-demarrage')).toHaveText('Démarrer maintenant');
    await expect(page.getByTestId('fiche-bouton-annuler-demarrage')).toHaveText('Annuler');
    await expect(page.getByTestId('fiche-bouton-annuler-demarrage')).toBeFocused();
    expect(posts).toHaveLength(0);

    // annulation : toujours aucun appel, le focus revient sur le bouton de démarrage
    await page.getByTestId('fiche-bouton-annuler-demarrage').click();
    await expect(confirmation).toHaveCount(0);
    await expect(page.getByTestId('fiche-bouton-demarrer')).toBeFocused();
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
    expect(posts).toHaveLength(0);

    // double clic sur « Démarrer maintenant » : un seul POST, réponse 200
    await page.getByTestId('fiche-bouton-demarrer').click();
    await expect(confirmation).toBeVisible();
    const reponse = page.waitForResponse((r) => estDemarrage(r.request()));
    await page.getByTestId('fiche-bouton-confirmer-demarrage').dblclick();
    const post = await reponse;
    expect(post.status()).toBe(200);
    expect(new URL(post.url()).pathname).toBe(`/api/administration/courses/${course.id}/demarrage`);

    // mise à jour en place, sans rechargement
    await expect(page.getByTestId('fiche-statut')).toHaveText('En cours');
    await expect(page.getByTestId('fiche-bouton-demarrer')).toHaveCount(0);
    await expect(page.getByTestId('fiche-aide-demarrage')).toHaveCount(0);
    await expect(confirmation).toHaveCount(0);
    await expect(page.getByTestId('fiche-message-demarrage')).toHaveText(`La course ${course.nom} a été démarrée.`);
    await expect(page.getByTestId('fiche-message-demarrage')).toHaveAttribute('role', 'status');
    await expect(page.getByTestId('fiche-demarree-le')).toBeVisible();
    const { texte, instant } = await lireDemarreeLe(page);
    expect(Math.abs(Date.now() - instant), `heure de départ affichée : ${texte}`).toBeLessThan(5 * 60_000);
    const aujourdhuiLocal = new Date();
    const jour = [aujourdhuiLocal.getDate(), aujourdhuiLocal.getMonth() + 1].map((n) => String(n).padStart(2, '0')).join('/');
    expect(texte).toContain(`Démarrée le ${jour}/${aujourdhuiLocal.getFullYear()} à `);
    expect(posts).toHaveLength(1);

    // les sections Bénévoles et Inscrits restent affichées, bénévoles actifs
    await expect(page.getByTestId('fiche-inscrits-compteur')).toHaveText('2 inscrits sur 50');
    await expect(page.getByTestId('fiche-inscrits-ligne')).toHaveCount(2);
    await expect(page.getByLabel(benevole, { exact: true })).toBeChecked();
    await expect(page.getByLabel(benevole, { exact: true })).toBeEnabled();
    await expect(page.getByTestId('fiche-bouton-enregistrer')).toBeVisible();

    // après F5 : même statut, même heure, aucun bouton
    await page.reload();
    await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En cours');
    expect((await lireDemarreeLe(page)).texte).toBe(texte);
    await expect(page.getByTestId('fiche-bouton-demarrer')).toHaveCount(0);
    await expect(page.getByTestId('fiche-aide-demarrage')).toHaveCount(0);

    // la liste porte la pastille « En cours »
    await page.goto('/administration/courses');
    await expect(ligneListe(page, course.nom).getByTestId('course-statut')).toHaveText('En cours');

    // un ADMIN démarre de la même façon une seconde course du jour
    const nadia = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    const seconde = await creerCourse(playwright, 0, [bruno]);
    const contexte = await browser.newContext();
    const pageAdmin = await contexte.newPage();
    await connecter(pageAdmin, nadia, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pageAdmin).toHaveURL(/\/administration$/);
    await ouvrirFiche(pageAdmin, seconde);
    await expect(pageAdmin.getByTestId('fiche-statut')).toHaveText('En préparation');
    expect(await demarrerParInterface(pageAdmin)).toBe(200);
    await expect(pageAdmin.getByTestId('fiche-statut')).toHaveText('En cours');
    await expect(pageAdmin.getByTestId('fiche-message-demarrage')).toHaveText(`La course ${seconde.nom} a été démarrée.`);
    await expect(pageAdmin.getByTestId('fiche-demarree-le')).toBeVisible();
    await contexte.close();
  });

  test('CA15 - une course démarrée disparaît des inscriptions et n\'est plus modifiable ni supprimable, ses inscrits gardent dossard et QR', async ({ page, browser, playwright }) => {
    const bruno = await creerCoureur(playwright, 'bruno');
    const chloe = await creerCoureur(playwright, 'chloe');
    const alice = await creerCoureur(playwright, 'alice');
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    const course = await creerCourse(playwright, 0, [bruno, chloe]);
    const autre = await creerCourse(playwright, 30);
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [benevole]));

    await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, course.id));

    // liste des courses de l'admin master : ni « Modifier » ni « Supprimer » sur la course démarrée
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto('/administration/courses');
    const carte = ligneListe(page, course.nom);
    await expect(carte).toBeVisible();
    await expect(carte.getByTestId('course-statut')).toHaveText('En cours');
    await expect(carte.getByTestId('course-bouton-modifier')).toHaveCount(0);
    await expect(carte.getByTestId('course-bouton-supprimer')).toHaveCount(0);
    const carteAutre = ligneListe(page, autre.nom);
    await expect(carteAutre.getByTestId('course-bouton-modifier')).toBeVisible();
    await expect(carteAutre.getByTestId('course-bouton-supprimer')).toBeVisible();

    // Espace coureur d'une coureuse non inscrite : la course n'est plus proposée, l'autre l'est
    const ctxAlice = await browser.newContext();
    const pageAlice = await ctxAlice.newPage();
    await seConnecter(pageAlice, alice);
    await pageAlice.goto('/coureur');
    await expect(ligneCoureur(pageAlice, autre.nom)).toHaveCount(1);
    await expect(ligneCoureur(pageAlice, course.nom)).toHaveCount(0);
    await ctxAlice.close();

    // inscription manuelle refusée par l'API
    const refus = await avecApi(playwright, async (ctx) => {
      await ctx.post('/api/connexion', { headers: { 'X-XSRF-TOKEN': await jeton(ctx) }, data: { pseudo: alice, motDePasse: MOT_DE_PASSE } });
      const r = await ctx.post(`/api/coureur/courses/${course.id}/inscriptions`, { headers: { 'X-XSRF-TOKEN': await jeton(ctx) } });
      return { status: r.status(), corps: (await r.json()) as { code: string } };
    });
    expect(refus.status).toBe(409);
    expect(refus.corps.code).toBe('COURSE_NON_OUVERTE');

    // Mes inscriptions de Bruno : dossard, QR et statut conservés, aucune désinscription
    const ctxBruno = await browser.newContext();
    const pageBruno = await ctxBruno.newPage();
    await seConnecter(pageBruno, bruno);
    await pageBruno.goto('/coureur/inscriptions');
    const ligne = pageBruno.getByTestId('inscriptions-ligne').filter({ has: pageBruno.getByTestId('inscriptions-course-nom').getByText(course.nom, { exact: true }) });
    await expect(ligne).toHaveCount(1);
    await expect(ligne.getByTestId('inscriptions-dossard')).toContainText('1');
    await expect(ligne.getByTestId('inscriptions-qr')).toBeVisible();
    await expect(ligne.getByTestId('inscriptions-statut')).toHaveText('En course');
    await expect(ligne.getByTestId('inscriptions-bouton-desinscrire')).toHaveCount(0);
    await ctxBruno.close();

    // Espace bénévole : la course reste listée avec la pastille « En cours »
    const ctxBenevole = await browser.newContext();
    const pageBenevole = await ctxBenevole.newPage();
    await connecter(pageBenevole, benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(pageBenevole).toHaveURL(/\/benevole$/);
    const ligneBenevole = pageBenevole.getByTestId('benevole-ligne-course').filter({ has: pageBenevole.getByTestId('benevole-course-nom').getByText(course.nom, { exact: true }) });
    await expect(ligneBenevole).toHaveCount(1);
    await expect(ligneBenevole.getByTestId('benevole-course-statut')).toHaveText('En cours');
    await ctxBenevole.close();
  });

  test('CA16 - le démarrage est refusé hors du jour de la course ou sans inscrit, avec un message sur la fiche', async ({ page, playwright }) => {
    const inscritA1 = await creerCoureur(playwright, 'a1');
    const inscritA2 = await creerCoureur(playwright, 'a2');
    const inscritC = await creerCoureur(playwright, 'c');
    const retardataire = await creerCoureur(playwright, 'b');
    const demain = await creerCourse(playwright, 1, [inscritA1, inscritA2]);
    const sansInscrit = await creerCourse(playwright, 0);
    const avecUnInscrit = await creerCourse(playwright, 0, [inscritC]);

    const posts: string[] = [];
    page.on('request', (r) => {
      if (estDemarrage(r)) posts.push(r.url());
    });

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);

    // A : datée de demain
    await ouvrirFiche(page, demain);
    expect(await demarrerParInterface(page)).toBe(409);
    await expect(page.getByTestId('fiche-erreur-demarrage')).toHaveText(MESSAGE_HORS_DATE);
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toHaveCount(0);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
    await expect(page.getByTestId('fiche-bouton-demarrer')).toBeEnabled();
    expect(posts).toHaveLength(1);

    // B : du jour, sans inscrit
    await ouvrirFiche(page, sansInscrit);
    expect(await demarrerParInterface(page)).toBe(409);
    await expect(page.getByTestId('fiche-erreur-demarrage')).toHaveText(MESSAGE_SANS_INSCRIT);
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toHaveCount(0);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
    await expect(page.getByTestId('fiche-bouton-demarrer')).toBeEnabled();
    expect(posts).toHaveLength(2);

    // le nouvel essai efface le message précédent (dès l'ouverture de la confirmation)
    await page.getByTestId('fiche-bouton-demarrer').click();
    await expect(page.getByTestId('fiche-erreur-demarrage')).toHaveCount(0);
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toBeVisible();
    await page.getByTestId('fiche-bouton-annuler-demarrage').click();
    expect(posts).toHaveLength(2);

    // un coureur s'inscrit à B par l'API : le nouvel essai réussit
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, retardataire, sansInscrit.id));
    await page.reload();
    await expect(page.getByTestId('fiche-titre')).toHaveText(sansInscrit.nom);
    expect(await demarrerParInterface(page)).toBe(200);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En cours');
    await expect(page.getByTestId('fiche-erreur-demarrage')).toHaveCount(0);
    await expect(page.getByTestId('fiche-message-demarrage')).toHaveText(`La course ${sansInscrit.nom} a été démarrée.`);
    expect(posts).toHaveLength(3);

    // C : du jour, un seul inscrit : démarrage accepté
    await ouvrirFiche(page, avecUnInscrit);
    expect(await demarrerParInterface(page)).toBe(200);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En cours');
    expect(posts).toHaveLength(4);

    // A n'a pas bougé
    await ouvrirFiche(page, demain);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
    await expect(page.getByTestId('fiche-demarree-le')).toHaveCount(0);
    expect(posts).toHaveLength(4);
  });

  test('CA17 - la fiche affiche chaque erreur du démarrage (réponses interceptées) et protège l\'accès par rôle', async ({ page, browser, playwright }) => {
    const bruno = await creerCoureur(playwright, 'bruno');
    const course = await creerCourse(playwright, 0, [bruno]);

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirFiche(page, course);

    const confirmer = async () => {
      await page.getByTestId('fiche-bouton-demarrer').click();
      await page.getByTestId('fiche-bouton-confirmer-demarrage').click();
    };
    const simuler = async (reponse: ReturnType<typeof probleme>) => {
      await page.unroute(motifDemarrage(course.id)).catch(() => undefined);
      await page.route(motifDemarrage(course.id), (route) => route.fulfill(reponse));
    };

    // 403 CSRF_INVALIDE : message, confirmation conservée
    await simuler(probleme(403, 'CSRF_INVALIDE'));
    await confirmer();
    await expect(page.getByTestId('fiche-erreur-demarrage')).toHaveText('La page a expiré, veuillez réessayer.');
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toBeVisible();
    await expect(page.getByTestId('fiche-bouton-confirmer-demarrage')).toBeEnabled();

    // 500 : message, confirmation conservée, boutons réactivés
    await simuler(probleme(500, 'ERREUR_INTERNE'));
    await page.getByTestId('fiche-bouton-confirmer-demarrage').click();
    await expect(page.getByTestId('fiche-erreur-demarrage')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toBeVisible();
    await expect(page.getByTestId('fiche-bouton-confirmer-demarrage')).toBeEnabled();
    await expect(page.getByTestId('fiche-bouton-annuler-demarrage')).toBeEnabled();
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');

    // 409 COURSE_NON_DEMARRABLE : message, confirmation fermée, fiche relue (GET intercepté : EN_COURS)
    await page.route(motifFiche(course.id), async (route: Route) => {
      if (route.request().method() !== 'GET') return route.continue();
      const reponse = await route.fetch();
      const fiche = (await reponse.json()) as Record<string, unknown>;
      await route.fulfill({ response: reponse, json: { ...fiche, statut: 'EN_COURS' } });
    });
    await simuler(probleme(409, 'COURSE_NON_DEMARRABLE'));
    await page.getByTestId('fiche-bouton-confirmer-demarrage').click();
    await expect(page.getByTestId('fiche-erreur-demarrage')).toHaveText(MESSAGE_NON_DEMARRABLE);
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toHaveCount(0);
    await expect(page.getByTestId('fiche-statut')).toHaveText('En cours');
    await expect(page.getByTestId('fiche-bouton-demarrer')).toHaveCount(0);
    await page.unroute(motifFiche(course.id));

    // 404 : retour à la liste avec le message
    await ouvrirFiche(page, course);
    await simuler(probleme(404, 'COURSE_INTROUVABLE'));
    await confirmer();
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await expect(page.getByTestId('courses-message-erreur')).toHaveText("Cette course n'existe plus.");

    // 403 ACCES_REFUSE
    await ouvrirFiche(page, course);
    await simuler(probleme(403, 'ACCES_REFUSE'));
    await confirmer();
    await expect(page).toHaveURL(/\/acces-refuse$/);

    // 401 : connexion avec retour vers la fiche
    await ouvrirFiche(page, course);
    await simuler(probleme(401, 'NON_AUTHENTIFIE'));
    await confirmer();
    await expect(page).toHaveURL(new RegExp(`/connexion\\?retour=%2Fadministration%2Fcourses%2F${course.id}$`));

    // la course n'a jamais été démarrée par ces essais simulés
    await page.unroute(motifDemarrage(course.id));
    const ctxAdmin = await browser.newContext();
    const pageAdmin = await ctxAdmin.newPage();
    await connecterAdminMaster(pageAdmin);
    await expect(pageAdmin).toHaveURL(/\/administration$/);
    await ouvrirFiche(pageAdmin, course);
    await expect(pageAdmin.getByTestId('fiche-statut')).toHaveText('En préparation');
    await expect(pageAdmin.getByTestId('fiche-demarree-le')).toHaveCount(0);
    await ctxAdmin.close();

    // un coureur et un bénévole sont redirigés vers /acces-refuse, un anonyme vers la connexion
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    for (const [pseudo, mdp] of [[bruno, MOT_DE_PASSE], [benevole, MOT_DE_PASSE_BENEVOLE_CREE]]) {
      const ctx = await browser.newContext();
      const p = await ctx.newPage();
      await connecter(p, pseudo, mdp);
      await expect(p.getByTestId('entete-pseudo')).toHaveText(pseudo);
      await p.goto(urlFiche(course.id));
      await expect(p).toHaveURL(/\/acces-refuse$/);
      await ctx.close();
    }
    const anonyme = await browser.newContext();
    const pageAnonyme = await anonyme.newPage();
    await pageAnonyme.goto(urlFiche(course.id));
    await expect(pageAnonyme).toHaveURL(new RegExp(`/connexion\\?retour=%2Fadministration%2Fcourses%2F${course.id}$`));
    await anonyme.close();
  });

  test('CA18 - la section Démarrage tient dans la carte à 320 px (texte à 200 % compris), focus visible et cibles de 44 px', async ({ page, playwright }) => {
    const bruno = await creerCoureur(playwright, 'bruno');
    const course = await creerCourse(playwright, 0, [bruno]);

    await page.setViewportSize({ width: 320, height: 640 });
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await ouvrirFiche(page, course);

    const section = page.getByRole('region', { name: 'Démarrage' });
    const carte = page.locator('section.carte');
    const verifierDansLaCarte = async (contexte: string) => {
      const debordements = await carte.evaluate((conteneur) => {
        const r = conteneur.getBoundingClientRect();
        return Array.from(conteneur.querySelectorAll('*'))
          .filter((e) => e.checkVisibility())
          .filter((e) => {
            const b = e.getBoundingClientRect();
            return b.width > 1 && b.height > 1 && (b.left < r.left - 0.5 || b.right > r.right + 0.5);
          })
          .map((e) => `${e.tagName.toLowerCase()}[${e.getAttribute('data-testid') ?? e.className}]`);
      });
      expect(debordements, contexte).toEqual([]);
      const defilement = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(defilement, `${contexte} : défilement horizontal`).toBeLessThanOrEqual(0);
    };
    const verifierCible = async (bouton: Locator, nom: string) => {
      const b = await bouton.boundingBox();
      expect(b, nom).not.toBeNull();
      expect(b!.height, `${nom} : hauteur`).toBeGreaterThanOrEqual(44);
      expect(b!.width, `${nom} : largeur`).toBeGreaterThanOrEqual(44);
    };
    const verifierFocus = async (bouton: Locator, nom: string) => {
      await bouton.focus();
      await expect(bouton).toBeFocused();
      const contour = await bouton.evaluate((e) => {
        const s = getComputedStyle(e);
        return { style: s.outlineStyle, largeur: parseFloat(s.outlineWidth) };
      });
      expect(contour.style, `${nom} : contour de focus`).not.toBe('none');
      expect(contour.largeur, `${nom} : épaisseur du contour`).toBeGreaterThan(0);
    };

    await expect(section.getByTestId('fiche-aide-demarrage')).toBeVisible();
    await verifierDansLaCarte('320 px, bouton');
    await verifierCible(page.getByTestId('fiche-bouton-demarrer'), 'Démarrer la course');
    await verifierFocus(page.getByTestId('fiche-bouton-demarrer'), 'Démarrer la course');

    await page.getByTestId('fiche-bouton-demarrer').click();
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toBeVisible();
    await verifierDansLaCarte('320 px, confirmation');
    await verifierCible(page.getByTestId('fiche-bouton-confirmer-demarrage'), 'Démarrer maintenant');
    await verifierCible(page.getByTestId('fiche-bouton-annuler-demarrage'), 'Annuler');
    await verifierFocus(page.getByTestId('fiche-bouton-confirmer-demarrage'), 'Démarrer maintenant');
    await verifierFocus(page.getByTestId('fiche-bouton-annuler-demarrage'), 'Annuler');

    await page.addStyleTag({ content: AGRANDIR });
    await expect(page.getByTestId('fiche-confirmation-demarrage')).toBeVisible();
    await verifierDansLaCarte('320 px, texte à 200 %');
    await verifierCible(page.getByTestId('fiche-bouton-confirmer-demarrage'), 'Démarrer maintenant (200 %)');

    // l'ordre de tabulation : bouton de démarrage avant les cases des bénévoles
    await page.getByTestId('fiche-bouton-annuler-demarrage').click();
    const ordre = await page.evaluate(() => {
      const tous = Array.from(document.querySelectorAll<HTMLElement>('button, a[href], input, select, textarea'));
      const position = (id: string) => tous.findIndex((e) => e.getAttribute('data-testid') === id);
      return { demarrer: position('fiche-bouton-demarrer'), enregistrer: position('fiche-bouton-enregistrer') };
    });
    expect(ordre.demarrer).toBeGreaterThanOrEqual(0);
    expect(ordre.demarrer).toBeLessThan(ordre.enregistrer);
  });
});

async function jeton(request: APIRequestContext): Promise<string> {
  const csrf = await request.get('/api/csrf');
  expect(csrf.status()).toBe(204);
  const valeur = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(valeur).toBeTruthy();
  return valeur!;
}
