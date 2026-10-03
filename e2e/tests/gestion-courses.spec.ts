import { expect, test, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { creerCompteParApi, MOT_DE_PASSE, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_ADMIN_MASTER,
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
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

const URL_COURSES = /\/administration\/courses$/;
const RETOUR_COURSES = /\/connexion\?retour=%2Fadministration%2Fcourses$/;
const MOTIF_API = '**/api/administration/courses';

const probleme = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code }),
});

async function creerCourse(playwright: PlaywrightLib, donnees: DonneesCourse): Promise<Record<string, unknown>> {
  const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
  try {
    return await creerCourseParApi(ctx, donnees);
  } finally {
    await ctx.dispose();
  }
}

async function creerAdmin(playwright: PlaywrightLib, pseudo: string): Promise<void> {
  const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
  try {
    await creerAdminParApi(ctx, pseudo);
  } finally {
    await ctx.dispose();
  }
}

async function creerBenevole(playwright: PlaywrightLib, pseudo: string): Promise<void> {
  const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
  try {
    await creerBenevoleParApi(ctx, pseudo);
  } finally {
    await ctx.dispose();
  }
}

async function connecter(page: Page, pseudo: string, motDePasse: string, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

async function ouvrirGestionCourses(page: Page): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
  await page.goto('/administration/courses');
  await expect(page.getByTestId('titre-courses')).toBeVisible();
  await expect(page.getByTestId('liste-courses').or(page.getByTestId('courses-vide'))).toBeVisible();
}

async function remplir(page: Page, d: Partial<Record<string, string>>): Promise<void> {
  const champs: Array<[string, string]> = [
    ['nom', 'nom'], ['date', 'date'], ['distance', 'distance'], ['duree', 'duree'],
    ['denivele', 'denivele'], ['participants-max', 'participants-max'], ['boucles-max', 'boucles-max'],
  ];
  for (const [cle, suffixe] of champs) {
    if (d[cle] !== undefined) {
      await page.getByTestId(`course-champ-${suffixe}`).fill(d[cle]!);
    }
  }
}

/** `dateIso` (aaaa-mm-jj) est saisie à l'écran au format jj/mm/aaaa. */
function saisieReference(nom: string, dateIso: string, extra: Partial<Record<string, string>> = {}): Record<string, string> {
  return {
    nom, date: dateAffichee(dateIso), distance: '6706', duree: '60', denivele: '120', 'participants-max': '50', 'boucles-max': '24', ...extra,
  };
}

const CHAMPS = ['nom', 'date', 'distance', 'duree', 'denivele', 'participants-max', 'boucles-max'];

async function verifierChampsVides(page: Page): Promise<void> {
  for (const c of CHAMPS) {
    await expect(page.getByTestId(`course-champ-${c}`)).toHaveValue('');
  }
}

