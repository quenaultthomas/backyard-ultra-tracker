import { expect, test, type APIRequestContext, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  affecterBenevolesParApi,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { seDesinscrireParApi, sinscrireParApi } from './aide-coureur';

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

async function creerCourse(playwright: PlaywrightLib, prefixe: string, max: number): Promise<{ id: string; nom: string }> {
  const nom = nomCourseUnique(prefixe);
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, nombreMaxParticipants: max })));
  return { id: c['id'] as string, nom };
}

/** Crée un coureur par l'API puis l'inscrit à la course ; renvoie son pseudo et l'identifiant d'inscription. */
async function inscrireCoureur(playwright: PlaywrightLib, prefixe: string, courseId: string): Promise<{ pseudo: string; inscriptionId: string; dossard: number }> {
  const pseudo = pseudoUnique(prefixe);
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  const ins = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, courseId));
  return { pseudo, inscriptionId: ins.id, dossard: ins.dossard };
}

async function connecter(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

const urlFiche = (id: string) => `/administration/courses/${id}`;
const motifInscrits = (id: string) => `**/api/administration/courses/${id}/inscriptions`;
const probleme = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code }),
});

async function verifierLignes(page: Page, attendues: Array<[string, string]>): Promise<void> {
  const lignes = page.getByTestId('fiche-inscrits-ligne');
  await expect(lignes).toHaveCount(attendues.length);
  for (const [i, [dossard, pseudo]] of attendues.entries()) {
    await expect(lignes.nth(i).getByTestId('fiche-inscrit-dossard')).toHaveText(dossard);
    await expect(lignes.nth(i).getByTestId('fiche-inscrit-pseudo')).toHaveText(pseudo);
    await expect(lignes.nth(i).getByTestId('fiche-inscrit-statut')).toHaveText('En course');
  }
}

