import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import jsQR from 'jsqr';
import { PNG } from 'pngjs';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
  connecterAdminMaster,
  connecterParApi,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { lireJetonQrEnBase, placerStatutCourseEnBase, placerStatutInscriptionEnBase, sinscrireParApi } from './aide-coureur';
import { installerCameraSimulee, presenterQr } from './aide-camera';
import { lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const JETON = /^[A-Za-z0-9_-]{43}$/;

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

interface CoureurInscrit {
  pseudo: string;
  inscriptionId: string;
  dossard: number;
  jeton: string;
}

interface CourseAvecCoureurs {
  id: string;
  nom: string;
  coureurs: CoureurInscrit[];
}

/** Course `EN_PREPARATION` et `n` coureurs inscrits par l'API (dossards 1 à n). */
async function preparerCourse(playwright: PlaywrightLib, n = 3, pseudos: string[] = []): Promise<CourseAvecCoureurs> {
  const nom = nomCourseUnique('Course A');
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom })));
  const id = c['id'] as string;
  const coureurs: CoureurInscrit[] = [];
  for (let i = 0; i < n; i++) {
    const pseudo = pseudos[i] ?? pseudoUnique('coureur');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
    const ins = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, id));
    coureurs.push({ pseudo, inscriptionId: ins.id, dossard: ins.dossard, jeton: lireJetonQrEnBase(ins.id) });
  }
  expect(coureurs.map((x) => x.dossard)).toEqual(Array.from({ length: n }, (_, i) => i + 1));
  for (const x of coureurs) expect(x.jeton).toMatch(JETON);
  return { id, nom, coureurs };
}

async function creerEtConnecterAdmin(page: Page, playwright: PlaywrightLib): Promise<string> {
  const admin = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
  await ouvrirConnexion(page);
  await saisir(page, admin, MOT_DE_PASSE_ADMIN_CREE);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(admin);
  return admin;
}

const urlFiche = (courseId: string) => `/administration/courses/${courseId}`;
const urlDossard = (courseId: string, inscriptionId: string) => `${urlFiche(courseId)}/inscriptions/${inscriptionId}/dossard`;
const estLectureDossard = (r: Request) => r.method() === 'GET' && /\/api\/administration\/courses\/[^/]+\/inscriptions\/[^/]+\/dossard$/.test(new URL(r.url()).pathname);

async function ouvrirDossard(page: Page, courseId: string, c: CoureurInscrit): Promise<void> {
  await page.goto(urlDossard(courseId, c.inscriptionId));
  await expect(page.getByTestId('dossard-numero')).toHaveText(String(c.dossard));
}

/** Capture du QR et décodage par une vraie bibliothèque de lecture ; renvoie le texte lu. */
async function decoderQr(qr: Locator): Promise<string> {
  await expect(qr).toBeVisible();
  const png = PNG.sync.read(await qr.screenshot());
  const lu = jsQR(new Uint8ClampedArray(png.data), png.width, png.height);
  expect(lu, 'le QR doit être décodable').not.toBeNull();
  return lu!.data;
}

/** Un pixel de la zone de silence (4 px du coin, hors frange de sous-pixel de la capture) est blanc. */
async function exigerFondBlanc(qr: Locator): Promise<void> {
  const png = PNG.sync.read(await qr.screenshot());
  const i = (4 * png.width + 4) * 4;
  expect([png.data[i], png.data[i + 1], png.data[i + 2]]).toEqual([255, 255, 255]);
}

/** Le jeton n'est nulle part dans le navigateur : DOM, attributs, URL, stockages, état de l'historique. */
async function exigerJetonAbsent(page: Page, jeton: string): Promise<void> {
  expect(await page.content()).not.toContain(jeton);
  expect(await page.evaluate(() => document.body.innerText)).not.toContain(jeton);
  expect(page.url()).not.toContain(jeton);
  expect(
    await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }, history.state])),
  ).not.toContain(jeton);
}

async function connecterEnPage(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}

