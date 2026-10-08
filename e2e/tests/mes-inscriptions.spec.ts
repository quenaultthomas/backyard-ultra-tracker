import { expect, test, type APIRequestContext, type Browser, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { fermerMenuCompte, ouvrirMenuCompte, ouvrirMenuVisiteur } from './aide-entete';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import jsQR from 'jsqr';
import { PNG } from 'pngjs';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  dateAffichee,
  dateDansJours,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { connecterCoureur, lireJetonQrEnBase, placerStatutCourseEnBase, placerStatutInscriptionEnBase, sinscrireParApi } from './aide-coureur';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const LOGO = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));
const RETOUR = /\/connexion\?retour=%2Fcoureur%2Finscriptions$/;
const estListe = (r: Request) => r.method() === 'GET' && new URL(r.url()).pathname === '/api/coureur/inscriptions';
const JETON = /^[A-Za-z0-9_-]{43}$/;

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

async function creerCourse(playwright: PlaywrightLib, prefixe: string, joursDepuisAujourdhui: number, logo: boolean): Promise<{ id: string; nom: string; date: string }> {
  const nom = nomCourseUnique(prefixe);
  const date = dateDansJours(joursDepuisAujourdhui);
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, date })));
  const id = c['id'] as string;
  if (logo) await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, id, { nom: 'logo.png', type: 'image/png', contenu: LOGO }));
  return { id, nom, date };
}

const ligneIns = (page: Page, nom: string): Locator =>
  page.getByTestId('inscriptions-ligne').filter({ has: page.getByTestId('inscriptions-course-nom').getByText(nom, { exact: true }) });

/** Capture d'écran du QR de la ligne, puis décodage par une vraie bibliothèque de lecture de QR. */
async function decoderQr(ligne: Locator): Promise<string> {
  const qr = ligne.getByTestId('inscriptions-qr');
  await expect(qr).toBeVisible();
  const png = PNG.sync.read(await qr.screenshot());
  const lu = jsQR(new Uint8ClampedArray(png.data), png.width, png.height);
  expect(lu, 'le QR doit être décodable').not.toBeNull();
  return lu!.data;
}

async function ouvrirMesInscriptions(page: Page): Promise<void> {
  await page.goto('/coureur/inscriptions');
  await expect(page.getByTestId('inscriptions-titre')).toBeVisible();
}

async function seConnecterEnTantQue(page: Page, pseudo: string, motDePasse: string, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}

async function ouvrirDansNouveauContexte<T>(browser: Browser, action: (page: Page) => Promise<T>): Promise<T> {
  const contexte = await browser.newContext();
  try {
    return await action(await contexte.newPage());
  } finally {
    await contexte.close();
  }
}

/** Alice inscrite à A (avec logo, J+30) et B (sans logo, J+40) ; B est donc listée avant A (date décroissante). */
async function preparerAlice(playwright: PlaywrightLib) {
  const alice = await creerCoureur(playwright, 'Alice');
  const a = await creerCourse(playwright, 'Course A', 30, true);
  const b = await creerCourse(playwright, 'Course B', 40, false);
  const insA = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, a.id));
  const insB = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, b.id));
  return { alice, a, b, insA, insB };
}