test.describe('Inscrits d\'une course (fiche admin)', () => {
  test('CA11 - l\'admin voit les inscrits, les places restantes, et la liste suit les inscriptions', async ({ page, browser, playwright }) => {
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    const courseA = await creerCourse(playwright, 'Course A', 3);
    const courseB = await creerCourse(playwright, 'Course B', 7);
    const zoe = await inscrireCoureur(playwright, 'Zoé', courseA.id);
    const alain = await inscrireCoureur(playwright, 'Alain', courseA.id);
    const marie = await inscrireCoureur(playwright, 'Marie', courseA.id);
    expect([zoe.dossard, alain.dossard, marie.dossard]).toEqual([1, 2, 3]);

    await connecter(page, admin, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto(urlFiche(courseA.id));
    await expect(page.getByTestId('fiche-titre')).toHaveText(courseA.nom);

    const section = page.getByTestId('fiche-inscrits');
    await expect(section).toBeVisible();
    await expect(page.getByTestId('fiche-inscrits-compteur')).toHaveText('3 inscrits sur 3');
    await expect(page.getByTestId('fiche-inscrits-places')).toHaveText('Course complète');
    await verifierLignes(page, [['1', zoe.pseudo], ['2', alain.pseudo], ['3', marie.pseudo]]);
    // Les bénévoles de la fiche sont affichés en même temps que la liste
    await expect(page.getByTestId('fiche-benevoles-compteur')).toBeVisible();
    // Ni jeton ni QR dans le DOM
    await expect(section.locator('canvas, img, svg')).toHaveCount(0);
    const html = (await section.innerHTML()).toLowerCase();
    expect(html).not.toContain('jeton');
    expect(html).not.toContain('qr');

    // Désinscription d'Alain puis F5
    expect(await avecApi(playwright, (ctx) => seDesinscrireParApi(ctx, alain.pseudo, alain.inscriptionId))).toBe(204);
    await page.reload();
    await expect(page.getByTestId('fiche-inscrits-compteur')).toHaveText('2 inscrits sur 3');
    await expect(page.getByTestId('fiche-inscrits-places')).toHaveText('1 place restante');
    await verifierLignes(page, [['1', zoe.pseudo], ['3', marie.pseudo]]);

    // Course sans inscrit
    await page.goto(urlFiche(courseB.id));
    await expect(page.getByTestId('fiche-inscrits-compteur')).toHaveText('0 inscrit sur 7');
    await expect(page.getByTestId('fiche-inscrits-places')).toHaveText('7 places restantes');
    await expect(page.getByTestId('fiche-inscrits-vide')).toHaveText('Aucun inscrit pour le moment.');
    await expect(page.getByTestId('fiche-inscrits-ligne')).toHaveCount(0);

    // Superadmin : même affichage
    const ctx = await browser.newContext();
    const p = await ctx.newPage();
    await connecterAdminMaster(p);
    await expect(p).toHaveURL(/\/administration$/);
    await p.goto(urlFiche(courseA.id));
    await expect(p.getByTestId('fiche-inscrits-compteur')).toHaveText('2 inscrits sur 3');
    await expect(p.getByTestId('fiche-inscrits-places')).toHaveText('1 place restante');
    await verifierLignes(p, [['1', zoe.pseudo], ['3', marie.pseudo]]);
    await ctx.close();
  });

  test('CA12 - pseudo en texte brut, erreurs de la section, accès et course inconnue', async ({ page, browser, request, playwright }) => {
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    const course = await creerCourse(playwright, 'Course A', 5);
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [benevole]));
    // Pseudo avec accents : `<` et `>` sont refusés par Pseudo (lettres, chiffres, « . », « _ », « - »)
    const accentue = await inscrireCoureur(playwright, 'Élodie-Ça', course.id);

    await connecter(page, admin, MOT_DE_PASSE_ADMIN_CREE);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto(urlFiche(course.id));
    await verifierLignes(page, [['1', accentue.pseudo]]);
    await expect(page.getByTestId('fiche-inscrit-pseudo').locator('*')).toHaveCount(0);
    await expect(page.getByTestId('fiche-inscrits').locator('b')).toHaveCount(0);

    // 500 : erreur dans la section, le reste de la fiche reste utilisable
    await page.route(motifInscrits(course.id), (route) => route.fulfill(probleme(500, 'ERREUR_INTERNE')));
    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-inscrits-erreur')).toHaveText('Impossible de charger les inscrits. Réessayez plus tard.');
    await expect(page.getByTestId('fiche-titre')).toHaveText(course.nom);
    await expect(page.getByTestId('fiche-statut')).toBeVisible();
    await expect(page.getByLabel(benevole, { exact: true })).toBeChecked();
    await expect(page.getByTestId('fiche-bouton-enregistrer')).toBeEnabled();

    // 403 puis 401
    await page.unroute(motifInscrits(course.id));
    await page.route(motifInscrits(course.id), (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await page.goto(urlFiche(course.id));
    await expect(page).toHaveURL(/\/acces-refuse$/);

    await page.unroute(motifInscrits(course.id));
    await page.route(motifInscrits(course.id), (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await page.goto(urlFiche(course.id));
    await expect(page).toHaveURL(new RegExp(`/connexion\\?retour=%2Fadministration%2Fcourses%2F${course.id}$`));

    // Course inconnue : message existant, pas de section
    const ctx = await browser.newContext();
    const p = await ctx.newPage();
    await connecter(p, admin, MOT_DE_PASSE_ADMIN_CREE);
    await expect(p).toHaveURL(/\/administration$/);
    await p.goto(urlFiche('00000000-0000-4000-8000-000000000000'));
    await expect(p.getByTestId('fiche-erreur-introuvable')).toHaveText("Cette course n'existe plus.");
    await expect(p.getByTestId('fiche-inscrits')).toHaveCount(0);
    await ctx.close();

    // Coureur : redirigé par la garde existante
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);
    const ctxC = await browser.newContext();
    const pc = await ctxC.newPage();
    await connecter(pc, coureur, MOT_DE_PASSE);
    await expect(pc.getByTestId('entete-pseudo')).toHaveText(coureur);
    await pc.goto(urlFiche(course.id));
    await expect(pc).toHaveURL(/\/acces-refuse$/);
    await expect(pc.getByTestId('fiche-inscrits')).toHaveCount(0);
    await ctxC.close();
  });
});