const probleme = (status: number, code?: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify(code ? { type: 'about:blank', title: 'Simulé', status, code } : { type: 'about:blank', title: 'Simulé', status }),
});

const MOTIF_DOSSARD = '**/api/administration/courses/*/inscriptions/*/dossard';

/** Supprime le compte du coureur par l'API (session du coureur, CSRF relu). */
async function supprimerCompteParApi(request: APIRequestContext, pseudo: string): Promise<void> {
  await connecterParApi(request, pseudo, MOT_DE_PASSE);
  await request.get('/api/csrf');
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  const reponse = await request.post('/api/comptes/moi/suppression', {
    headers: { 'X-XSRF-TOKEN': jeton! },
    data: { motDePasseActuel: MOT_DE_PASSE },
  });
  expect(reponse.status()).toBeLessThan(300);
}

test.describe('Dossard imprimable (admin)', () => {
  test('CA11 - l\'admin ouvre le dossard depuis la fiche : numéro, pseudo, course, QR identique à celui du coureur, jeton jamais visible', async ({ page, browser, playwright }) => {
    const course = await preparerCourse(playwright);
    await creerEtConnecterAdmin(page, playwright);

    await page.goto(urlFiche(course.id));
    const lignes = page.getByTestId('fiche-inscrits-ligne');
    await expect(lignes).toHaveCount(3);
    for (const [i, c] of course.coureurs.entries()) {
      const lien = lignes.nth(i).getByTestId('fiche-inscrit-lien-dossard');
      await expect(lien).toHaveText('Dossard');
      await expect(lien).toHaveAttribute('aria-label', `Dossard du coureur ${c.pseudo}`);
      await expect(lignes.nth(i).getByTestId('fiche-inscrit-dossard')).toHaveText(String(c.dossard));
    }
    const contenuFiche = await page.content();
    for (const c of course.coureurs) expect(contenuFiche).not.toContain(c.jeton);

    const deux = course.coureurs[1];
    await page.evaluate(() => ((window as unknown as Record<string, boolean>)['__spa'] = true));
    const lecture = page.waitForResponse((r) => estLectureDossard(r.request()));
    await lignes.nth(1).getByTestId('fiche-inscrit-lien-dossard').click();
    const reponse = await lecture;
    expect(reponse.status()).toBe(200);
    expect(new URL(reponse.url()).pathname).toBe(`/api${urlDossard(course.id, deux.inscriptionId)}`);
    await expect(page).toHaveURL(new RegExp(`${urlDossard(course.id, deux.inscriptionId)}$`));

    const verifierEcran = async () => {
      await expect(page.getByTestId('dossard-titre')).toHaveText('Dossard');
      await expect(page.getByTestId('dossard-numero')).toHaveText('2');
      await expect(page.getByTestId('dossard-pseudo')).toHaveText(deux.pseudo);
      await expect(page.getByTestId('dossard-course-nom')).toHaveText(course.nom);
      const qr = page.getByTestId('dossard-qr');
      await expect(qr).toBeVisible();
      const boite = (await qr.boundingBox())!;
      expect(boite.width).toBeGreaterThanOrEqual(200);
      expect(boite.width).toBeCloseTo(240, 0);
      expect(boite.height).toBeCloseTo(boite.width, 0);
      await exigerFondBlanc(qr);
      expect(await decoderQr(qr)).toBe(deux.jeton);
      await exigerJetonAbsent(page, deux.jeton);
      await expect(page.getByTestId('dossard-avertissement')).toHaveCount(0);
    };
    await verifierEcran();
    expect(await page.evaluate(() => (window as unknown as Record<string, boolean>)['__spa'])).toBe(true);

    // Le même QR que « Mes inscriptions » du coureur 2.
    const contexteCoureur = await browser.newContext();
    try {
      const pageCoureur = await contexteCoureur.newPage();
      await connecterEnPage(pageCoureur, deux.pseudo, MOT_DE_PASSE);
      await pageCoureur.goto('/coureur/inscriptions');
      expect(await decoderQr(pageCoureur.getByTestId('inscriptions-qr'))).toBe(deux.jeton);
    } finally {
      await contexteCoureur.close();
    }

    // F5 : même affichage, même QR.
    await page.reload();
    await verifierEcran();

    await page.getByTestId('dossard-lien-retour').click();
    await expect(page).toHaveURL(new RegExp(`${urlFiche(course.id)}$`));
    await expect(page.getByTestId('fiche-inscrits-ligne')).toHaveCount(3);
  });

  test('CA11 - le Patron (admin master) suit le même parcours', async ({ page, playwright }) => {
    const course = await preparerCourse(playwright, 2);
    await connecterAdminMaster(page);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(PSEUDO_ADMIN_MASTER);
    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-inscrit-lien-dossard')).toHaveCount(2);
    expect(await page.content()).not.toContain(course.coureurs[0].jeton);
    await page.getByTestId('fiche-inscrits-ligne').nth(0).getByTestId('fiche-inscrit-lien-dossard').click();
    await expect(page).toHaveURL(new RegExp(`${urlDossard(course.id, course.coureurs[0].inscriptionId)}$`));
    await expect(page.getByTestId('dossard-numero')).toHaveText('1');
    await expect(page.getByTestId('dossard-pseudo')).toHaveText(course.coureurs[0].pseudo);
    await expect(page.getByTestId('dossard-course-nom')).toHaveText(course.nom);
    expect(await decoderQr(page.getByTestId('dossard-qr'))).toBe(course.coureurs[0].jeton);
    await exigerJetonAbsent(page, course.coureurs[0].jeton);
  });

  test('CA12 - le QR du dossard sert de jeton de scan, en saisie puis avec la caméra simulée', async ({ page, browser, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [un, deux] = course.coureurs;
    const contexteAdmin = await browser.newContext();
    try {
      const pageAdmin = await contexteAdmin.newPage();
      await creerEtConnecterAdmin(pageAdmin, playwright);
      await pageAdmin.goto(urlDossard(course.id, un.inscriptionId));
      await expect(pageAdmin.getByTestId('dossard-numero')).toHaveText('1');
      const texteUn = await decoderQr(pageAdmin.getByTestId('dossard-qr'));
      await pageAdmin.goto(urlDossard(course.id, deux.inscriptionId));
      await expect(pageAdmin.getByTestId('dossard-numero')).toHaveText('2');
      const texteDeux = await decoderQr(pageAdmin.getByTestId('dossard-qr'));

      await installerCameraSimulee(page);
      await ouvrirEcranDeScan(page, course);
      await scannerParEntree(page, texteUn);
      await verifierSucces(page, 1, un.pseudo, 1);
      expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);

      await page.getByTestId('scan-camera-activer').click();
      await expect(page.getByTestId('scan-camera-etat')).toHaveText('Caméra active');
      await presenterQr(page, texteDeux);
      await expect(page.getByTestId('scan-resultat-dossard')).toHaveText('Dossard 2', { timeout: 5000 });
      await verifierSucces(page, 2, deux.pseudo, 1);
      expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
    } finally {
      await contexteAdmin.close();
    }
  });

  test('CA13 - impression : seule la feuille reste, une page en A4 et en A5, QR de 70 mm, bouton Imprimer appelle window.print', async ({ page, playwright }) => {
    const course = await preparerCourse(playwright, 1);
    const [c] = course.coureurs;
    placerStatutInscriptionEnBase(c.inscriptionId, 'ABANDON');
    await creerEtConnecterAdmin(page, playwright);
    await page.addInitScript(() => {
      const w = window as unknown as { __impressions: number };
      w.__impressions = 0;
      window.print = () => {
        w.__impressions++;
      };
    });
    await ouvrirDossard(page, course.id, c);

    const masquables = ['entete-titre', 'dossard-lien-retour', 'dossard-bouton-imprimer', 'dossard-avertissement'];
    for (const id of masquables) await expect(page.getByTestId(id)).toBeVisible();

    await page.emulateMedia({ media: 'print' });
    for (const id of masquables) await expect(page.getByTestId(id)).toBeHidden();
    for (const id of ['dossard-feuille', 'dossard-numero', 'dossard-pseudo', 'dossard-course-nom', 'dossard-qr']) {
      await expect(page.getByTestId(id)).toBeVisible();
    }
    const qr = page.getByTestId('dossard-qr');
    const boite = (await qr.boundingBox())!;
    expect(boite.width).toBeGreaterThanOrEqual(265);
    expect(boite.height).toBeGreaterThanOrEqual(265);
    expect(await decoderQr(qr)).toBe(c.jeton);
    await exigerFondBlanc(qr);

    for (const format of ['A4', 'A5'] as const) {
      const pdf = await page.pdf({ format });
      const brut = pdf.toString('latin1');
      const pages = brut.match(/\/Type\s*\/Page(?![s\w])/g) ?? [];
      expect(pages, `nombre de pages en ${format}`).toHaveLength(1);
      expect(brut).not.toContain(c.jeton);
      expect(brut).not.toContain('/URI');
      expect(brut).not.toContain('http://');
    }
    expect(await page.getByTestId('dossard-feuille').innerText()).not.toContain(c.jeton);

    await page.emulateMedia({ media: 'screen' });
    for (const id of [...masquables, 'dossard-feuille', 'dossard-numero', 'dossard-pseudo', 'dossard-course-nom', 'dossard-qr']) {
      await expect(page.getByTestId(id)).toBeVisible();
    }

    expect(await page.evaluate(() => (window as unknown as { __impressions: number }).__impressions)).toBe(0);
    await page.getByTestId('dossard-bouton-imprimer').click();
    await expect.poll(() => page.evaluate(() => (window as unknown as { __impressions: number }).__impressions)).toBe(1);
  });

  test('CA14 - avertissements selon les statuts et pseudo « Coureur anonyme », QR toujours affiché', async ({ page, playwright }) => {
    const course = await preparerCourse(playwright, 3);
    const [, , trois] = course.coureurs;
    await creerEtConnecterAdmin(page, playwright);

    const verifier = async (avertissement: string | null) => {
      await ouvrirDossard(page, course.id, trois);
      if (avertissement === null) await expect(page.getByTestId('dossard-avertissement')).toHaveCount(0);
      else await expect(page.getByTestId('dossard-avertissement')).toHaveText(avertissement);
      await expect(page.getByTestId('dossard-feuille')).toBeVisible();
      expect(await decoderQr(page.getByTestId('dossard-qr'))).toBe(trois.jeton);
      await exigerJetonAbsent(page, trois.jeton);
    };

    await verifier(null); // EN_COURSE sur course EN_PREPARATION
    placerStatutCourseEnBase(course.id, 'EN_COURS');
    await verifier(null);
    placerStatutInscriptionEnBase(trois.inscriptionId, 'ABANDON');
    await verifier('Ce coureur est en abandon : son QR code sera refusé au scan.');
    placerStatutInscriptionEnBase(trois.inscriptionId, 'VAINQUEUR');
    await verifier('Ce coureur est vainqueur : son QR code sera refusé au scan.');
    placerStatutCourseEnBase(course.id, 'TERMINEE');
    await verifier('Cette course est terminée : le QR code ne servira plus.');
    placerStatutInscriptionEnBase(trois.inscriptionId, 'ABANDON');
    await verifier('Cette course est terminée : le QR code ne servira plus.');
    await expect(page.getByTestId('dossard-avertissement')).toHaveCount(1);

    // Compte supprimé par le coureur pendant que la course est EN_COURS : inscription conservée, pseudo anonymisé.
    const courseB = await preparerCourse(playwright, 1);
    placerStatutCourseEnBase(courseB.id, 'EN_COURS');
    const alice = courseB.coureurs[0];
    await avecApi(playwright, (ctx) => supprimerCompteParApi(ctx, alice.pseudo));
    await ouvrirDossard(page, courseB.id, alice);
    await expect(page.getByTestId('dossard-pseudo')).toHaveText('Coureur anonyme');
    expect(await decoderQr(page.getByTestId('dossard-qr'))).toBe(alice.jeton);
  });

  test('CA15 - erreurs de chargement : 5xx avec réessai, 404 reconnus par leur code, 401, 403, QR impossible à dessiner', async ({ page, playwright }) => {
    const course = await preparerCourse(playwright, 1);
    const [c] = course.coureurs;
    const admin = await creerEtConnecterAdmin(page, playwright);
    const url = urlDossard(course.id, c.inscriptionId);

    const aucunDossard = async () => {
      for (const id of ['dossard-numero', 'dossard-qr', 'dossard-feuille']) await expect(page.getByTestId(id)).toHaveCount(0);
    };

    // 500 puis réessai qui aboutit.
    let appels = 0;
    await page.route(MOTIF_DOSSARD, (route) => {
      appels++;
      return appels === 1 ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue();
    });
    await page.goto(url);
    await expect(page.getByTestId('dossard-erreur')).toHaveText('Impossible de charger le dossard. Réessayez plus tard.');
    await aucunDossard();
    await page.getByTestId('dossard-bouton-reessayer').click();
    await expect(page.getByTestId('dossard-numero')).toHaveText('1');
    await expect(page.getByTestId('dossard-erreur')).toHaveCount(0);
    expect(await decoderQr(page.getByTestId('dossard-qr'))).toBe(c.jeton);
    await page.unroute(MOTIF_DOSSARD);

    // 404 sans code reconnu : traité comme une indisponibilité, avec réessai.
    await page.route(MOTIF_DOSSARD, (route) => route.fulfill(probleme(404)));
    await page.goto(url);
    await expect(page.getByTestId('dossard-erreur')).toHaveText('Impossible de charger le dossard. Réessayez plus tard.');
    await expect(page.getByTestId('dossard-bouton-reessayer')).toBeVisible();
    await aucunDossard();
    await page.unroute(MOTIF_DOSSARD);

    // Erreur réseau.
    await page.route(MOTIF_DOSSARD, (route) => route.abort('failed'));
    await page.goto(url);
    await expect(page.getByTestId('dossard-erreur')).toHaveText('Impossible de charger le dossard. Réessayez plus tard.');
    await expect(page.getByTestId('dossard-bouton-reessayer')).toBeVisible();
    await page.unroute(MOTIF_DOSSARD);

    // 404 COURSE_INTROUVABLE : lien vers la liste des courses.
    await page.route(MOTIF_DOSSARD, (route) => route.fulfill(probleme(404, 'COURSE_INTROUVABLE')));
    await page.goto(url);
    await expect(page.getByTestId('dossard-erreur')).toHaveText('Cette course n\'existe plus.');
    await expect(page.getByTestId('dossard-bouton-reessayer')).toHaveCount(0);
    await aucunDossard();
    await expect(page.getByTestId('dossard-lien-retour')).toHaveAttribute('href', '/administration/courses');
    await page.getByTestId('dossard-lien-retour').click();
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await page.unroute(MOTIF_DOSSARD);

    // 404 INSCRIPTION_INTROUVABLE : lien vers la fiche.
    await page.route(MOTIF_DOSSARD, (route) => route.fulfill(probleme(404, 'INSCRIPTION_INTROUVABLE')));
    await page.goto(url);
    await expect(page.getByTestId('dossard-erreur')).toHaveText('Cette inscription n\'existe plus (le coureur s\'est peut-être désinscrit).');
    await aucunDossard();
    await expect(page.getByTestId('dossard-lien-retour')).toHaveAttribute('href', urlFiche(course.id));
    await page.getByTestId('dossard-lien-retour').click();
    await expect(page).toHaveURL(new RegExp(`${urlFiche(course.id)}$`));
    await page.unroute(MOTIF_DOSSARD);

    // 403 ACCES_REFUSE.
    await page.route(MOTIF_DOSSARD, (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await page.goto(url);
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await aucunDossard();
    await page.unroute(MOTIF_DOSSARD);

    // Jeton vide : le numéro et le pseudo restent, pas de repli en texte.
    await page.route(MOTIF_DOSSARD, async (route) => {
      const reponse = await route.fetch();
      const corps = (await reponse.json()) as Record<string, unknown>;
      await route.fulfill({ response: reponse, json: { ...corps, jetonQr: '' } });
    });
    await page.goto(url);
    await expect(page.getByTestId('dossard-numero')).toHaveText('1');
    await expect(page.getByTestId('dossard-pseudo')).toHaveText(c.pseudo);
    await expect(page.getByTestId('dossard-erreur-qr')).toHaveText('Impossible d\'afficher le QR code.');
    await expect(page.getByTestId('dossard-qr')).toHaveCount(0);
    expect(await page.content()).not.toContain(c.jeton);
    await page.unroute(MOTIF_DOSSARD);

    // 401 : connexion avec retour, puis retour sur le dossard après connexion.
    await page.route(MOTIF_DOSSARD, (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await page.goto(url);
    await expect(page).toHaveURL(new RegExp(`/connexion\\?retour=${encodeURIComponent(url).replace(/\./g, '\\.')}$`));
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await aucunDossard();
    await page.unroute(MOTIF_DOSSARD);
    await saisir(page, admin, MOT_DE_PASSE_ADMIN_CREE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(new RegExp(`${url}$`));
    await expect(page.getByTestId('dossard-numero')).toHaveText('1');
  });

  test('CA15 - le bénévole, le coureur et l\'anonyme n\'accèdent pas au dossard', async ({ page, browser, playwright }) => {
    const course = await preparerCourse(playwright, 1);
    const [c] = course.coureurs;
    const url = urlDossard(course.id, c.inscriptionId);
    const benevole = pseudoBenevoleUnique();
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    const autreCoureur = pseudoUnique('coureur');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, autreCoureur));

    // Anonyme.
    await page.goto(url);
    await expect(page).toHaveURL(new RegExp(`/connexion\\?retour=${encodeURIComponent(url).replace(/\./g, '\\.')}$`));
    await expect(page.getByTestId('dossard-numero')).toHaveCount(0);
    await expect(page.getByTestId('dossard-qr')).toHaveCount(0);

    for (const [pseudo, motDePasse] of [
      [benevole, MOT_DE_PASSE_BENEVOLE_CREE],
      [autreCoureur, MOT_DE_PASSE],
      [c.pseudo, MOT_DE_PASSE],
    ]) {
      const contexte = await browser.newContext();
      try {
        const p = await contexte.newPage();
        await connecterEnPage(p, pseudo, motDePasse);
        await p.goto(url);
        await expect(p).toHaveURL(/\/acces-refuse$/);
        await expect(p.getByTestId('dossard-numero')).toHaveCount(0);
        await expect(p.getByTestId('dossard-qr')).toHaveCount(0);
        expect(await p.content()).not.toContain(c.jeton);
      } finally {
        await contexte.close();
      }
    }
  });

  test('CA16 - téléphone 375 px : pas de défilement horizontal, numéro et QR visibles sans défiler, cibles de 44 px, focus visible', async ({ page, playwright }) => {
    const pseudoLong = `Z${Math.random().toString(36).slice(2, 12)}${'x'.repeat(30)}`.slice(0, 30);
    const course = await preparerCourse(playwright, 1, [pseudoLong]);
    const [c] = course.coureurs;
    expect(c.pseudo).toHaveLength(30);
    await page.setViewportSize({ width: 375, height: 667 });
    await creerEtConnecterAdmin(page, playwright);

    // Fiche : lien « Dossard » de 44 px au moins.
    await page.goto(urlFiche(course.id));
    const lien = page.getByTestId('fiche-inscrit-lien-dossard');
    await expect(lien).toBeVisible();
    expect((await lien.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    expect(await sansDefilementHorizontal(page)).toBe(true);

    await lien.click();
    await expect(page.getByTestId('dossard-numero')).toHaveText('1');
    expect(await sansDefilementHorizontal(page)).toBe(true);

    const numero = (await page.getByTestId('dossard-numero').boundingBox())!;
    const qr = page.getByTestId('dossard-qr');
    const boiteQr = (await qr.boundingBox())!;
    expect(numero.y).toBeGreaterThanOrEqual(0);
    expect(boiteQr.y + boiteQr.height).toBeLessThanOrEqual(667);
    expect(boiteQr.width).toBeGreaterThanOrEqual(200);
    expect(boiteQr.x).toBeGreaterThanOrEqual(0);
    expect(boiteQr.x + boiteQr.width).toBeLessThanOrEqual(375);
    await exigerFondBlanc(qr);
    expect(await decoderQr(qr)).toBe(c.jeton);

    const bouton = page.getByTestId('dossard-bouton-imprimer');
    await expect(bouton).toBeVisible();
    expect((await bouton.boundingBox())!.height).toBeGreaterThanOrEqual(44);

    // Pseudo de 30 caractères sans espace : reste dans la feuille.
    await expect(page.getByTestId('dossard-pseudo')).toHaveText(c.pseudo);
    await exigerDansLaFeuille(page, 'dossard-pseudo');

    // Focus visible au clavier sur le bouton.
    await page.locator('body').click({ position: { x: 1, y: 300 } });
    let atteint = false;
    for (let i = 0; i < 15 && !atteint; i++) {
      await page.keyboard.press('Tab');
      atteint = await bouton.evaluate((el) => el === document.activeElement);
    }
    expect(atteint, 'le bouton Imprimer doit être atteignable au clavier').toBe(true);
    await expect(bouton).toBeFocused();
    const contour = await bouton.evaluate((el) => {
      const s = getComputedStyle(el);
      return { style: s.outlineStyle, largeur: parseFloat(s.outlineWidth), ombre: s.boxShadow };
    });
    expect(contour.style !== 'none' && contour.largeur > 0 || contour.ombre !== 'none', `contour de focus : ${JSON.stringify(contour)}`).toBe(true);
  });

  test('CA16 - 320 px, texte normal puis à 200 % : aucun défilement horizontal, pseudo de 30 caractères dans la feuille', async ({ page, playwright }) => {
    const pseudoLong = `Y${Math.random().toString(36).slice(2, 12)}${'w'.repeat(30)}`.slice(0, 30);
    const course = await preparerCourse(playwright, 1, [pseudoLong]);
    const [c] = course.coureurs;
    await page.setViewportSize({ width: 320, height: 568 });
    await creerEtConnecterAdmin(page, playwright);

    await page.goto(urlFiche(course.id));
    await expect(page.getByTestId('fiche-inscrit-lien-dossard')).toBeVisible();
    expect(await sansDefilementHorizontal(page)).toBe(true);

    await ouvrirDossard(page, course.id, c);
    expect(await sansDefilementHorizontal(page)).toBe(true);
    await exigerDansLaFeuille(page, 'dossard-pseudo');
    const boite = (await page.getByTestId('dossard-qr').boundingBox())!;
    expect(boite.width).toBeGreaterThanOrEqual(200);
    expect(await decoderQr(page.getByTestId('dossard-qr'))).toBe(c.jeton);

    await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
    await expect(page.getByTestId('dossard-numero')).toBeVisible();
    expect(await sansDefilementHorizontal(page)).toBe(true);
    await exigerDansLaFeuille(page, 'dossard-pseudo');
    await exigerDansLaFeuille(page, 'dossard-course-nom');
    await expect(page.getByTestId('dossard-bouton-imprimer')).toBeVisible();
  });
});

async function sansDefilementHorizontal(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
}

/** L'élément reste horizontalement dans la feuille. */
async function exigerDansLaFeuille(page: Page, testid: string): Promise<void> {
  const feuille = (await page.getByTestId('dossard-feuille').boundingBox())!;
  const element = (await page.getByTestId(testid).boundingBox())!;
  expect(element.x).toBeGreaterThanOrEqual(feuille.x - 1);
  expect(element.x + element.width).toBeLessThanOrEqual(feuille.x + feuille.width + 1);
  expect(await page.getByTestId(testid).evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
}
