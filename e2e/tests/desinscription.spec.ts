import { expect, test, type APIRequestContext, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import jsQR from 'jsqr';
import { PNG } from 'pngjs';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  courseDeReference,
  creerCourseParApi,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  supprimerCourseParApi,
} from './aide-admin';
import {
  connecterCoureur,
  inscriptionExisteEnBase,
  lireJetonQrEnBase,
  ligneCoureur,
  placerStatutCourseEnBase,
  seDesinscrireParApi,
  sinscrireParApi,
} from './aide-coureur';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const LOGO = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));
const RETOUR = /\/connexion\?retour=%2Fcoureur%2Finscriptions$/;
const estSuppression = (r: Request) => r.method() === 'DELETE' && /^\/api\/coureur\/inscriptions\/[^/]+$/.test(new URL(r.url()).pathname);
const estCsrf = (r: Request) => new URL(r.url()).pathname === '/api/csrf';
const estListe = (r: Request) => r.method() === 'GET' && new URL(r.url()).pathname === '/api/coureur/inscriptions';

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

async function creerCourse(playwright: PlaywrightLib, prefixe: string, options: { logo?: boolean; max?: number } = {}): Promise<{ id: string; nom: string }> {
  const nom = nomCourseUnique(prefixe);
  const c = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference({ nom, nombreMaxParticipants: options.max ?? 50 })));
  const id = c['id'] as string;
  if (options.logo) await avecApi(playwright, (ctx) => envoyerLogoParApi(ctx, id, { nom: 'logo.png', type: 'image/png', contenu: LOGO }));
  return { id, nom };
}

const ligneIns = (page: Page, nom: string): Locator =>
  page.getByTestId('inscriptions-ligne').filter({ has: page.getByTestId('inscriptions-course-nom').getByText(nom, { exact: true }) });

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

async function seConnecter(page: Page, pseudo: string): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, MOT_DE_PASSE);
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
}

/** Connecte le coureur puis ouvre « Mes inscriptions ». */
async function ouvrirMesInscriptionsEnTantQue(page: Page, pseudo: string): Promise<void> {
  await seConnecter(page, pseudo);
  await ouvrirMesInscriptions(page);
}

async function ouvrirEtConfirmer(ligne: Locator): Promise<void> {
  await ligne.getByTestId('inscriptions-bouton-desinscrire').click();
  await expect(ligne.getByTestId('inscriptions-confirmation-desinscription')).toBeVisible();
  await ligne.getByTestId('inscriptions-bouton-confirmer-desinscription').click();
}

/** Remplace la réponse du `DELETE` de désinscription. */
async function intercepterSuppression(page: Page, status: number, code: string | null, nombreFois = Infinity): Promise<void> {
  let restant = nombreFois;
  await page.route(
    (url) => /^\/api\/coureur\/inscriptions\/[^/]+$/.test(url.pathname),
    async (route) => {
      if (route.request().method() !== 'DELETE' || restant <= 0) return route.fallback();
      restant--;
      await route.fulfill({
        status,
        contentType: 'application/problem+json',
        body: code ? JSON.stringify({ type: 'about:blank', title: 'Erreur simulée', status, detail: 'Erreur simulée', code }) : '',
      });
    },
  );
}

async function preparerAliceAB(playwright: PlaywrightLib) {
  const alice = await creerCoureur(playwright, 'Alice');
  const a = await creerCourse(playwright, 'Course A', { logo: true });
  const b = await creerCourse(playwright, 'Course B');
  const insA = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, a.id));
  const insB = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, b.id));
  return { alice, a, b, insA, insB };
}