test.describe('Mes inscriptions', () => {
  test('CA8 - le coureur retrouve ses inscriptions, dossards, statuts et QR depuis le lien de l\'en-tête', async ({ page, playwright }) => {
    const { alice, a, b, insA, insB } = await preparerAlice(playwright);
    const jetonA = lireJetonQrEnBase(insA.id);
    const jetonB = lireJetonQrEnBase(insB.id);
    expect(jetonA).toMatch(JETON);
    expect(jetonB).toMatch(JETON);

    await seConnecterEnTantQue(page, alice, MOT_DE_PASSE);
    await expect(page).toHaveURL(/\/$/);
    const reponseListe = page.waitForResponse((r) => estListe(r.request()));
    await ouvrirMenuCompte(page);
    await page.getByTestId('menu-lien-mes-inscriptions').click();
    expect((await reponseListe).status()).toBe(200);
    await expect(page).toHaveURL(/\/coureur\/inscriptions$/);
    await expect(page).toHaveTitle('Mes inscriptions - Backyard Ultra Tracker');
    await expect(page.getByTestId('inscriptions-titre')).toHaveText('Mes inscriptions');

    const verifier = async (): Promise<void> => {
      const lignes = page.getByTestId('inscriptions-ligne');
      await expect(lignes).toHaveCount(2);
      await expect(lignes.nth(0).getByTestId('inscriptions-course-nom')).toHaveText(b.nom);
      await expect(lignes.nth(1).getByTestId('inscriptions-course-nom')).toHaveText(a.nom);
      for (const [l, course] of [[lignes.nth(0), b], [lignes.nth(1), a]] as const) {
        await expect(l.getByTestId('inscriptions-course-date')).toHaveText(dateAffichee(course.date));
        await expect(l.getByTestId('inscriptions-course-statut')).toHaveText('En préparation');
        await expect(l.getByTestId('inscriptions-dossard')).toHaveText('Dossard 1');
        await expect(l.getByTestId('inscriptions-statut')).toHaveText('En course');
        await expect(l.getByTestId('inscriptions-qr')).toBeVisible();
        const boite = await l.getByTestId('inscriptions-qr').boundingBox();
        expect(boite!.width).toBeGreaterThanOrEqual(200);
        expect(boite!.height).toBeGreaterThanOrEqual(200);
        await expect(l.getByTestId('inscriptions-jeton')).toHaveCount(0);
        await expect(l.getByTestId('inscriptions-bouton-jeton')).toHaveCount(0);
      }
      await expect(lignes.nth(1).getByTestId('inscriptions-course-logo')).toBeVisible();
      await expect(lignes.nth(0).getByTestId('inscriptions-course-logo')).toHaveCount(0);
      await expect(page.getByTestId('inscriptions-jeton')).toHaveCount(0);
      await expect(page.getByTestId('inscriptions-bouton-jeton')).toHaveCount(0);
      await expect(page.getByRole('button', { name: /Afficher le jeton|Masquer le jeton|Copier/i })).toHaveCount(0);
      await expect(page.getByText(/Afficher le jeton|Copier/i)).toHaveCount(0);
      const html = await page.content();
      expect(html, 'le jeton de A ne doit pas figurer dans le DOM').not.toContain(jetonA);
      expect(html, 'le jeton de B ne doit pas figurer dans le DOM').not.toContain(jetonB);
    };
    await verifier();

    await page.reload();
    await verifier();

    await ouvrirMenuCompte(page);
    await page.getByTestId('menu-lien-espace-coureur').click();
    await expect(page).toHaveURL(/\/coureur$/);
    await expect(page.getByTestId('coureur-titre')).toBeVisible();
    await expect(page.getByTestId('inscriptions-titre')).toHaveCount(0);
  });

  test('CA9 - le QR décodé est exactement le jeton lu en base, jamais le dossard, et ne sollicite aucun service externe', async ({ page, playwright }) => {
    const { alice, a, b, insA, insB } = await preparerAlice(playwright);
    const jetonA = lireJetonQrEnBase(insA.id);
    const jetonB = lireJetonQrEnBase(insB.id);
    expect(jetonA).toMatch(JETON);
    expect(jetonB).toMatch(JETON);
    expect(jetonA).not.toBe(jetonB);

    await seConnecterEnTantQue(page, alice, MOT_DE_PASSE);
    const requetes: string[] = [];
    page.on('request', (r) => requetes.push(r.url()));
    await ouvrirMesInscriptions(page);
    await expect(page.getByTestId('inscriptions-ligne')).toHaveCount(2);

    const decodeA = await decoderQr(ligneIns(page, a.nom));
    expect(decodeA).toBe(jetonA);
    expect(decodeA).toHaveLength(43);
    expect(decodeA).not.toBe('1');
    expect(decodeA).not.toContain(a.nom);
    expect(decodeA).not.toContain(alice);
    const decodeB = await decoderQr(ligneIns(page, b.nom));
    expect(decodeB).toBe(jetonB);
    expect(decodeB).not.toBe(decodeA);

    await page.reload();
    await expect(page.getByTestId('inscriptions-ligne')).toHaveCount(2);
    expect(await decoderQr(ligneIns(page, a.nom))).toBe(jetonA);

    const origine = new URL(BASE_URL).origin;
    const externes = requetes.filter((u) => !u.startsWith('data:') && !u.startsWith('blob:') && new URL(u).origin !== origine);
    expect(externes, 'aucune requête externe').toEqual([]);
    const images = requetes.filter((u) => /qr/i.test(new URL(u).pathname) || /\.(png|svg)$/i.test(new URL(u).pathname));
    expect(images, 'aucun endpoint d\'image de QR').toEqual([]);
  });

  test('CA10 - les statuts posés en base se reflètent, les QR restent identiques et le jeton reste invisible', async ({ page, playwright }) => {
    const { alice, a, b, insA, insB } = await preparerAlice(playwright);
    const jetonA = lireJetonQrEnBase(insA.id);
    const jetonB = lireJetonQrEnBase(insB.id);

    await seConnecterEnTantQue(page, alice, MOT_DE_PASSE);
    await ouvrirMesInscriptions(page);
    const la = ligneIns(page, a.nom);
    const lb = ligneIns(page, b.nom);
    await expect(la).toBeVisible();

    const verifierJetonInvisible = async (): Promise<void> => {
      await expect(page.getByTestId('inscriptions-jeton')).toHaveCount(0);
      await expect(page.getByTestId('inscriptions-bouton-jeton')).toHaveCount(0);
      const html = await page.content();
      expect(html).not.toContain(jetonA);
      expect(html).not.toContain(jetonB);
    };
    await verifierJetonInvisible();

    placerStatutCourseEnBase(a.id, 'EN_COURS');
    placerStatutInscriptionEnBase(insB.id, 'ABANDON');
    await page.reload();
    await expect(page.getByTestId('inscriptions-ligne')).toHaveCount(2);
    await expect(la.getByTestId('inscriptions-course-statut')).toHaveText('En cours');
    await expect(lb.getByTestId('inscriptions-statut')).toHaveText('Abandon');
    expect(await decoderQr(la)).toBe(jetonA);
    expect(await decoderQr(lb)).toBe(jetonB);
    await verifierJetonInvisible();

    placerStatutInscriptionEnBase(insB.id, 'VAINQUEUR');
    await page.reload();
    await expect(lb.getByTestId('inscriptions-statut')).toHaveText('Vainqueur');
    expect(await decoderQr(lb)).toBe(jetonB);
    expect(await decoderQr(la)).toBe(jetonA);
    await verifierJetonInvisible();
  });

  test('CA11 - chaque coureur ne voit que ses inscriptions, l\'état vide est guidé et une course supprimée disparaît', async ({ browser, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const bruno = await creerCoureur(playwright, 'Bruno');
    const chloe = await creerCoureur(playwright, 'Chloé');
    const a = await creerCourse(playwright, 'Course A', 30, true);
    const b = await creerCourse(playwright, 'Course B', 40, false);
    const insAliceA = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, a.id));
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, b.id));
    const insBrunoA = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, bruno, a.id));
    const jetonAliceA = lireJetonQrEnBase(insAliceA.id);
    const jetonBrunoA = lireJetonQrEnBase(insBrunoA.id);
    expect(jetonAliceA).not.toBe(jetonBrunoA);

    const ctxAlice = await browser.newContext();
    const ctxBruno = await browser.newContext();
    const ctxChloe = await browser.newContext();
    const pageAlice = await ctxAlice.newPage();
    const pageBruno = await ctxBruno.newPage();
    const pageChloe = await ctxChloe.newPage();
    try {
      await seConnecterEnTantQue(pageAlice, alice, MOT_DE_PASSE);
      await seConnecterEnTantQue(pageBruno, bruno, MOT_DE_PASSE);
      await seConnecterEnTantQue(pageChloe, chloe, MOT_DE_PASSE);
      await ouvrirMesInscriptions(pageAlice);
      await ouvrirMesInscriptions(pageBruno);
      await ouvrirMesInscriptions(pageChloe);

      await expect(pageAlice.getByTestId('inscriptions-ligne')).toHaveCount(2);
      await expect(pageBruno.getByTestId('inscriptions-ligne')).toHaveCount(1);
      await expect(ligneIns(pageBruno, b.nom)).toHaveCount(0);
      await expect(ligneIns(pageBruno, a.nom).getByTestId('inscriptions-dossard')).toHaveText('Dossard 2');
      expect(await decoderQr(ligneIns(pageAlice, a.nom))).toBe(jetonAliceA);
      expect(await decoderQr(ligneIns(pageBruno, a.nom))).toBe(jetonBrunoA);

      await expect(pageChloe.getByTestId('inscriptions-vide')).toHaveText("Vous n'êtes inscrit à aucune course.");
      await expect(pageChloe.getByTestId('inscriptions-ligne')).toHaveCount(0);
      await pageChloe.getByTestId('inscriptions-lien-courses-ouvertes').click();
      await expect(pageChloe).toHaveURL(/\/coureur$/);
      await expect(pageChloe.getByTestId('coureur-titre')).toBeVisible();

      await ouvrirDansNouveauContexte(browser, async (pagePatron) => {
        await connecterAdminMaster(pagePatron);
        await expect(pagePatron).toHaveURL(/\/administration$/);
        await pagePatron.goto('/administration/courses');
        await expect(pagePatron.getByTestId('liste-courses')).toBeVisible();
        const l = pagePatron.getByTestId('ligne-course').filter({ has: pagePatron.getByTestId('course-nom').getByText(a.nom, { exact: true }) });
        await l.getByTestId('course-bouton-supprimer').click();
        const suppression = pagePatron.waitForResponse((r) => r.request().method() === 'DELETE');
        await l.getByTestId('course-bouton-confirmer-suppression').click();
        expect((await suppression).status()).toBe(204);
      });

      await pageAlice.reload();
      await expect(pageAlice.getByTestId('inscriptions-ligne')).toHaveCount(1);
      await expect(ligneIns(pageAlice, a.nom)).toHaveCount(0);
      await expect(ligneIns(pageAlice, b.nom)).toBeVisible();
      await pageBruno.reload();
      await expect(pageBruno.getByTestId('inscriptions-vide')).toBeVisible();
    } finally {
      await Promise.all([ctxAlice.close(), ctxBruno.close(), ctxChloe.close()]);
    }
  });

  test('CA12 - accès réservé au coureur, lien d\'en-tête, erreurs de chargement et QR impossible', async ({ page, browser, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const nadia = pseudoAdminUnique();
    const leo = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, leo));
    const course = await creerCourse(playwright, 'Course A', 30, false);
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, course.id));

    // Anonyme : pas de lien, redirection puis retour sur l'écran après connexion.
    await page.goto('/');
    await expect(page.getByTestId('bouton-menu')).toBeVisible();
    await expect(page.getByTestId('menu-visiteur')).toHaveCount(1);
    await expect(page.getByTestId('menu-lien-mes-inscriptions')).toHaveCount(0);
    await page.goto('/coureur/inscriptions');
    await expect(page).toHaveURL(RETOUR);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await saisir(page, alice, MOT_DE_PASSE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/coureur\/inscriptions$/);
    await expect(page.getByTestId('inscriptions-titre')).toBeVisible();
    await ouvrirMenuCompte(page);
    await expect(page.getByTestId('menu-lien-mes-inscriptions')).toBeVisible();
    await expect(page.getByTestId('menu-lien-espace-coureur')).toBeVisible();
    await fermerMenuCompte(page);

    // Autres rôles : accès refusé, pas de lien.
    const roles: Array<[string, string, boolean]> = [
      [leo, MOT_DE_PASSE_BENEVOLE_CREE, false],
      [nadia, MOT_DE_PASSE_ADMIN_CREE, false],
    ];
    for (const [pseudo, motDePasse] of roles) {
      await ouvrirDansNouveauContexte(browser, async (p) => {
        await seConnecterEnTantQue(p, pseudo, motDePasse);
        await expect(p.getByTestId('menu-lien-mes-inscriptions')).toHaveCount(0);
        await expect(p.getByTestId('menu-lien-espace-coureur')).toHaveCount(0);
        await p.goto('/coureur/inscriptions');
        await expect(p).toHaveURL(/\/acces-refuse$/);
        await expect(p.getByTestId('titre-acces-refuse')).toBeVisible();
      });
    }
    await ouvrirDansNouveauContexte(browser, async (p) => {
      await connecterAdminMaster(p);
      await ouvrirMenuCompte(p);
      await expect(p.getByTestId('entete-pseudo')).toBeVisible();
      await fermerMenuCompte(p);
      await expect(p.getByTestId('menu-lien-mes-inscriptions')).toHaveCount(0);
      await p.goto('/coureur/inscriptions');
      await expect(p).toHaveURL(/\/acces-refuse$/);
    });

    // Erreurs simulées sur la lecture, session d'Alice.
    const simuler = async (status: number, corps: unknown): Promise<void> => {
      await page.unroute('**/api/coureur/inscriptions');
      await page.route('**/api/coureur/inscriptions', (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(corps) })
          : route.fallback(),
      );
    };

    await simuler(500, { title: 'Erreur', status: 500, detail: 'Erreur interne', code: 'ERREUR_INTERNE' });
    await page.goto('/coureur/inscriptions');
    await expect(page.getByTestId('inscriptions-erreur-chargement')).toHaveText('Impossible de charger vos inscriptions. Réessayez plus tard.');

    await simuler(200, []);
    await page.goto('/coureur/inscriptions');
    await expect(page.getByTestId('inscriptions-vide')).toBeVisible();

    await simuler(200, [
      { id: '00000000-0000-0000-0000-000000000001', courseId: '00000000-0000-0000-0000-000000000002', courseNom: 'Course simulée', courseDate: '2026-10-10', courseStatut: 'EN_PREPARATION', logoUrl: null, dossard: 7, statut: 'EN_COURSE', jetonQr: '' },
    ]);
    await page.goto('/coureur/inscriptions');
    await expect(page.getByTestId('inscriptions-erreur-qr')).toHaveText("Impossible d'afficher le QR code.");
    await expect(page.getByTestId('inscriptions-dossard')).toHaveText('Dossard 7');
    await expect(page.getByTestId('inscriptions-qr')).toHaveCount(0);
    await expect(page.getByTestId('inscriptions-jeton')).toHaveCount(0);
    await expect(page.getByTestId('inscriptions-bouton-jeton')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /jeton|Copier/i })).toHaveCount(0);

    await simuler(403, { title: 'Accès refusé', status: 403, detail: 'Accès refusé', code: 'ACCES_REFUSE' });
    await page.goto('/coureur/inscriptions');
    await expect(page).toHaveURL(/\/acces-refuse$/);

    await simuler(401, { title: 'Non authentifié', status: 401, detail: 'Non authentifié', code: 'NON_AUTHENTIFIE' });
    await page.goto('/coureur/inscriptions');
    await expect(page).toHaveURL(RETOUR);
  });
});