test.describe('Gestion des courses', () => {
  test('CA21 - un admin déclare une course, la retrouve dans la liste triée avec ses paramètres', async ({ page, browser, playwright }) => {
    const nom = nomCourseUnique();
    const date = dateDansJours(30);
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);

    await page.getByTestId('lien-gestion-courses').click();
    await expect(page).toHaveURL(URL_COURSES);
    await expect(page.getByTestId('titre-courses')).toHaveText('Gestion des courses');
    await expect(page.getByTestId('liste-courses').or(page.getByTestId('courses-vide'))).toBeVisible();

    const champDate = page.getByTestId('course-champ-date');
    await expect(champDate).toHaveAttribute('type', 'text');
    await expect(champDate).toHaveAttribute('placeholder', 'jj/mm/aaaa');
    await expect(page.getByText('Format : jj/mm/aaaa')).toBeVisible();

    await remplir(page, saisieReference(nom, date));
    await expect(champDate).toHaveValue(dateAffichee(date));
    const requetePost = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().includes('/api/administration/courses'),
    );
    await page.getByTestId('course-bouton-creer').click();
    expect((await requetePost).postDataJSON().date).toBe(date);

    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été déclarée.`);
    const ligne = page.getByTestId('ligne-course').filter({ hasText: nom });
    await expect(ligne).toHaveCount(1);
    await expect(ligne.getByTestId('course-nom')).toHaveText(nom);
    await expect(ligne.getByTestId('course-date')).toHaveText(dateAffichee(date));
    await expect(ligne.getByTestId('course-statut')).toHaveText('En préparation');
    await expect(ligne.getByTestId('course-distance')).toHaveText('6706 m');
    await expect(ligne.getByTestId('course-duree')).toHaveText('60 min');
    await expect(ligne.getByTestId('course-denivele')).toHaveText('120 m');
    await expect(ligne.getByTestId('course-participants-max')).toHaveText('50');
    await expect(ligne.getByTestId('course-boucles-max')).toHaveText('24');
    await verifierChampsVides(page);

    const api = await page.request.get('/api/administration/courses');
    expect(api.status()).toBe(200);
    const courses = (await api.json()) as Array<Record<string, unknown>>;
    const creee = courses.find((c) => c['nom'] === nom);
    expect(creee).toBeDefined();
    expect(creee!['statut']).toBe('EN_PREPARATION');
    expect(creee!['date']).toBe(date);

    // tri : une course plus lointaine passe avant (date décroissante)
    const nomLointain = nomCourseUnique('Course E2E lointaine');
    await creerCourse(playwright, courseDeReference({ nom: nomLointain, date: dateDansJours(60) }));
    await page.reload();
    await expect(page.getByTestId('course-nom').filter({ hasText: nomLointain })).toHaveCount(1);
    const noms = await page.getByTestId('course-nom').allTextContents();
    expect(noms.indexOf(nomLointain)).toBeGreaterThanOrEqual(0);
    expect(noms.indexOf(nomLointain)).toBeLessThan(noms.indexOf(nom));

    // même date : ordre alphabétique insensible à la casse
    const dateTri = dateDansJours(45);
    const suffixe = nomCourseUnique('');
    const nomB = `b-tri${suffixe}`;
    const nomA = `Z-tri${suffixe}`.replace('Z', 'A');
    await creerCourse(playwright, courseDeReference({ nom: nomB, date: dateTri }));
    await creerCourse(playwright, courseDeReference({ nom: nomA, date: dateTri }));
    await page.reload();
    await expect(page.getByTestId('course-nom').filter({ hasText: nomA })).toHaveCount(1);
    await expect(page.getByTestId('course-nom').filter({ hasText: nomB })).toHaveCount(1);
    const noms2 = await page.getByTestId('course-nom').allTextContents();
    expect(noms2.indexOf(nomA)).toBeGreaterThanOrEqual(0);
    expect(noms2.indexOf(nomA)).toBeLessThan(noms2.indexOf(nomB));

    // boucle plate : dénivelé 0 accepté
    const nomPlat = nomCourseUnique('Piste plate');
    await remplir(page, saisieReference(nomPlat, dateDansJours(31), { distance: '400', duree: '1', denivele: '0' }));
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nomPlat} a été déclarée.`);
    await expect(page.getByTestId('ligne-course').filter({ hasText: nomPlat }).getByTestId('course-denivele')).toHaveText('0 m');

    await page.getByTestId('lien-retour-administration').click();
    await expect(page).toHaveURL(/\/administration$/);
    await expect(page.getByTestId('titre-administration')).toBeVisible();

    // même déclaration par un admin non master
    const admin = pseudoAdminUnique();
    await creerAdmin(playwright, admin);
    const contexte = await browser.newContext();
    const pageAdmin = await contexte.newPage();
    await connecter(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pageAdmin).toHaveURL(/\/administration$/);
    await pageAdmin.getByTestId('lien-gestion-courses').click();
    await expect(pageAdmin).toHaveURL(URL_COURSES);
    await expect(pageAdmin.getByTestId('ligne-course').filter({ hasText: nom })).toHaveCount(1);
    const nom2 = nomCourseUnique();
    await remplir(pageAdmin, saisieReference(nom2, dateDansJours(30)));
    await pageAdmin.getByTestId('course-bouton-creer').click();
    await expect(pageAdmin.getByTestId('course-message-succes')).toHaveText(`La course ${nom2} a été déclarée.`);
    await expect(pageAdmin.getByTestId('ligne-course').filter({ hasText: nom2 })).toHaveCount(1);
    await contexte.close();
  });

  test('CA22 - les contrôles côté écran bloquent l\'envoi, le dénivelé 0 est accepté', async ({ page }) => {
    await ouvrirGestionCourses(page);
    const posts: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/api/administration/courses')) posts.push(r.url());
    });
    const lignesAvant = await page.getByTestId('ligne-course').count();

    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-erreur-nom')).toHaveText('Le nom est obligatoire.');
    await expect(page.getByTestId('course-erreur-date')).toHaveText('La date est obligatoire.');
    await expect(page.getByTestId('course-erreur-distance')).toHaveText('La distance d\'une boucle est obligatoire.');
    await expect(page.getByTestId('course-erreur-duree')).toHaveText('La durée d\'une boucle est obligatoire.');
    await expect(page.getByTestId('course-erreur-denivele')).toHaveText('Le dénivelé positif d\'une boucle est obligatoire.');
    await expect(page.getByTestId('course-erreur-participants-max')).toHaveText('Le nombre maximum de participants est obligatoire.');
    await expect(page.getByTestId('course-erreur-boucles-max')).toHaveText('Le nombre maximum de boucles est obligatoire.');

    for (const valeur of ['0', '-5']) {
      await page.getByTestId('course-champ-distance').fill(valeur);
      await page.getByTestId('course-bouton-creer').click();
      await expect(page.getByTestId('course-erreur-distance')).toHaveText('La distance d\'une boucle doit être un nombre entier supérieur à 0.');
    }

    await page.getByTestId('course-champ-denivele').fill('-5');
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-erreur-denivele')).toHaveText(
      'Le dénivelé positif d\'une boucle doit être un nombre entier supérieur ou égal à 0.',
    );

    await page.getByTestId('course-champ-denivele').fill('0');
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-erreur-denivele')).toHaveCount(0);
    await expect(page.getByTestId('course-erreur-nom')).toBeVisible();

    expect(posts).toHaveLength(0);
    await expect(page.getByTestId('ligne-course')).toHaveCount(lignesAvant);
  });

  test('CA26 - la saisie de la date jj/mm/aaaa est contrôlée côté écran puis convertie en aaaa-mm-jj, en français même en locale en-US', async ({ browser }) => {
    const contexte = await browser.newContext({ locale: 'en-US' });
    const page = await contexte.newPage();
    try {
      await ouvrirGestionCourses(page);
      const champDate = page.getByTestId('course-champ-date');
      const erreurDate = page.getByTestId('course-erreur-date');
      await expect(champDate).toHaveAttribute('type', 'text');
      await expect(champDate).toHaveAttribute('placeholder', 'jj/mm/aaaa');
      await expect(page.getByText('Format : jj/mm/aaaa')).toBeVisible();

      const posts: string[] = [];
      page.on('request', (r) => {
        if (r.method() === 'POST' && r.url().includes('/api/administration/courses')) posts.push(r.url());
      });
      const nom = nomCourseUnique();
      const lignesAvant = await page.getByTestId('ligne-course').count();
      await remplir(page, saisieReference(nom, dateDansJours(30)));

      const formatInvalide = ['31/02/2026', '14-11-2026', '2026-11-14', '14/11/26', '1/2/2026', '29/02/2027', 'abc'];
      for (const saisie of formatInvalide) {
        await champDate.fill(saisie);
        await page.getByTestId('course-bouton-creer').click();
        await expect(erreurDate, `saisie ${saisie}`).toHaveText('La date doit être au format jj/mm/aaaa.');
        await expect(champDate).toHaveValue(saisie);
      }
      for (const saisie of ['', '   ']) {
        await champDate.fill(saisie);
        await page.getByTestId('course-bouton-creer').click();
        await expect(erreurDate, `saisie « ${saisie} »`).toHaveText('La date est obligatoire.');
      }
      expect(posts).toHaveLength(0);
      await expect(page.getByTestId('ligne-course')).toHaveCount(lignesAvant);

      // format valide mais passée : part au serveur, message serveur, saisie conservée
      await champDate.fill('01/01/2020');
      const requetePassee = page.waitForRequest((r) => r.method() === 'POST' && r.url().includes('/api/administration/courses'));
      await page.getByTestId('course-bouton-creer').click();
      expect((await requetePassee).postDataJSON().date).toBe('2020-01-01');
      await expect(erreurDate).toHaveText('La date ne peut pas être dans le passé.');
      await expect(champDate).toHaveValue('01/01/2020');
      await expect(page.getByTestId('course-champ-nom')).toHaveValue(nom);

      // année bissextile : le contrôle client est franchi, le POST porte 2028-02-29
      await champDate.fill('29/02/2028');
      const requeteBissextile = page.waitForRequest((r) => r.method() === 'POST' && r.url().includes('/api/administration/courses'));
      await page.getByTestId('course-bouton-creer').click();
      expect((await requeteBissextile).postDataJSON().date).toBe('2028-02-29');
      await expect(erreurDate.or(page.getByTestId('course-message-succes'))).toBeVisible();
      if (await erreurDate.count()) {
        await expect(erreurDate).not.toHaveText('La date doit être au format jj/mm/aaaa.');
      }
      expect(posts).toHaveLength(2);

      // tout reste en français malgré la locale du navigateur
      await expect(page.getByTestId('titre-courses')).toHaveText('Gestion des courses');
      await expect(champDate).toHaveAttribute('placeholder', 'jj/mm/aaaa');
    } finally {
      await contexte.close();
    }
  });

  test('CA26 - les espaces autour d\'une date jj/mm/aaaa valide sont tolérés (trim)', async ({ page }) => {
    await ouvrirGestionCourses(page);
    await remplir(page, saisieReference(nomCourseUnique(), dateDansJours(30)));
    const champDate = page.getByTestId('course-champ-date');
    await champDate.fill(' 01/01/2020 ');
    const requete = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().includes('/api/administration/courses'),
      { timeout: 5000 },
    );
    await page.getByTestId('course-bouton-creer').click();
    expect((await requete).postDataJSON().date).toBe('2020-01-01');
    await expect(page.getByTestId('course-erreur-date')).toHaveText('La date ne peut pas être dans le passé.');
  });

  test('CA23 - les erreurs de validation du serveur s\'affichent sous les champs, les valeurs sont conservées', async ({ page }) => {
    await ouvrirGestionCourses(page);
    const lignesAvant = await page.getByTestId('ligne-course').count();
    const hier = dateDansJours(-1);
    const hierAffiche = dateAffichee(hier);

    await remplir(page, {
      nom: 'ab', date: hierAffiche, distance: '50001', duree: '1441', denivele: '10001', 'participants-max': '5001', 'boucles-max': '501',
    });
    await page.getByTestId('course-bouton-creer').click();

    await expect(page.getByTestId('course-erreur-nom')).toHaveText('Le nom doit faire entre 3 et 100 caractères.');
    await expect(page.getByTestId('course-erreur-date')).toHaveText('La date ne peut pas être dans le passé.');
    await expect(page.getByTestId('course-erreur-distance')).toHaveText('La distance d\'une boucle doit être comprise entre 1 et 50000 m.');
    await expect(page.getByTestId('course-erreur-duree')).toHaveText('La durée d\'une boucle doit être comprise entre 1 et 1440 min.');
    await expect(page.getByTestId('course-erreur-denivele')).toHaveText('Le dénivelé positif d\'une boucle doit être compris entre 0 et 10000 m.');
    await expect(page.getByTestId('course-erreur-participants-max')).toHaveText('Le nombre maximum de participants doit être compris entre 1 et 5000.');
    await expect(page.getByTestId('course-erreur-boucles-max')).toHaveText('Le nombre maximum de boucles doit être compris entre 1 et 500.');

    await expect(page.getByTestId('course-champ-nom')).toHaveValue('ab');
    await expect(page.getByTestId('course-champ-date')).toHaveValue(hierAffiche);
    await expect(page.getByTestId('course-champ-distance')).toHaveValue('50001');
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('1441');
    await expect(page.getByTestId('course-champ-denivele')).toHaveValue('10001');
    await expect(page.getByTestId('course-champ-participants-max')).toHaveValue('5001');
    await expect(page.getByTestId('course-champ-boucles-max')).toHaveValue('501');
    await expect(page.getByTestId('course-message-succes')).toHaveCount(0);
    await expect(page.getByTestId('ligne-course')).toHaveCount(lignesAvant);

    const nom = nomCourseUnique();
    await remplir(page, saisieReference(nom, dateDansJours(30)));
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été déclarée.`);
    await expect(page.getByTestId('ligne-course').filter({ hasText: nom })).toHaveCount(1);
    await expect(page.getByTestId('course-erreur-nom')).toHaveCount(0);
    await verifierChampsVides(page);
  });

  test('CA24 - l\'accès à /administration/courses et le lien dépendent du rôle', async ({ page, browser, request, playwright }) => {
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);
    const benevole = pseudoBenevoleUnique();
    await creerBenevole(playwright, benevole);
    const admin = pseudoAdminUnique();
    await creerAdmin(playwright, admin);

    // anonyme puis admin master avec retour, F5 conservé
    await page.goto('/administration/courses');
    await expect(page).toHaveURL(RETOUR_COURSES);
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await saisir(page, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(URL_COURSES);
    await expect(page.getByTestId('titre-courses')).toBeVisible();
    await page.reload();
    await expect(page).toHaveURL(URL_COURSES);
    await expect(page.getByTestId('titre-courses')).toBeVisible();
    await page.goto('/administration');
    await expect(page.getByTestId('lien-gestion-courses')).toBeVisible();
    await expect(page.getByTestId('lien-gestion-courses')).toHaveText('Gérer les courses');

    for (const [pseudoRole, mdp] of [[coureur, MOT_DE_PASSE], [benevole, MOT_DE_PASSE_BENEVOLE_CREE]]) {
      const contexte = await browser.newContext();
      const p = await contexte.newPage();
      await p.goto('/administration/courses');
      await expect(p).toHaveURL(RETOUR_COURSES);
      await saisir(p, pseudoRole, mdp);
      await p.getByTestId('bouton-connexion').click();
      await expect(p).toHaveURL(/\/acces-refuse$/);
      await expect(p.getByTestId('titre-acces-refuse')).toHaveText('Accès refusé');
      await contexte.close();
    }

    const contexteAdmin = await browser.newContext();
    const pageAdmin = await contexteAdmin.newPage();
    await connecter(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
    await expect(pageAdmin).toHaveURL(/\/administration$/);
    await expect(pageAdmin.getByTestId('lien-gestion-courses')).toBeVisible();
    await contexteAdmin.close();
  });

  test('CA25 - les erreurs 500, 403 CSRF, 401, 403 et la liste vide sont gérées', async ({ page }) => {
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);

    // liste en 500
    await page.route(MOTIF_API, (route) =>
      route.request().method() === 'GET' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue(),
    );
    await page.goto('/administration/courses');
    await expect(page.getByTestId('courses-erreur')).toHaveText('Impossible de charger la liste des courses. Réessayez plus tard.');
    await expect(page.getByTestId('courses-erreur')).not.toContainText('500');
    await expect(page.getByTestId('course-champ-nom')).toBeEnabled();
    await expect(page.getByTestId('course-bouton-creer')).toBeEnabled();
    await page.unroute(MOTIF_API);

    // liste vide interceptée
    await page.route(MOTIF_API, (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
        : route.continue(),
    );
    await page.goto('/administration/courses');
    await expect(page.getByTestId('courses-vide')).toHaveText('Aucune course pour le moment.');
    await page.unroute(MOTIF_API);

    // POST en 500
    await page.route(MOTIF_API, (route) =>
      route.request().method() === 'POST' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue(),
    );
    await page.goto('/administration/courses');
    await expect(page.getByTestId('liste-courses').or(page.getByTestId('courses-vide'))).toBeVisible();
    const nom = nomCourseUnique();
    const date = dateDansJours(30);
    await remplir(page, saisieReference(nom, date));
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(nom);
    await expect(page.getByTestId('course-champ-date')).toHaveValue(dateAffichee(date));
    await expect(page.getByTestId('course-champ-distance')).toHaveValue('6706');
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('60');
    await expect(page.getByTestId('course-champ-denivele')).toHaveValue('120');
    await expect(page.getByTestId('course-champ-participants-max')).toHaveValue('50');
    await expect(page.getByTestId('course-champ-boucles-max')).toHaveValue('24');
    await expect(page.getByTestId('course-bouton-creer')).toBeEnabled();
    await page.unroute(MOTIF_API);

    // POST en 403 CSRF_INVALIDE, puis nouveau jeton demandé
    await page.route(MOTIF_API, (route) =>
      route.request().method() === 'POST' ? route.fulfill(probleme(403, 'CSRF_INVALIDE')) : route.continue(),
    );
    await page.goto('/administration/courses');
    await expect(page.getByTestId('liste-courses').or(page.getByTestId('courses-vide'))).toBeVisible();
    await remplir(page, saisieReference(nom, date));
    const nouveauCsrf = page.waitForResponse((r) => r.url().includes('/api/csrf'));
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText('La page a expiré, veuillez réessayer.');
    await nouveauCsrf;
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(nom);
    await page.unroute(MOTIF_API);

    // liste en 401
    await page.route(MOTIF_API, (route) =>
      route.request().method() === 'GET' ? route.fulfill(probleme(401, 'NON_AUTHENTIFIE')) : route.continue(),
    );
    await page.goto('/administration/courses');
    await expect(page).toHaveURL(RETOUR_COURSES);
    await page.unroute(MOTIF_API);

    // liste en 403 ACCES_REFUSE
    await page.goto('/');
    await page.route(MOTIF_API, (route) =>
      route.request().method() === 'GET' ? route.fulfill(probleme(403, 'ACCES_REFUSE')) : route.continue(),
    );
    await page.goto('/administration/courses');
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });
});
