import { expect, test, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir, MOT_DE_PASSE } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  connecterAdminMaster,
  courseDeReference,
  creerAdminParApi,
  creerBenevoleParApi,
  creerCourseParApi,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
  type DonneesCourse,
} from './aide-admin';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const FIXTURES = join(__dirname, '..', 'fixtures');
const fixture = (nom: string): string => join(FIXTURES, nom);
const octets = (nom: string): Buffer => readFileSync(fixture(nom));
const MOTIF_LISTE = '**/api/administration/courses';
const MOTIF_LOGO = '**/api/administration/courses/*/logo';
const RETOUR_COURSES = /\/connexion\?retour=%2Fadministration%2Fcourses$/;

function pngAvecRemplissage(taille: number): Buffer {
  const png = octets('logo.png');
  return Buffer.concat([png, Buffer.alloc(taille - png.length)]);
}

async function avecContexte<T>(playwright: PlaywrightLib, action: (ctx: Awaited<ReturnType<PlaywrightLib['request']['newContext']>>) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

async function creerCourse(playwright: PlaywrightLib, donnees: DonneesCourse = courseDeReference()): Promise<{ id: string; nom: string }> {
  const course = await avecContexte(playwright, (ctx) => creerCourseParApi(ctx, donnees));
  return { id: course['id'] as string, nom: course['nom'] as string };
}

async function creerCourseAvecLogo(playwright: PlaywrightLib, fichier = 'logo.png', type = 'image/png'): Promise<{ id: string; nom: string }> {
  const course = await creerCourse(playwright);
  await avecContexte(playwright, (ctx) => envoyerLogoParApi(ctx, course.id, { nom: fichier, type, contenu: octets(fichier) }));
  return course;
}

async function creerAdmin(playwright: PlaywrightLib, pseudo: string): Promise<void> {
  await avecContexte(playwright, (ctx) => creerAdminParApi(ctx, pseudo));
}

async function ouvrirGestionCourses(page: Page): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
  await page.goto('/administration/courses');
  await expect(page.getByTestId('titre-courses')).toBeVisible();
  await expect(page.getByTestId('liste-courses')).toBeVisible();
}

function ligne(page: Page, nom: string): Locator {
  return page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });
}

async function choisir(l: Locator, fichier: string | { name: string; mimeType: string; buffer: Buffer }): Promise<void> {
  await l.getByTestId('course-champ-logo').setInputFiles(typeof fichier === 'string' ? fixture(fichier) : fichier);
}

async function srcVignette(l: Locator): Promise<string> {
  const src = await l.getByTestId('course-logo').getAttribute('src');
  expect(src).toBeTruthy();
  return src!;
}

async function lirePublic(playwright: PlaywrightLib, url: string): Promise<{ status: number; type: string | undefined; corps: Buffer }> {
  return avecContexte(playwright, async (ctx) => {
    const r = await ctx.get(url);
    return { status: r.status(), type: r.headers()['content-type'], corps: await r.body() };
  });
}

const estPutLogo = (r: Request): boolean => r.method() === 'PUT' && /\/api\/administration\/courses\/[^/]+\/logo$/.test(r.url());
const estDeleteLogo = (r: Request): boolean => r.method() === 'DELETE' && /\/api\/administration\/courses\/[^/]+\/logo$/.test(r.url());

function compterRequetesLogo(page: Page): { nombre: () => number } {
  let n = 0;
  page.on('request', (r) => {
    if (estPutLogo(r) || estDeleteLogo(r)) n += 1;
  });
  return { nombre: () => n };
}