test.describe('Se désinscrire', () => {
  test('CA12 - le coureur annule puis confirme sa désinscription, la ligne disparaît sans rechargement', async ({ page, playwright }) => {
    const { alice, a, b, insA, insB } = await preparerAliceAB(playwright);
    const jetonA = lireJetonQrEnBase(insA.id);
    const jetonB = lireJetonQrEnBase(insB.id);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    await expect(page.getByTestId('inscriptions-ligne')).toHaveCount(2);
    for (const l of [ligneIns(page, a.nom), ligneIns(page, b.nom)]) {
      await expect(l.getByTestId('inscriptions-bouton-desinscrire')).toHaveText('Se désinscrire');
    }
    expect(await decoderQr(ligneIns(page, b.nom))).toBe(jetonB);

    const suppressions: string[] = [];
    const csrfs: string[] = [];
    page.on('request', (r) => {
      if (estSuppression(r)) suppressions.push(r.url());
      if (estCsrf(r)) csrfs.push(r.url());
    });
    await page.evaluate(() => ((window as unknown as Record<string, unknown>)['__marqueur'] = 'sans-rechargement'));

    // ouverture : aucune requête de suppression
    const la = ligneIns(page, a.nom);
    await la.getByTestId('inscriptions-bouton-desinscrire').click();
    const confirmation = la.getByTestId('inscriptions-confirmation-desinscription');
    await expect(confirmation).toContainText(`Se désinscrire de ${a.nom} ? Votre dossard 1 et votre QR code seront supprimés.`);
    await expect(confirmation).toContainText('Si vous vous réinscrivez, vous recevrez un nouveau dossard et un nouveau QR code.');
    await expect(confirmation.getByTestId('inscriptions-bouton-confirmer-desinscription')).toHaveText('Se désinscrire');
    await expect(confirmation.getByTestId('inscriptions-bouton-annuler-desinscription')).toHaveText('Annuler');
    expect(suppressions).toHaveLength(0);

    // annulation
    await confirmation.getByTestId('inscriptions-bouton-annuler-desinscription').click();
    await expect(page.getByTestId('inscriptions-confirmation-desinscription')).toHaveCount(0);
    await expect(page.getByTestId('inscriptions-ligne')).toHaveCount(2);
    expect(suppressions).toHaveLength(0);
    expect(inscriptionExisteEnBase(insA.id)).toBe(true);

    // une seule confirmation ouverte à la fois
    await la.getByTestId('inscriptions-bouton-desinscrire').click();
    await ligneIns(page, b.nom).getByTestId('inscriptions-bouton-desinscrire').click();
    await expect(page.getByTestId('inscriptions-confirmation-desinscription')).toHaveCount(1);
    await expect(ligneIns(page, b.nom).getByTestId('inscriptions-confirmation-desinscription')).toBeVisible();
    await ligneIns(page, b.nom).getByTestId('inscriptions-bouton-annuler-desinscription').click();

    // confirmation (double clic : un seul DELETE)
    await la.getByTestId('inscriptions-bouton-desinscrire').click();
    const csrfAvant = csrfs.length;
    const reponse = page.waitForResponse((r) => estSuppression(r.request()));
    await la.getByTestId('inscriptions-bouton-confirmer-desinscription').dblclick();
    expect((await reponse).status()).toBe(204);
    await expect(page.getByTestId('inscriptions-message-desinscription')).toHaveText(`Vous êtes désinscrit de ${a.nom}.`);
    await expect(page.getByTestId('inscriptions-ligne')).toHaveCount(1);
    await expect(ligneIns(page, a.nom)).toHaveCount(0);
    expect(suppressions).toHaveLength(1);
    expect(csrfs.length - csrfAvant).toBe(1);
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['__marqueur'])).toBe('sans-rechargement');
    expect(inscriptionExisteEnBase(insA.id)).toBe(false);
    expect(inscriptionExisteEnBase(insB.id)).toBe(true);
    expect(await decoderQr(ligneIns(page, b.nom))).toBe(jetonB);
    await expect(ligneIns(page, b.nom).getByTestId('inscriptions-dossard')).toHaveText('Dossard 1');
    const html = await page.content();
    expect(html).not.toContain(jetonA);
    expect(html).not.toContain(jetonB);

    await page.reload();
    await expect(page.getByTestId('inscriptions-ligne')).toHaveCount(1);
    await expect(ligneIns(page, b.nom)).toBeVisible();

    // dernière désinscription : état vide avec le message de succès
    await ouvrirEtConfirmer(ligneIns(page, b.nom));
    await expect(page.getByTestId('inscriptions-vide')).toHaveText("Vous n'êtes inscrit à aucune course.");
    await expect(page.getByTestId('inscriptions-message-desinscription')).toHaveText(`Vous êtes désinscrit de ${b.nom}.`);
    await expect(page.getByTestId('inscriptions-lien-courses-ouvertes')).toBeVisible();
    expect(inscriptionExisteEnBase(insB.id)).toBe(false);
  });

  test('CA13 - la place libérée rouvre la course complète, le dossard n\'est pas réattribué et le QR change', async ({ browser, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const bruno = await creerCoureur(playwright, 'Bruno');
    const chloe = await creerCoureur(playwright, 'Chloé');
    const p = await creerCourse(playwright, 'Course P', { max: 2 });
    const insAlice = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, p.id));
    const insBruno = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, bruno, p.id));
    expect([insAlice.dossard, insBruno.dossard]).toEqual([1, 2]);
    const ancienJeton = lireJetonQrEnBase(insAlice.id);

    const ctxAlice = await browser.newContext();
    const ctxChloe = await browser.newContext();
    const pageAlice = await ctxAlice.newPage();
    const pageChloe = await ctxChloe.newPage();
    try {
      await connecterCoureur(pageChloe, chloe);
      const lc = ligneCoureur(pageChloe, p.nom);
      await expect(lc.getByTestId('coureur-course-complete')).toHaveText('Complète');
      await expect(lc.getByTestId('coureur-bouton-sinscrire')).toBeDisabled();

      await ouvrirMesInscriptionsEnTantQue(pageAlice, alice);
      const reponse = pageAlice.waitForResponse((r) => estSuppression(r.request()));
      await ouvrirEtConfirmer(ligneIns(pageAlice, p.nom));
      expect((await reponse).status()).toBe(204);
      await expect(pageAlice.getByTestId('inscriptions-message-desinscription')).toHaveText(`Vous êtes désinscrit de ${p.nom}.`);

      // Alice : la course propose de nouveau « S'inscrire » (page gardée ouverte)
      await pageAlice.goto('/coureur');
      const la = ligneCoureur(pageAlice, p.nom);
      await expect(la.getByTestId('coureur-dossard')).toHaveCount(0);
      await expect(la.getByTestId('coureur-statut-inscription')).toHaveCount(0);
      await expect(la.getByTestId('coureur-bouton-sinscrire')).toHaveText("S'inscrire");
      await expect(la.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();

      // Chloé : la place est libre, elle s'inscrit avec le dossard 3 (le dossard 1 n'est pas réattribué)
      await pageChloe.reload();
      await expect(lc.getByTestId('coureur-course-complete')).toHaveCount(0);
      await expect(lc.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();
      await lc.getByTestId('coureur-bouton-sinscrire').click();
      await expect(pageChloe.getByTestId('coureur-message-inscription')).toHaveText(`Vous êtes inscrit à ${p.nom} avec le dossard 3.`);
      await expect(lc.getByTestId('coureur-dossard')).toHaveText('Dossard 3');

      // La course est de nouveau pleine : la réinscription d'Alice est refusée
      await la.getByTestId('coureur-bouton-sinscrire').click();
      await expect(pageAlice.getByTestId('coureur-erreur')).toHaveText('Cette course est complète.');
      await expect(la.getByTestId('coureur-dossard')).toHaveCount(0);

      // Bruno se désinscrit : Alice se réinscrit avec le dossard 4 et un nouveau QR
      expect(await avecApi(playwright, (ctx) => seDesinscrireParApi(ctx, bruno, insBruno.id))).toBe(204);
      await pageAlice.reload();
      await expect(la.getByTestId('coureur-bouton-sinscrire')).toBeEnabled();
      await la.getByTestId('coureur-bouton-sinscrire').click();
      await expect(la.getByTestId('coureur-dossard')).toHaveText('Dossard 4');
      await ouvrirMesInscriptions(pageAlice);
      const nouveauJeton = await decoderQr(ligneIns(pageAlice, p.nom));
      expect(nouveauJeton).not.toBe(ancienJeton);
      expect(inscriptionExisteEnBase(insAlice.id)).toBe(false);
    } finally {
      await Promise.all([ctxAlice.close(), ctxChloe.close()]);
    }
  });

  test('CA14 - la course démarre pendant que l\'écran est ouvert : 409, la ligne reste sans bouton', async ({ page, playwright }) => {
    const alice = await creerCoureur(playwright, 'Alice');
    const a = await creerCourse(playwright, 'Course A');
    const ins = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, alice, a.id));
    const jeton = lireJetonQrEnBase(ins.id);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    const l = ligneIns(page, a.nom);
    await expect(l.getByTestId('inscriptions-bouton-desinscrire')).toBeVisible();

    placerStatutCourseEnBase(a.id, 'EN_COURS');
    await l.getByTestId('inscriptions-bouton-desinscrire').click();
    const reponse = page.waitForResponse((r) => estSuppression(r.request()));
    await l.getByTestId('inscriptions-bouton-confirmer-desinscription').click();
    const r = await reponse;
    expect(r.status()).toBe(409);
    expect(r.headers()['content-type']).toContain('application/problem+json');
    await expect(page.getByTestId('inscriptions-erreur-desinscription')).toHaveText('La course a démarré : vous ne pouvez plus vous désinscrire.');
    await expect(page.getByTestId('inscriptions-message-desinscription')).toHaveCount(0);
    await expect(l).toBeVisible();
    await expect(l.getByTestId('inscriptions-qr')).toBeVisible();
    await expect(l.getByTestId('inscriptions-bouton-desinscrire')).toHaveCount(0);
    await expect(l.getByTestId('inscriptions-confirmation-desinscription')).toHaveCount(0);
    expect(await decoderQr(l)).toBe(jeton);
    expect(inscriptionExisteEnBase(ins.id)).toBe(true);

    await page.reload();
    await expect(l.getByTestId('inscriptions-course-statut')).toHaveText('En cours');
    await expect(l.getByTestId('inscriptions-bouton-desinscrire')).toHaveCount(0);

    placerStatutCourseEnBase(a.id, 'TERMINEE');
    await page.reload();
    await expect(l.getByTestId('inscriptions-course-statut')).toHaveText('Terminée');
    await expect(l.getByTestId('inscriptions-bouton-desinscrire')).toHaveCount(0);
    expect(inscriptionExisteEnBase(ins.id)).toBe(true);
  });

  test('CA15 - 404 INSCRIPTION_INTROUVABLE : « Cette inscription n\'existe plus. » et liste relue', async ({ page, playwright }) => {
    const { alice, a } = await preparerAliceAB(playwright);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    await intercepterSuppression(page, 404, 'INSCRIPTION_INTROUVABLE');
    const relecture = page.waitForRequest(estListe);
    await ouvrirEtConfirmer(ligneIns(page, a.nom));
    await relecture;
    await expect(page.getByTestId('inscriptions-erreur-desinscription')).toHaveText("Cette inscription n'existe plus.");
    await expect(ligneIns(page, a.nom).getByTestId('inscriptions-confirmation-desinscription')).toHaveCount(0);
  });

  test('CA15 - 403 CSRF_INVALIDE : « La page a expiré », confirmation conservée, nouveau jeton CSRF avant le nouvel essai', async ({ page, playwright }) => {
    const { alice, a, insA } = await preparerAliceAB(playwright);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    await intercepterSuppression(page, 403, 'CSRF_INVALIDE', 1);
    const evenements: string[] = [];
    page.on('request', (r) => {
      if (estSuppression(r)) evenements.push('DELETE');
      if (estCsrf(r)) evenements.push('CSRF');
    });
    const l = ligneIns(page, a.nom);
    await ouvrirEtConfirmer(l);
    await expect(page.getByTestId('inscriptions-erreur-desinscription')).toHaveText('La page a expiré, veuillez réessayer.');
    await expect(l.getByTestId('inscriptions-confirmation-desinscription')).toBeVisible();
    expect(inscriptionExisteEnBase(insA.id)).toBe(true);

    const avant = evenements.length;
    const reponse = page.waitForResponse((r) => estSuppression(r.request()));
    await l.getByTestId('inscriptions-bouton-confirmer-desinscription').click();
    expect((await reponse).status()).toBe(204);
    const depuis = evenements.slice(avant);
    expect(depuis.indexOf('CSRF')).toBeGreaterThanOrEqual(0);
    expect(depuis.indexOf('CSRF')).toBeLessThan(depuis.indexOf('DELETE'));
    await expect(page.getByTestId('inscriptions-message-desinscription')).toHaveText(`Vous êtes désinscrit de ${a.nom}.`);
    await expect(page.getByTestId('inscriptions-erreur-desinscription')).toHaveCount(0);
    await expect(ligneIns(page, a.nom)).toHaveCount(0);
    expect(inscriptionExisteEnBase(insA.id)).toBe(false);
  });

  test('CA15 - 403 ACCES_REFUSE redirige vers /acces-refuse', async ({ page, playwright }) => {
    const { alice, a } = await preparerAliceAB(playwright);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    await intercepterSuppression(page, 403, 'ACCES_REFUSE');
    await ouvrirEtConfirmer(ligneIns(page, a.nom));
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(page.getByTestId('titre-acces-refuse')).toBeVisible();
  });

  test('CA15 - 401 redirige vers la connexion avec le retour sur Mes inscriptions', async ({ page, playwright }) => {
    const { alice, a } = await preparerAliceAB(playwright);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    await intercepterSuppression(page, 401, 'NON_AUTHENTIFIE');
    await ouvrirEtConfirmer(ligneIns(page, a.nom));
    await expect(page).toHaveURL(RETOUR);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
  });

  test('CA15 - 500 : « Service indisponible », confirmation conservée, inscription intacte', async ({ page, playwright }) => {
    const { alice, a, insA } = await preparerAliceAB(playwright);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    await intercepterSuppression(page, 500, 'ERREUR_INTERNE');
    const l = ligneIns(page, a.nom);
    await ouvrirEtConfirmer(l);
    await expect(page.getByTestId('inscriptions-erreur-desinscription')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(l.getByTestId('inscriptions-confirmation-desinscription')).toBeVisible();
    await expect(l.getByTestId('inscriptions-bouton-confirmer-desinscription')).toBeEnabled();
    expect(inscriptionExisteEnBase(insA.id)).toBe(true);
  });

  test('CA15 - la course est supprimée par l\'admin master pendant que l\'écran est ouvert : inscription introuvable, ligne retirée', async ({ page, playwright }) => {
    const { alice, a, b } = await preparerAliceAB(playwright);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    await expect(page.getByTestId('inscriptions-ligne')).toHaveCount(2);
    expect(await avecApi(playwright, (ctx) => supprimerCourseParApi(ctx, a.id))).toBe(204);

    const reponse = page.waitForResponse((r) => estSuppression(r.request()));
    await ouvrirEtConfirmer(ligneIns(page, a.nom));
    expect((await reponse).status()).toBe(404);
    await expect(page.getByTestId('inscriptions-erreur-desinscription')).toHaveText("Cette inscription n'existe plus.");
    await expect(ligneIns(page, a.nom)).toHaveCount(0);
    await expect(ligneIns(page, b.nom)).toBeVisible();
  });

  test('CA15 - une erreur survenant après un succès remplace le message de succès', async ({ page, playwright }) => {
    const { alice, a, b } = await preparerAliceAB(playwright);
    await ouvrirMesInscriptionsEnTantQue(page, alice);
    await ouvrirEtConfirmer(ligneIns(page, b.nom));
    await expect(page.getByTestId('inscriptions-message-desinscription')).toHaveText(`Vous êtes désinscrit de ${b.nom}.`);

    await intercepterSuppression(page, 500, null);
    await ouvrirEtConfirmer(ligneIns(page, a.nom));
    await expect(page.getByTestId('inscriptions-erreur-desinscription')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('inscriptions-message-desinscription')).toHaveCount(0);
  });
});