test.describe('Logo de course', () => {
  test('CA21 - l\'admin envoie puis remplace un logo, lisible publiquement, persistant après F5', async ({ page, playwright }) => {
    const course = await creerCourse(playwright);
    const autre = await creerCourse(playwright);
    await ouvrirGestionCourses(page);
    const l = ligne(page, course.nom);

    await expect(l.getByTestId('course-logo-absent')).toHaveText('Aucun logo');
    await expect(l.getByTestId('course-bouton-logo')).toHaveText('Choisir un logo');
    await expect(l.getByTestId('course-bouton-logo-supprimer')).toHaveCount(0);
    await expect(l.getByTestId('course-champ-logo')).toHaveAttribute('accept', 'image/png,image/jpeg,image/webp');

    const requetes = compterRequetesLogo(page);
    await choisir(l, 'logo.png');
    await expect(l.getByTestId('course-logo-apercu')).toHaveAttribute('src', /^data:image\/png/);
    await expect(l.getByTestId('course-bouton-logo-envoyer')).toBeVisible();
    await expect(l.getByTestId('course-bouton-logo-annuler')).toBeVisible();
    expect(requetes.nombre()).toBe(0);

    const envoi = page.waitForRequest(estPutLogo);
    await l.getByTestId('course-bouton-logo-envoyer').click();
    const requete = await envoi;
    expect(requete.url()).toContain(`/api/administration/courses/${course.id}/logo`);
    expect(requete.headers()['content-type']).toMatch(/^multipart\/form-data/);
    // le corps d'un FormData contenant un fichier n'est pas exposé par Chromium : la partie `fichier` est prouvée par le GET public ci-dessous

    await expect(page.getByTestId('course-message-succes')).toHaveText(`Le logo de la course ${course.nom} a été enregistré.`);
    await expect(l.getByTestId('course-logo-apercu')).toHaveCount(0);
    await expect(l.getByTestId('course-logo-absent')).toHaveCount(0);
    const vignette = l.getByTestId('course-logo');
    await expect(vignette).toHaveAttribute('src', new RegExp(`^/api/courses/${course.id}/logo\\?v=`));
    await expect.poll(() => vignette.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
    await expect(l.getByTestId('course-bouton-logo-supprimer')).toBeVisible();

    const src1 = await srcVignette(l);
    const publicPng = await lirePublic(playwright, src1);
    expect(publicPng.status).toBe(200);
    expect(publicPng.type).toContain('image/png');
    expect(publicPng.corps.equals(octets('logo.png'))).toBe(true);

    await page.reload();
    await expect(page.getByTestId('liste-courses')).toBeVisible();
    await expect(ligne(page, course.nom).getByTestId('course-logo')).toHaveAttribute('src', src1);

    let precedent = src1;
    for (const [fichier, type] of [['logo.jpg', 'image/jpeg'], ['logo.webp', 'image/webp']]) {
      await choisir(l, fichier);
      await expect(l.getByTestId('course-bouton-logo-envoyer')).toBeVisible();
      await l.getByTestId('course-bouton-logo-envoyer').click();
      await expect(l.getByTestId('course-logo-apercu')).toHaveCount(0);
      await expect(l.getByTestId('course-logo')).not.toHaveAttribute('src', precedent);
      precedent = await srcVignette(l);
      const lecture = await lirePublic(playwright, precedent);
      expect(lecture.status).toBe(200);
      expect(lecture.type).toContain(type);
      expect(lecture.corps.equals(octets(fichier))).toBe(true);
    }

    await expect(ligne(page, autre.nom).getByTestId('course-logo-absent')).toHaveText('Aucun logo');

    // même opération par un ADMIN non master
    const admin = pseudoAdminUnique();
    await creerAdmin(playwright, admin);
    const contexte = await page.context().browser()!.newContext();
    const pageAdmin = await contexte.newPage();
    try {
      await ouvrirConnexion(pageAdmin);
      await saisir(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
      await pageAdmin.getByTestId('bouton-connexion').click();
      await expect(pageAdmin).toHaveURL(/\/administration$/);
      await pageAdmin.goto('/administration/courses');
      const la = ligne(pageAdmin, autre.nom);
      await choisir(la, 'logo-2.png');
      await la.getByTestId('course-bouton-logo-envoyer').click();
      await expect(pageAdmin.getByTestId('course-message-succes')).toHaveText(`Le logo de la course ${autre.nom} a été enregistré.`);
      await expect(la.getByTestId('course-logo')).toBeVisible();
    } finally {
      await contexte.close();
    }
  });

  test('CA22 - l\'admin supprime un logo', async ({ page, playwright }) => {
    const course = await creerCourseAvecLogo(playwright);
    const voisine = await creerCourseAvecLogo(playwright, 'logo-2.png');
    await ouvrirGestionCourses(page);
    const l = ligne(page, course.nom);
    const srcVoisine = await srcVignette(ligne(page, voisine.nom));
    const url = await srcVignette(l);

    const suppression = page.waitForResponse((r) => estDeleteLogo(r.request()));
    await l.getByTestId('course-bouton-logo-supprimer').click();
    const reponse = await suppression;
    expect(reponse.request().url()).toContain(`/api/administration/courses/${course.id}/logo`);
    expect(reponse.status()).toBe(204);

    await expect(page.getByTestId('course-message-succes')).toHaveText(`Le logo de la course ${course.nom} a été supprimé.`);
    await expect(l.getByTestId('course-logo-absent')).toBeVisible();
    await expect(l.getByTestId('course-bouton-logo-supprimer')).toHaveCount(0);

    const lecture = await avecContexte(playwright, async (ctx) => {
      const r = await ctx.get(`/api/courses/${course.id}/logo`);
      return { status: r.status(), corps: (await r.json()) as Record<string, unknown> };
    });
    expect(lecture.status).toBe(404);
    expect(lecture.corps['code']).toBe('LOGO_INTROUVABLE');
    expect(url).toContain(course.id);

    await page.reload();
    await expect(page.getByTestId('liste-courses')).toBeVisible();
    await expect(ligne(page, course.nom).getByTestId('course-logo-absent')).toHaveText('Aucun logo');
    await expect(ligne(page, voisine.nom).getByTestId('course-logo')).toHaveAttribute('src', srcVoisine);
  });

  test('CA23 - fichiers illisibles bloqués, formats refusés par le serveur, annulation, un seul aperçu', async ({ page, playwright }) => {
    const course = await creerCourseAvecLogo(playwright);
    const seconde = await creerCourse(playwright);
    await ouvrirGestionCourses(page);
    const l = ligne(page, course.nom);
    const src = await srcVignette(l);
    const requetes = compterRequetesLogo(page);

    for (const illisible of ['faux-logo.png', 'png-corrompu.png']) {
      await choisir(l, illisible);
      await expect(l.getByTestId('course-logo-erreur')).toHaveText('Ce fichier n\'est pas une image lisible.');
      await expect(l.getByTestId('course-bouton-logo-envoyer')).toHaveCount(0);
    }
    expect(requetes.nombre()).toBe(0);

    for (const refuse of ['image.gif', 'dessin.svg']) {
      await choisir(l, refuse);
      await expect(l.getByTestId('course-logo-apercu')).toBeVisible();
      const reponse = page.waitForResponse((r) => estPutLogo(r.request()));
      await l.getByTestId('course-bouton-logo-envoyer').click();
      expect((await reponse).status()).toBe(415);
      await expect(l.getByTestId('course-logo-erreur')).toHaveText('Le logo doit être une image PNG, JPEG ou WebP.');
      await expect(l.getByTestId('course-logo')).toHaveAttribute('src', src);
    }
    const avantAnnulation = requetes.nombre();
    expect(avantAnnulation).toBe(2);

    await choisir(l, 'logo-2.png');
    await expect(l.getByTestId('course-logo-apercu')).toBeVisible();
    await l.getByTestId('course-bouton-logo-annuler').click();
    await expect(l.getByTestId('course-logo-apercu')).toHaveCount(0);
    expect(requetes.nombre()).toBe(avantAnnulation);
    await expect(l.getByTestId('course-logo')).toHaveAttribute('src', src);

    // un seul aperçu ouvert, erreur précédente effacée
    await choisir(l, 'dessin.svg');
    await expect(l.getByTestId('course-logo-apercu')).toBeVisible();
    await l.getByTestId('course-bouton-logo-envoyer').click();
    await expect(l.getByTestId('course-logo-erreur')).toBeVisible();
    await choisir(l, 'logo-2.png');
    await expect(l.getByTestId('course-logo-erreur')).toHaveCount(0);
    const s = ligne(page, seconde.nom);
    await choisir(s, 'logo.png');
    await expect(s.getByTestId('course-logo-apercu')).toBeVisible();
    await expect(l.getByTestId('course-logo-apercu')).toHaveCount(0);
    await expect(page.getByTestId('course-logo-apercu')).toHaveCount(1);
  });

  test('CA24 - limite de 2 Mo : acceptée à 2 097 152 octets, refusée au-delà (serveur puis Caddy)', async ({ page, playwright }) => {
    test.setTimeout(90_000);
    const course = await creerCourseAvecLogo(playwright);
    await ouvrirGestionCourses(page);
    const l = ligne(page, course.nom);
    const srcInitial = await srcVignette(l);
    const limite = pngAvecRemplissage(2_097_152);

    await choisir(l, { name: 'limite.png', mimeType: 'image/png', buffer: limite });
    await expect(l.getByTestId('course-bouton-logo-envoyer')).toBeVisible();
    await l.getByTestId('course-bouton-logo-envoyer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`Le logo de la course ${course.nom} a été enregistré.`);
    await expect(l.getByTestId('course-logo')).not.toHaveAttribute('src', srcInitial);
    const srcLimite = await srcVignette(l);

    for (const [taille, statutAttendu] of [[2_097_153, 413], [5_000_000, 413]] as const) {
      await choisir(l, { name: 'gros.png', mimeType: 'image/png', buffer: pngAvecRemplissage(taille) });
      await expect(l.getByTestId('course-bouton-logo-envoyer')).toBeVisible();
      const reponse = page.waitForResponse((r) => estPutLogo(r.request()));
      await l.getByTestId('course-bouton-logo-envoyer').click();
      expect((await reponse).status()).toBe(statutAttendu);
      await expect(l.getByTestId('course-logo-erreur')).toHaveText('Le logo ne doit pas dépasser 2 Mo.');
      await expect(l.getByTestId('course-logo')).toHaveAttribute('src', srcLimite);
    }

    // l'écran reste utilisable
    await choisir(l, 'logo-2.png');
    await l.getByTestId('course-bouton-logo-envoyer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`Le logo de la course ${course.nom} a été enregistré.`);

    // contenu du fichier limite vérifié sur une autre Course pour ne pas dépendre du dernier envoi
    const autre = await creerCourse(playwright);
    await avecContexte(playwright, (ctx) => envoyerLogoParApi(ctx, autre.id, { nom: 'limite.png', type: 'image/png', contenu: limite }));
    const lecture = await lirePublic(playwright, `/api/courses/${autre.id}/logo`);
    expect(lecture.status).toBe(200);
    expect(lecture.corps.equals(limite)).toBe(true);
  });

  test('CA25 - boutons selon le statut et retours du serveur', async ({ page }) => {
    const idPrep = crypto.randomUUID();
    const idCours = crypto.randomUUID();
    const idFinie = crypto.randomUUID();
    const base = { date: '2030-01-01', distanceBoucleMetres: 6706, dureeBoucleMinutes: 60, denivelePositifBoucleMetres: 120, nombreMaxParticipants: 50, nombreMaxBoucles: 24 };
    const courses = [
      { ...base, id: idPrep, nom: 'Prep avec logo', statut: 'EN_PREPARATION', logoUrl: `/api/courses/${idPrep}/logo?v=aaaaaaaaaaaa` },
      { ...base, id: idCours, nom: 'Cours avec logo', statut: 'EN_COURS', logoUrl: `/api/courses/${idCours}/logo?v=bbbbbbbbbbbb` },
      { ...base, id: idFinie, nom: 'Finie sans logo', statut: 'TERMINEE', logoUrl: null },
    ];
    let rechargements = 0;
    await page.route('**/api/courses/*/logo*', (route) => route.fulfill({ status: 200, contentType: 'image/png', body: octets('logo.png') }));
    await page.route(MOTIF_LISTE, (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      rechargements += 1;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(courses) });
    });
    let reponseLogo: { status: number; code: string } | null = null;
    await page.route(MOTIF_LOGO, (route) => {
      const m = route.request().method();
      if ((m !== 'PUT' && m !== 'DELETE') || reponseLogo === null) return route.fallback();
      return route.fulfill({
        status: reponseLogo.status,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: reponseLogo.status, code: reponseLogo.code, detail: 'La course n\'est plus en préparation : elle ne peut plus être modifiée.' }),
      });
    });

    await ouvrirGestionCourses(page);
    const prep = ligne(page, 'Prep avec logo');
    const cours = ligne(page, 'Cours avec logo');
    const finie = ligne(page, 'Finie sans logo');
    await expect(prep.getByTestId('course-bouton-logo')).toBeVisible();
    await expect(prep.getByTestId('course-bouton-logo-supprimer')).toBeVisible();
    for (const l of [cours, finie]) {
      await expect(l.getByTestId('course-bouton-logo')).toHaveCount(0);
      await expect(l.getByTestId('course-bouton-logo-supprimer')).toHaveCount(0);
      await expect(l.getByTestId('course-champ-logo')).toHaveCount(0);
    }
    await expect(prep.getByTestId('course-logo')).toHaveCount(1);
    await expect(cours.getByTestId('course-logo')).toHaveCount(1);
    await expect(finie.getByTestId('course-logo-absent')).toHaveCount(1);

    type Cas = { status: number; code: string; message: string; rechargee: boolean; fichierConserve: boolean };
    const cas: Cas[] = [
      { status: 409, code: 'COURSE_NON_MODIFIABLE', message: 'La course n\'est plus en préparation : elle ne peut plus être modifiée.', rechargee: true, fichierConserve: false },
      { status: 404, code: 'COURSE_INTROUVABLE', message: 'Cette course n\'existe plus.', rechargee: true, fichierConserve: false },
      { status: 403, code: 'CSRF_INVALIDE', message: 'La page a expiré, veuillez réessayer.', rechargee: false, fichierConserve: true },
      { status: 500, code: 'ERREUR_INTERNE', message: 'Service indisponible, veuillez réessayer plus tard.', rechargee: false, fichierConserve: true },
      { status: 400, code: 'LOGO_REQUIS', message: 'Le fichier du logo est obligatoire.', rechargee: false, fichierConserve: true },
    ];
    for (const c of cas) {
      reponseLogo = { status: c.status, code: c.code };
      const avant = rechargements;
      await choisir(prep, 'logo.png');
      await expect(prep.getByTestId('course-bouton-logo-envoyer')).toBeVisible();
      await prep.getByTestId('course-bouton-logo-envoyer').click();
      await expect(prep.getByTestId('course-logo-erreur')).toHaveText(c.message);
      if (c.rechargee) {
        await expect.poll(() => rechargements).toBeGreaterThan(avant);
        await expect(prep.getByTestId('course-logo-apercu')).toHaveCount(0);
      } else if (c.fichierConserve) {
        await expect(prep.getByTestId('course-logo-apercu')).toBeVisible();
        await prep.getByTestId('course-bouton-logo-annuler').click();
      }
      await expect(prep.getByTestId('course-logo')).toHaveCount(1);
    }

    // DELETE intercepté : mêmes messages
    for (const c of cas.filter((x) => [409, 404, 500].includes(x.status))) {
      reponseLogo = { status: c.status, code: c.code };
      const avant = rechargements;
      await prep.getByTestId('course-bouton-logo-supprimer').click();
      await expect(prep.getByTestId('course-logo-erreur')).toHaveText(c.message);
      if (c.rechargee) await expect.poll(() => rechargements).toBeGreaterThan(avant);
    }

    // 401 : redirection vers la connexion
    reponseLogo = { status: 401, code: 'NON_AUTHENTIFIE' };
    await choisir(prep, 'logo.png');
    await expect(prep.getByTestId('course-bouton-logo-envoyer')).toBeVisible();
    await prep.getByTestId('course-bouton-logo-envoyer').click();
    await expect(page).toHaveURL(RETOUR_COURSES);

    // 403 ACCES_REFUSE : page d'accès refusé
    await page.goto('/administration/courses');
    await expect(page.getByTestId('liste-courses')).toBeVisible();
    reponseLogo = { status: 403, code: 'ACCES_REFUSE' };
    await choisir(ligne(page, 'Prep avec logo'), 'logo.png');
    await ligne(page, 'Prep avec logo').getByTestId('course-bouton-logo-envoyer').click();
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });

  test('CA26 - modifier la course (2.2) laisse le logo intact ; accès réservé aux admins', async ({ page, playwright }) => {
    const course = await creerCourseAvecLogo(playwright);
    await ouvrirGestionCourses(page);
    const l = ligne(page, course.nom);
    const src = await srcVignette(l);

    await choisir(l, 'logo-2.png');
    await expect(l.getByTestId('course-logo-apercu')).toBeVisible();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    await l.getByTestId('course-bouton-logo-annuler').click();

    await l.getByTestId('course-bouton-modifier').click();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await page.getByTestId('course-champ-duree').fill('45');
    const modification = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith(`/api/administration/courses/${course.id}`));
    await page.getByTestId('course-bouton-enregistrer').click();
    const requete = await modification;
    expect(requete.headers()['content-type']).toContain('application/json');
    expect(requete.headers()['content-type']).not.toContain('multipart');
    await expect(l.getByTestId('course-duree')).toHaveText('45 min');
    await expect(l.getByTestId('course-logo')).toHaveAttribute('src', src);

    // coureur et bénévole : accès refusé
    const coureur = pseudoUnique('coureur');
    await avecContexte(playwright, (ctx) => creerCompteParApi(ctx, coureur));
    const benevole = pseudoBenevoleUnique();
    await avecContexte(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
    for (const [pseudo, mdp] of [[coureur, MOT_DE_PASSE], [benevole, MOT_DE_PASSE_BENEVOLE_CREE]]) {
      const contexte = await page.context().browser()!.newContext();
      const p = await contexte.newPage();
      try {
        await ouvrirConnexion(p);
        await saisir(p, pseudo, mdp);
        await p.getByTestId('bouton-connexion').click();
        await expect(p.getByTestId('entete-pseudo')).toHaveText(pseudo);
        await p.goto('/administration/courses');
        await expect(p).toHaveURL(/\/acces-refuse$/);
      } finally {
        await contexte.close();
      }
    }
  });
});
