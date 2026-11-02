import { expect, test, type APIRequestContext, type Browser, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { join } from 'node:path';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_ADMIN_MASTER,
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
  connecterParApi,
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
} from './aide-admin';
import { FORMULAIRES, ouvrirFormulaire, verifierFerme, type NomFormulaire } from './aide-formulaire';
import {
  AIDES_PAGE,
  ETATS,
  RGB_PALETTE,
  RGB_TRANSPARENT,
  ouvrirScene,
  preparerJeuReference,
  type EcartCouleur,
  type MesureContraste,
} from './aide-theme';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const MASTER = { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER };
const BASE_URL = process.env.BASE_URL ?? 'http://localhost';
const AGRANDIR = 'html{font-size:32px}';
const FOCUS = 'rgb(168, 67, 0)';
const MOT_DE_PASSE = 'un-mot-de-passe-12';
const CHAMPS_COURSE = ['nom', 'date', 'distance', 'duree', 'denivele', 'participants-max', 'boucles-max'];
const FIXTURES = join(__dirname, '..', 'fixtures');

interface PageGeree {
  nom: NomFormulaire;
  chemin: string;
  titre: string;
  liste: string;
  vide: string;
  erreur: string;
  ligne: string;
  api: string;
  libelleBouton: string;
  titreFormulaire: string;
  aideVide: string;
  texteVide: string;
  boutonCreer: string;
  boutonAnnuler: string;
  champs: string[];
}

const PAGES: PageGeree[] = [
  {
    nom: 'course', chemin: '/administration/courses', titre: 'titre-courses', liste: 'liste-courses', vide: 'courses-vide', erreur: 'courses-erreur',
    ligne: 'ligne-course', api: '**/api/administration/courses', libelleBouton: 'Nouvelle course', titreFormulaire: 'Déclarer une course',
    aideVide: 'Utilisez le bouton « Nouvelle course » pour déclarer la première course.', texteVide: 'Aucune course pour le moment.',
    boutonCreer: 'course-bouton-creer', boutonAnnuler: 'course-bouton-annuler-creation',
    champs: CHAMPS_COURSE.map((c) => `course-champ-${c}`),
  },
  {
    nom: 'admin', chemin: '/administration/admins', titre: 'titre-admins', liste: 'liste-admins', vide: 'admins-vide', erreur: 'admins-erreur',
    ligne: 'ligne-admin', api: '**/api/administration/admins', libelleBouton: 'Nouvel admin', titreFormulaire: 'Créer un administrateur',
    aideVide: 'Créez-en un avec le bouton « Nouvel admin ».', texteVide: 'Aucun administrateur pour le moment.',
    boutonCreer: 'admin-bouton-creer', boutonAnnuler: 'admin-bouton-annuler',
    champs: ['admin-champ-pseudo', 'admin-champ-mot-de-passe', 'admin-champ-confirmation'],
  },
  {
    nom: 'benevole', chemin: '/administration/benevoles', titre: 'titre-benevoles', liste: 'liste-benevoles', vide: 'benevoles-vide', erreur: 'benevoles-erreur',
    ligne: 'ligne-benevole', api: '**/api/administration/benevoles', libelleBouton: 'Nouveau bénévole', titreFormulaire: 'Créer un bénévole',
    aideVide: 'Créez-en un avec le bouton « Nouveau bénévole ».', texteVide: 'Aucun bénévole pour le moment.',
    boutonCreer: 'benevole-bouton-creer', boutonAnnuler: 'benevole-bouton-annuler',
    champs: ['benevole-champ-pseudo', 'benevole-champ-mot-de-passe', 'benevole-champ-confirmation'],
  },
];
const PAGES_COMPTES = PAGES.filter((p) => p.nom !== 'course');
const PAGE_COURSES = PAGES[0];

const probleme = (status: number, code: string, detail = '') => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code, detail }),
});

/* ------------------------------------------------------------------ aides */

async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

const creerCourse = (playwright: PlaywrightLib, surcharge = {}) =>
  avecApi(playwright, (ctx) => creerCourseParApi(ctx, courseDeReference(surcharge))) as Promise<{ id: string; nom: string }>;

async function creerAdmin(playwright: PlaywrightLib): Promise<string> {
  const pseudo = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, pseudo));
  return pseudo;
}

async function creerBenevole(playwright: PlaywrightLib): Promise<string> {
  const pseudo = pseudoBenevoleUnique();
  await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, pseudo));
  return pseudo;
}

/** Session API dans le contexte du navigateur (cookies partagés avec la page), puis ouverture de l'écran. */
async function ouvrirEn(page: Page, f: PageGeree, ident = MASTER): Promise<void> {
  await page.context().clearCookies();
  await connecterParApi(page.request, ident.pseudo, ident.motDePasse);
  await page.goto(f.chemin);
  await attendreEcran(page, f);
}

async function attendreEcran(page: Page, f: PageGeree): Promise<void> {
  await expect(page.getByTestId(f.titre)).toBeVisible();
  await expect(page.getByText('Chargement…')).toHaveCount(0);
}

async function ouvrirEnNadia(browser: Browser, playwright: PlaywrightLib): Promise<{ page: Page; fermer: () => Promise<void> }> {
  const pseudo = await creerAdmin(playwright);
  const contexte = await browser.newContext();
  const page = await contexte.newPage();
  await connecterParApi(page.request, pseudo, MOT_DE_PASSE_ADMIN_CREE);
  return { page, fermer: () => contexte.close() };
}

function requetesApiHorsLecture(page: Page): Request[] {
  const requetes: Request[] = [];
  page.on('request', (r) => {
    if (r.url().includes('/api/') && r.method() !== 'GET') requetes.push(r);
  });
  return requetes;
}

function toutesRequetesApi(page: Page): Request[] {
  const requetes: Request[] = [];
  page.on('request', (r) => {
    if (r.url().includes('/api/')) requetes.push(r);
  });
  return requetes;
}

const estPostCreation = (r: Request): boolean => r.method() === 'POST' && /\/api\/administration\/(courses|admins|benevoles)$/.test(r.url());
const estPutCourse = (r: Request): boolean => r.method() === 'PUT' && /\/api\/administration\/courses\/[^/]+$/.test(r.url());

const ligneCourse = (page: Page, nom: string): Locator =>
  page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });

async function remplirCourse(page: Page, nom: string, dateIso: string, extra: Record<string, string> = {}): Promise<void> {
  const valeurs: Record<string, string> = {
    nom, date: dateAffichee(dateIso), distance: '6706', duree: '60', denivele: '120', 'participants-max': '50', 'boucles-max': '24', ...extra,
  };
  for (const [cle, valeur] of Object.entries(valeurs)) {
    await page.getByTestId(`course-champ-${cle}`).fill(valeur);
  }
}

async function remplirCompte(page: Page, p: string, pseudo: string, motDePasse = MOT_DE_PASSE, confirmation = motDePasse): Promise<void> {
  await page.getByTestId(`${p}-champ-pseudo`).fill(pseudo);
  await page.getByTestId(`${p}-champ-mot-de-passe`).fill(motDePasse);
  await page.getByTestId(`${p}-champ-confirmation`).fill(confirmation);
}

async function verifierChampsVides(page: Page, f: PageGeree): Promise<void> {
  for (const champ of f.champs) {
    await expect(page.getByTestId(champ)).toHaveValue('');
    await expect(page.getByTestId(champ)).not.toHaveAttribute('aria-invalid', 'true');
  }
}

/** Réponse du `GET` de la liste filtrée sur les éléments créés par le test (liste déterministe, courte). */
async function filtrerListe(page: Page, f: PageGeree, gardes: string[]): Promise<void> {
  await page.route(f.api, async (route) => {
    if (route.request().method() !== 'GET') return route.continue();
    const reponse = await route.fetch();
    const elements = (await reponse.json()) as Array<{ nom?: string; pseudo?: string }>;
    return route.fulfill({ response: reponse, json: elements.filter((e) => gardes.includes((e.nom ?? e.pseudo)!)) });
  });
}

/** Aucun descendant du formulaire ne dépasse ses bords latéraux. */
async function debordementsFormulaire(page: Page, f: PageGeree): Promise<string[]> {
  return page.getByTestId(FORMULAIRES[f.nom].formulaire).evaluate((form) => {
    const rf = form.getBoundingClientRect();
    return Array.from(form.querySelectorAll('*'))
      .filter((e) => {
        const r = e.getBoundingClientRect();
        return r.width > 0 && (r.left < rf.left - 0.5 || r.right > rf.right + 0.5);
      })
      .map((e) => e.getAttribute('data-testid') ?? e.tagName);
  });
}

async function sansDefilementHorizontal(page: Page, contexte: string): Promise<void> {
  const m = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
  expect(m.scroll, `${contexte} : défilement horizontal`).toBeLessThanOrEqual(m.client);
}

/* ------------------------------------------------------------------ CA1 */

async function verifierEtatInitial(page: Page, f: PageGeree, contexte: string): Promise<void> {
  const fm = FORMULAIRES[f.nom];
  await expect(page.getByTestId(f.liste).or(page.getByTestId(f.vide)).or(page.getByTestId(f.erreur)), contexte).toBeVisible();
  await expect(page.getByTestId(fm.formulaire), contexte).not.toBeVisible();
  const bouton = page.getByTestId(fm.bouton);
  await expect(bouton).toBeVisible();
  await expect(bouton).toHaveText(f.libelleBouton);
  await expect(bouton).toHaveAttribute('aria-expanded', 'false');
  await expect(bouton).toHaveAttribute('aria-controls', fm.id);
  expect((await bouton.boundingBox())!.height, `${contexte} : hauteur du bouton`).toBeGreaterThanOrEqual(44);
}

async function aucunChampAtteintEnTab(page: Page, f: PageGeree, nombre = 30): Promise<void> {
  await page.getByTestId('entete-titre').focus();
  const formulaire = FORMULAIRES[f.nom].formulaire;
  for (let i = 0; i < nombre; i++) {
    await page.keyboard.press('Tab');
    const dedans = await page.evaluate((id) => !!document.activeElement?.closest(`[data-testid="${id}"]`), formulaire);
    expect(dedans, `Tab ${i + 1} : le focus ne doit pas entrer dans le formulaire fermé`).toBe(false);
  }
}

test.describe('Formulaires de création à la demande', () => {
  test('CA1 - à l\'arrivée la liste est visible, le formulaire fermé et le bouton d\'ouverture présent, même après F5, liste en 500 ou vide', async ({ page, browser, playwright }) => {
    test.setTimeout(180_000);
    await creerCourse(playwright);
    await creerAdmin(playwright);
    await creerBenevole(playwright);

    for (const f of PAGES) {
      await ouvrirEn(page, f);
      await expect(page.getByTestId(f.liste)).toBeVisible();
      await verifierEtatInitial(page, f, `${f.chemin} arrivée`);
      await aucunChampAtteintEnTab(page, f);

      await page.reload();
      await attendreEcran(page, f);
      await expect(page.getByTestId(f.liste)).toBeVisible();
      await verifierEtatInitial(page, f, `${f.chemin} après F5`);

      // liste en 500 : message d'erreur de chargement et bouton présents
      await page.route(f.api, (route) => (route.request().method() === 'GET' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue()));
      await page.goto(f.chemin);
      await expect(page.getByTestId(f.erreur)).toBeVisible();
      await verifierEtatInitial(page, f, `${f.chemin} liste en 500`);
      await expect(page.getByTestId(FORMULAIRES[f.nom].bouton)).toBeEnabled();
      await page.unroute(f.api);

      // liste vide
      await page.route(f.api, (route) =>
        route.request().method() === 'GET' ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }) : route.continue(),
      );
      await page.goto(f.chemin);
      await expect(page.getByTestId(f.vide)).toBeVisible();
      await verifierEtatInitial(page, f, `${f.chemin} liste vide`);
      await page.unroute(f.api);
    }

    // Nadia (ADMIN) voit « Nouvelle course » et « Nouveau bénévole »
    const nadia = await ouvrirEnNadia(browser, playwright);
    try {
      for (const f of [PAGES[0], PAGES[2]]) {
        await nadia.page.goto(f.chemin);
        await attendreEcran(nadia.page, f);
        await verifierEtatInitial(nadia.page, f, `Nadia ${f.chemin}`);
      }
    } finally {
      await nadia.fermer();
    }
  });

  /* ---------------------------------------------------------------- CA2 */

  test('CA2 - la Course : ouvrir donne le focus au nom, valider à vide affiche 7 erreurs sans requête, Annuler efface tout et rend le focus au bouton', async ({ page }) => {
    await ouvrirEn(page, PAGE_COURSES);
    const requetes = requetesApiHorsLecture(page);
    await verifierFerme(page, 'course');

    await ouvrirFormulaire(page, 'course');
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    await verifierChampsVides(page, PAGE_COURSES);

    await page.getByTestId('course-bouton-creer').click();
    for (const c of CHAMPS_COURSE) await expect(page.getByTestId(`course-erreur-${c}`)).toBeVisible();
    expect(requetes.filter(estPostCreation)).toHaveLength(0);
    await expect(page.getByTestId('course-formulaire')).toBeVisible();

    await page.getByTestId('course-champ-nom').fill('ab');
    await page.getByTestId('course-bouton-annuler-creation').click();
    await verifierFerme(page, 'course');
    await expect(page.getByTestId('course-bouton-nouvelle')).toBeFocused();
    expect(requetes).toHaveLength(0);

    await ouvrirFormulaire(page, 'course');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue('');
    await verifierChampsVides(page, PAGE_COURSES);
    await expect(page.getByTestId('course-erreur-nom')).toHaveCount(0);
    for (const c of CHAMPS_COURSE) await expect(page.getByTestId(`course-champ-${c}`)).not.toHaveAttribute('aria-invalid', /.*/);
    expect(requetes).toHaveLength(0);
  });

  /* ---------------------------------------------------------------- CA3 */

  for (const f of PAGES_COMPTES) {
    test(`CA3 - ${f.chemin} : ouvrir donne le focus au pseudo, un pseudo existant reste ouvert avec le pseudo conservé, Annuler efface tout`, async ({ page, playwright }) => {
      const p = f.nom;
      const existant = p === 'admin' ? await creerAdmin(playwright) : await creerBenevole(playwright);
      await ouvrirEn(page, f);
      const requetes = requetesApiHorsLecture(page);

      await ouvrirFormulaire(page, p);
      await verifierChampsVides(page, f);
      await expect(page.getByTestId(FORMULAIRES[p].bouton)).toHaveAttribute('aria-expanded', 'true');

      await remplirCompte(page, p, existant, MOT_DE_PASSE);
      await page.getByTestId(f.boutonCreer).click();
      await expect(page.getByTestId(`${p}-erreur-pseudo`).or(page.getByTestId(`${p}-erreur-generale`)).first()).toHaveText('Ce pseudo est déjà utilisé.');
      await expect(page.getByTestId(FORMULAIRES[p].formulaire)).toBeVisible();
      await expect(page.getByTestId(FORMULAIRES[p].bouton)).toHaveAttribute('aria-expanded', 'true');
      await expect(page.getByTestId(`${p}-champ-pseudo`)).toHaveValue(existant);
      await expect(page.getByTestId(`${p}-champ-mot-de-passe`)).toHaveValue('');
      await expect(page.getByTestId(`${p}-champ-confirmation`)).toHaveValue('');

      await page.getByTestId(f.boutonAnnuler).click();
      await verifierFerme(page, p);
      await expect(page.getByTestId(FORMULAIRES[p].bouton)).toBeFocused();

      await ouvrirFormulaire(page, p);
      await verifierChampsVides(page, f);
      await expect(page.getByTestId(`${p}-erreur-pseudo`)).toHaveCount(0);
      await expect(page.getByTestId(`${p}-erreur-generale`)).toHaveCount(0);
      expect(requetes.map((r) => r.method())).toEqual(['POST']);
    });
  }

  /* ---------------------------------------------------------------- CA4 */

  test('CA4 - déclarer une Course ferme le formulaire, affiche le message hors du formulaire, ajoute la ligne et rend le focus au bouton', async ({ page }) => {
    await ouvrirEn(page, PAGE_COURSES);
    const nom = nomCourseUnique();
    const date = dateDansJours(30);
    await ouvrirFormulaire(page, 'course');
    await remplirCourse(page, nom, date);

    const reponse = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/administration/courses'));
    await page.getByTestId('course-bouton-creer').click();
    const r = await reponse;
    expect(r.status()).toBe(201);
    expect(r.request().postDataJSON().date).toBe(date);

    await verifierFerme(page, 'course');
    const succes = page.getByTestId('course-message-succes');
    await expect(succes).toHaveText(`La course ${nom} a été déclarée.`);
    await expect(succes).toHaveAttribute('role', 'status');
    await expect(succes).toHaveClass(/\bmessage--succes\b/);
    await expect(page.getByTestId('course-formulaire').getByTestId('course-message-succes')).toHaveCount(0);
    expect(await succes.evaluate((el) => !!el.closest('form'))).toBe(false);
    await expect(ligneCourse(page, nom)).toHaveCount(1);
    await expect(page.getByTestId('course-bouton-nouvelle')).toBeFocused();

    await ouvrirFormulaire(page, 'course');
    await verifierChampsVides(page, PAGE_COURSES);
    await expect(page.getByTestId('course-message-succes')).toHaveCount(0);
  });

  /* ---------------------------------------------------------------- CA5 */

  test('CA5 - créer un administrateur (Patron) puis un bénévole (Nadia) ferme le formulaire, affiche le message hors du formulaire et rend le focus', async ({ page, browser, playwright }) => {
    const pseudoAdmin = pseudoAdminUnique();
    await ouvrirEn(page, PAGES[1]);
    await ouvrirFormulaire(page, 'admin');
    await remplirCompte(page, 'admin', pseudoAdmin);
    const reponseAdmin = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/administration/admins'));
    await page.getByTestId('admin-bouton-creer').click();
    expect((await reponseAdmin).status()).toBe(201);
    await verifierFerme(page, 'admin');
    const succes = page.getByTestId('admin-message-succes');
    await expect(succes).toHaveText(`L'administrateur ${pseudoAdmin} a été créé.`);
    expect(await succes.evaluate((el) => !!el.closest('form'))).toBe(false);
    await expect(page.getByTestId('ligne-admin').filter({ hasText: pseudoAdmin })).toHaveCount(1);
    await expect(page.getByTestId('admin-bouton-nouveau')).toBeFocused();
    await ouvrirFormulaire(page, 'admin');
    await verifierChampsVides(page, PAGES[1]);

    const nadia = await ouvrirEnNadia(browser, playwright);
    try {
      const pseudoBenevole = pseudoBenevoleUnique();
      await nadia.page.goto(PAGES[2].chemin);
      await attendreEcran(nadia.page, PAGES[2]);
      await ouvrirFormulaire(nadia.page, 'benevole');
      await remplirCompte(nadia.page, 'benevole', pseudoBenevole);
      const reponseBenevole = nadia.page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/administration/benevoles'));
      await nadia.page.getByTestId('benevole-bouton-creer').click();
      expect((await reponseBenevole).status()).toBe(201);
      await verifierFerme(nadia.page, 'benevole');
      const succesBenevole = nadia.page.getByTestId('benevole-message-succes');
      await expect(succesBenevole).toHaveText(`Le bénévole ${pseudoBenevole} a été créé.`);
      expect(await succesBenevole.evaluate((el) => !!el.closest('form'))).toBe(false);
      await expect(nadia.page.getByTestId('ligne-benevole').filter({ hasText: pseudoBenevole })).toHaveCount(1);
      await expect(nadia.page.getByTestId('benevole-bouton-nouveau')).toBeFocused();
      await ouvrirFormulaire(nadia.page, 'benevole');
      await verifierChampsVides(nadia.page, PAGES[2]);
    } finally {
      await nadia.fermer();
    }
  });

  /* ---------------------------------------------------------------- CA6 */

  test('CA6 - une saisie refusée (Course) laisse le formulaire ouvert avec ses valeurs, puis la correction ferme le formulaire', async ({ page }) => {
    test.setTimeout(120_000);
    await ouvrirEn(page, PAGE_COURSES);
    const requetes = requetesApiHorsLecture(page);
    const bouton = page.getByTestId('course-bouton-nouvelle');
    await ouvrirFormulaire(page, 'course');

    // à vide : 7 erreurs, aucun POST
    await page.getByTestId('course-bouton-creer').click();
    for (const c of CHAMPS_COURSE) await expect(page.getByTestId(`course-erreur-${c}`)).toBeVisible();
    expect(requetes.filter(estPostCreation)).toHaveLength(0);
    await expect(bouton).toHaveAttribute('aria-expanded', 'true');

    // date de la veille : 400 du serveur
    const lignesAvant = await page.getByTestId('ligne-course').count();
    const nom = nomCourseUnique();
    const date = dateDansJours(30);
    await remplirCourse(page, nom, dateDansJours(-1));
    const reponse400 = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/administration/courses'));
    await page.getByTestId('course-bouton-creer').click();
    expect((await reponse400).status()).toBe(400);
    await expect(page.getByTestId('course-erreur-date')).toHaveText('La date ne peut pas être dans le passé.');
    await expect(bouton).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('course-formulaire')).toBeVisible();
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(nom);
    await expect(page.getByTestId('course-champ-date')).toHaveValue(dateAffichee(dateDansJours(-1)));
    await expect(page.getByTestId('ligne-course')).toHaveCount(lignesAvant);

    // 500 simulé : valeurs conservées
    await page.getByTestId('course-champ-date').fill(dateAffichee(date));
    await page.route(PAGE_COURSES.api, (route) => (route.request().method() === 'POST' ? route.fulfill(probleme(500, 'ERREUR_INTERNE')) : route.continue()));
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(bouton).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(nom);
    await expect(page.getByTestId('course-champ-date')).toHaveValue(dateAffichee(date));
    await expect(page.getByTestId('course-champ-boucles-max')).toHaveValue('24');
    await page.unroute(PAGE_COURSES.api);

    // 403 CSRF_INVALIDE : nouveau jeton demandé
    await page.route(PAGE_COURSES.api, (route) => (route.request().method() === 'POST' ? route.fulfill(probleme(403, 'CSRF_INVALIDE')) : route.continue()));
    const nouveauCsrf = page.waitForResponse((r) => r.url().includes('/api/csrf'));
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText('La page a expiré, veuillez réessayer.');
    await nouveauCsrf;
    await expect(bouton).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(nom);
    await page.unroute(PAGE_COURSES.api);

    // correction : l'envoi réussit et le formulaire se ferme
    const reponse201 = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/administration/courses'));
    await page.getByTestId('course-bouton-creer').click();
    expect((await reponse201).status()).toBe(201);
    await verifierFerme(page, 'course');
    await expect(ligneCourse(page, nom)).toHaveCount(1);
  });

  for (const f of PAGES_COMPTES) {
    test(`CA6 - ${f.chemin} : une saisie refusée laisse le formulaire ouvert, puis la correction ferme le formulaire`, async ({ page }) => {
      test.setTimeout(120_000);
      const p = f.nom;
      await ouvrirEn(page, f);
      const requetes = requetesApiHorsLecture(page);
      const bouton = page.getByTestId(FORMULAIRES[p].bouton);
      await ouvrirFormulaire(page, p);

      // mot de passe de 11 caractères : refus côté écran, aucun POST
      const pseudo = p === 'admin' ? pseudoAdminUnique() : pseudoBenevoleUnique();
      await remplirCompte(page, p, pseudo, '12345678901');
      await page.getByTestId(f.boutonCreer).click();
      await expect(page.getByTestId(`${p}-erreur-mot-de-passe`)).toHaveText('Le mot de passe doit faire au moins 12 caractères.');
      expect(requetes.filter(estPostCreation)).toHaveLength(0);
      await expect(bouton).toHaveAttribute('aria-expanded', 'true');

      // confirmation différente
      await remplirCompte(page, p, pseudo, MOT_DE_PASSE, 'autre-chose-12');
      await page.getByTestId(f.boutonCreer).click();
      await expect(page.getByTestId(`${p}-erreur-confirmation`)).toHaveText('Les mots de passe ne correspondent pas.');
      expect(requetes.filter(estPostCreation)).toHaveLength(0);
      await expect(bouton).toHaveAttribute('aria-expanded', 'true');

      // pseudo « ab » : 400 du serveur, pseudo conservé, mots de passe vidés
      await remplirCompte(page, p, 'ab');
      const reponse400 = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith(f.api.replace('**', '')));
      await page.getByTestId(f.boutonCreer).click();
      expect((await reponse400).status()).toBe(400);
      await expect(page.getByTestId(`${p}-erreur-pseudo`)).toBeVisible();
      await expect(page.getByTestId(FORMULAIRES[p].formulaire)).toBeVisible();
      await expect(bouton).toHaveAttribute('aria-expanded', 'true');
      await expect(page.getByTestId(`${p}-champ-pseudo`)).toHaveValue('ab');
      await expect(page.getByTestId(`${p}-champ-mot-de-passe`)).toHaveValue('');
      await expect(page.getByTestId(`${p}-champ-confirmation`)).toHaveValue('');

      // correction : 201 et fermeture
      await remplirCompte(page, p, pseudo);
      const reponse201 = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith(f.api.replace('**', '')));
      await page.getByTestId(f.boutonCreer).click();
      expect((await reponse201).status()).toBe(201);
      await verifierFerme(page, p);
      await expect(page.getByTestId(f.ligne).filter({ hasText: pseudo })).toHaveCount(1);
    });
  }

  /* ---------------------------------------------------------------- CA7 */

  test('CA7 - modifier une Course ouvre le formulaire, l\'enregistrement et Annuler le ferment, le 409 reste ouvert en déclaration, la suppression de la Course éditée le ferme', async ({ page, playwright }) => {
    test.setTimeout(180_000);
    const a = await creerCourse(playwright);
    const b = await creerCourse(playwright);
    await ouvrirEn(page, PAGE_COURSES);
    const getsParId: string[] = [];
    const puts: Request[] = [];
    let listes = 0;
    page.on('request', (r) => {
      if (r.method() === 'GET' && /\/api\/administration\/courses\/[^/]+$/.test(r.url())) getsParId.push(r.url());
      if (r.method() === 'GET' && r.url().endsWith('/api/administration/courses')) listes++;
      if (estPutCourse(r)) puts.push(r);
    });

    // ouverture par « Modifier »
    await verifierFerme(page, 'course');
    await ligneCourse(page, a.nom).getByTestId('course-bouton-modifier').click();
    await expect(page.getByTestId('course-formulaire')).toBeVisible();
    await expect(page.getByTestId('course-bouton-nouvelle')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(a.nom);
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('60');
    await expect(page.getByTestId('course-champ-nom')).toBeFocused();
    expect(getsParId).toHaveLength(0);

    // enregistrement
    await page.getByTestId('course-champ-duree').fill('45');
    const reponsePut = page.waitForResponse((r) => estPutCourse(r.request()));
    await page.getByTestId('course-bouton-enregistrer').click();
    const rp = await reponsePut;
    expect(rp.status()).toBe(200);
    expect(new URL(rp.url()).pathname).toBe(`/api/administration/courses/${a.id}`);
    await verifierFerme(page, 'course');
    const succes = page.getByTestId('course-message-succes');
    await expect(succes).toHaveText(`La course ${a.nom} a été modifiée.`);
    expect(await succes.evaluate((el) => !!el.closest('form'))).toBe(false);
    await expect(ligneCourse(page, a.nom).getByTestId('course-duree')).toHaveText('45 min');
    await expect(page.getByTestId('course-bouton-nouvelle')).toBeFocused();
    expect(getsParId).toHaveLength(0);

    // Annuler : fermé, aucun nouveau PUT
    await ligneCourse(page, a.nom).getByTestId('course-bouton-modifier').click();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await page.getByTestId('course-bouton-annuler').click();
    await verifierFerme(page, 'course');
    expect(puts).toHaveLength(1);

    // « Nouvelle course » pendant une modification : rien ne change
    await ligneCourse(page, a.nom).getByTestId('course-bouton-modifier').click();
    await page.getByTestId('course-champ-duree').fill('33');
    await page.getByTestId('course-bouton-nouvelle').click();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('33');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(a.nom);
    await expect(page.getByTestId('course-bouton-nouvelle')).toHaveAttribute('aria-expanded', 'true');
    await page.getByTestId('course-bouton-annuler').click();
    await verifierFerme(page, 'course');
    expect(puts).toHaveLength(1);

    // 409 COURSE_NON_MODIFIABLE : reste ouvert en déclaration, vide, avec le message, liste relue
    const detail = "La course n'est plus en préparation : elle ne peut plus être modifiée.";
    await page.route('**/api/administration/courses/*', (route) =>
      route.request().method() === 'PUT' ? route.fulfill(probleme(409, 'COURSE_NON_MODIFIABLE', detail)) : route.continue(),
    );
    await ligneCourse(page, b.nom).getByTestId('course-bouton-modifier').click();
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(b.nom);
    const listesAvant = listes;
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText(detail);
    await expect(page.getByTestId('course-formulaire')).toBeVisible();
    await expect(page.getByTestId('course-bouton-nouvelle')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue('');
    expect(listes).toBeGreaterThan(listesAvant);
    await page.unroute('**/api/administration/courses/*');

    // suppression de la Course en cours de modification : le formulaire se ferme
    await ligneCourse(page, b.nom).getByTestId('course-bouton-modifier').click();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    const l = ligneCourse(page, b.nom);
    await l.getByTestId('course-bouton-supprimer').click();
    await l.getByTestId('course-bouton-confirmer-suppression').click();
    await expect(ligneCourse(page, b.nom)).toHaveCount(0);
    await verifierFerme(page, 'course');
  });

  /* ---------------------------------------------------------------- CA8 */

  for (const f of PAGES) {
    test(`CA8 - ${f.chemin} : ordre de Tab, Entrée sur le bouton, Maj+Tab, Entrée sur Annuler, Échap sans effet et contours entiers`, async ({ page, playwright }) => {
      test.setTimeout(120_000);
      const fm = FORMULAIRES[f.nom];
      const gardes: string[] = [];
      if (f.nom === 'course') {
        gardes.push((await creerCourse(playwright)).nom, (await creerCourse(playwright)).nom);
      } else {
        gardes.push(f.nom === 'admin' ? await creerAdmin(playwright) : await creerBenevole(playwright));
        gardes.push(f.nom === 'admin' ? await creerAdmin(playwright) : await creerBenevole(playwright));
      }
      await filtrerListe(page, f, gardes);
      await ouvrirEn(page, f);
      await expect(page.getByTestId(f.ligne)).toHaveCount(2);

      interface Arret { testid: string | null; dansEntete: boolean; style: string; largeur: string; couleur: string; ancetres: string[] }
      const lireArret = async (): Promise<Arret | null> =>
        page.evaluate(() => {
          const el = document.activeElement as HTMLElement | null;
          if (!el || el === document.body) return null;
          const cs = getComputedStyle(el);
          const ancetres: string[] = [];
          for (let n = el.parentElement; n && n.tagName !== 'MAIN' && n !== document.body; n = n.parentElement) {
            const s = getComputedStyle(n);
            if (s.overflowX !== 'visible' || s.overflowY !== 'visible') ancetres.push(`${n.tagName.toLowerCase()}.${n.className}`);
          }
          return { testid: el.getAttribute('data-testid'), dansEntete: !!el.closest('.entete'), style: cs.outlineStyle, largeur: cs.outlineWidth, couleur: cs.outlineColor, ancetres };
        });
      const tabuler = async (touche: 'Tab' | 'Shift+Tab', jusqua: string, maximum = 60): Promise<Arret[]> => {
        const arrets: Arret[] = [];
        for (let i = 0; i < maximum; i++) {
          await page.keyboard.press(touche);
          const a = await lireArret();
          expect(a, `${touche} : le focus a quitté la page avant ${jusqua}`).not.toBeNull();
          arrets.push(a!);
          if (a!.testid === jusqua) break;
        }
        return arrets;
      };
      const verifierContours = (arrets: Arret[]): void => {
        for (const a of arrets.filter((x) => !x.dansEntete)) {
          expect(a.style, `${a.testid} : style`).toBe('solid');
          expect(a.largeur, `${a.testid} : épaisseur`).toBe('3px');
          expect(a.couleur, `${a.testid} : couleur`).toBe(FOCUS);
          expect(a.ancetres, `${a.testid} : ancêtres qui rognent`).toEqual([]);
        }
      };

      // formulaire fermé : menu, bouton d'ouverture, actions des lignes, retour ; aucun champ
      await page.getByTestId('entete-titre').focus();
      const fermes = await tabuler('Tab', 'lien-retour-administration');
      const horsEntete = fermes.filter((a) => !a.dansEntete);
      expect(fermes.some((a) => a.dansEntete), 'bouton du menu de l\'en-tête').toBe(true);
      expect(horsEntete.slice(0, 3).map((a) => a.testid)).toEqual(['menu-administration-courses', 'menu-administration-admins', 'menu-administration-benevoles']);
      expect(horsEntete[3].testid, 'bouton d\'ouverture juste après le menu d\'administration').toBe(fm.bouton);
      expect(horsEntete.at(-1)!.testid).toBe('lien-retour-administration');
      const entreBoutonEtRetour = horsEntete.slice(4, -1).map((a) => a.testid ?? '');
      expect(entreBoutonEtRetour.length, 'actions des lignes').toBeGreaterThan(0);
      expect(entreBoutonEtRetour.filter((t) => /-champ-|-bouton-creer|-bouton-annuler|-bouton-enregistrer/.test(t) && !/logo/.test(t))).toEqual([]);
      verifierContours(fermes);

      // Entrée sur le bouton : focus sur le premier champ, puis les champs, le bouton principal, « Annuler », le retour
      await page.getByTestId(fm.bouton).focus();
      await page.keyboard.press('Enter');
      await expect(page.getByTestId(fm.formulaire)).toBeVisible();
      await expect(page.getByTestId(fm.premierChamp)).toBeFocused();
      const complet = [...f.champs, f.boutonCreer, f.boutonAnnuler];
      const ouverts = await tabuler('Tab', 'lien-retour-administration');
      expect(ouverts.map((a) => a.testid)).toEqual([...complet.slice(1), 'lien-retour-administration']);
      verifierContours(ouverts);

      // Maj+Tab : sens inverse
      const inverses = await tabuler('Shift+Tab', f.champs[0], complet.length);
      expect(inverses.map((a) => a.testid)).toEqual([...complet].reverse());
      verifierContours(inverses);

      // Entrée sur « Annuler » : ferme et rend le focus au bouton
      await page.getByTestId(f.boutonAnnuler).focus();
      await page.keyboard.press('Enter');
      await verifierFerme(page, f.nom);
      await expect(page.getByTestId(fm.bouton)).toBeFocused();

      // Échap n'a aucun effet
      await ouvrirFormulaire(page, f.nom);
      await page.keyboard.press('Escape');
      await expect(page.getByTestId(fm.formulaire)).toBeVisible();
      await expect(page.getByTestId(fm.bouton)).toHaveAttribute('aria-expanded', 'true');
    });
  }

  /* ---------------------------------------------------------------- CA9 */

  for (const f of PAGES) {
    test(`CA9 - ${f.chemin} : rôles et noms accessibles du bouton, du formulaire, du message de succès et des erreurs`, async ({ page }) => {
      const fm = FORMULAIRES[f.nom];
      await ouvrirEn(page, f);
      const bouton = page.getByRole('button', { name: f.libelleBouton, exact: true });
      const formulaire = page.getByRole('form', { name: f.titreFormulaire, exact: true });

      await expect(bouton).toHaveCount(1);
      await expect(bouton).toHaveAttribute('data-testid', fm.bouton);
      await expect(formulaire).toHaveCount(0);

      await ouvrirFormulaire(page, f.nom);
      await expect(page.getByRole('button', { name: f.libelleBouton, exact: true })).toHaveCount(1);
      await expect(formulaire).toHaveCount(1);
      await expect(formulaire).toBeVisible();

      // une erreur de champ affichée a role="alert"
      await page.getByTestId(f.boutonCreer).click();
      const premiereErreur = f.nom === 'course' ? 'course-erreur-nom' : `${f.nom}-erreur-pseudo`;
      await expect(page.getByTestId(premiereErreur)).toHaveAttribute('role', 'alert');

      // le message de succès a role="status" (création réelle)
      if (f.nom === 'course') {
        await remplirCourse(page, nomCourseUnique(), dateDansJours(30));
      } else {
        await remplirCompte(page, f.nom, f.nom === 'admin' ? pseudoAdminUnique() : pseudoBenevoleUnique());
      }
      await page.getByTestId(f.boutonCreer).click();
      await verifierFerme(page, f.nom);
      await expect(page.getByTestId(`${f.nom}-message-succes`)).toHaveAttribute('role', 'status');
      await expect(formulaire).toHaveCount(0);
      await expect(page.getByRole('button', { name: f.libelleBouton, exact: true })).toHaveCount(1);
    });
  }

  /* ---------------------------------------------------------------- CA10 */

  for (const f of PAGES) {
    test(`CA10 - ${f.chemin} : ouvrir, annuler, rouvrir et fermer n'émettent aucune requête, quitter la page perd l'état`, async ({ page }) => {
      const fm = FORMULAIRES[f.nom];
      await ouvrirEn(page, f);
      const requetes = toutesRequetesApi(page);

      await ouvrirFormulaire(page, f.nom);
      await page.getByTestId(f.boutonAnnuler).click();
      await verifierFerme(page, f.nom);
      await ouvrirFormulaire(page, f.nom);
      await page.getByTestId(fm.premierChamp).fill('abc');
      await page.getByTestId(f.boutonAnnuler).click();
      await verifierFerme(page, f.nom);
      expect(requetes.map((r) => `${r.method()} ${r.url()}`)).toEqual([]);

      // quitter la page par le menu d'administration puis y revenir
      await ouvrirFormulaire(page, f.nom);
      await page.getByTestId(fm.premierChamp).fill('abc');
      const autre = f.nom === 'course' ? 'menu-administration-benevoles' : 'menu-administration-courses';
      await page.getByTestId(autre).click();
      await expect(page.getByTestId(fm.bouton)).toHaveCount(0);
      await page.getByTestId(`menu-administration-${f.nom === 'course' ? 'courses' : f.nom + 's'}`).click();
      await attendreEcran(page, f);
      await verifierFerme(page, f.nom);
      await ouvrirFormulaire(page, f.nom);
      await expect(page.getByTestId(fm.premierChamp)).toHaveValue('');
    });
  }

  /* ---------------------------------------------------------------- CA11 */

  test('CA11 - un logo envoyé ou une autre Course supprimée ne ferme pas le formulaire ni ne touche à la saisie', async ({ page, playwright }) => {
    test.setTimeout(120_000);
    const pourLogo = await creerCourse(playwright);
    const aSupprimer = await creerCourse(playwright);
    await ouvrirEn(page, PAGE_COURSES);
    await ouvrirFormulaire(page, 'course');
    await page.getByTestId('course-champ-nom').fill('Course E2E');

    // suppression d'une autre Course et relecture de la liste
    const l = ligneCourse(page, aSupprimer.nom);
    await l.getByTestId('course-bouton-supprimer').click();
    await l.getByTestId('course-bouton-confirmer-suppression').click();
    await expect(ligneCourse(page, aSupprimer.nom)).toHaveCount(0);
    await expect(page.getByTestId('course-bouton-nouvelle')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('course-formulaire')).toBeVisible();
    await expect(page.getByTestId('course-champ-nom')).toHaveValue('Course E2E');

    // logo d'une autre Course
    const la = ligneCourse(page, pourLogo.nom);
    await la.getByTestId('course-bouton-logo').click();
    await la.getByTestId('course-champ-logo').setInputFiles(join(FIXTURES, 'logo.png'));
    await la.getByTestId('course-bouton-logo-envoyer').click();
    const succes = page.getByTestId('course-message-succes');
    await expect(succes).toHaveText(`Le logo de la course ${pourLogo.nom} a été enregistré.`);
    expect(await succes.evaluate((el) => !!el.closest('form'))).toBe(false);
    await expect(page.getByTestId('course-bouton-nouvelle')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('course-formulaire')).toBeVisible();
    await expect(page.getByTestId('course-champ-nom')).toHaveValue('Course E2E');
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
  });

  /* ---------------------------------------------------------------- CA12 */

  for (const f of PAGES) {
    test(`CA12 - ${f.chemin} : 320 x 640 et 360 x 740, texte à 200 %, mouvement réduit et contraste forcé`, async ({ page, playwright }) => {
      test.setTimeout(180_000);
      const fm = FORMULAIRES[f.nom];
      await creerCourse(playwright);
      await ouvrirEn(page, f);

      for (const [largeur, hauteur] of [[320, 640], [360, 740]] as const) {
        for (const grand of [false, true]) {
          const contexte = `${f.chemin} ${largeur} px${grand ? ' à 200 %' : ''}`;
          await page.setViewportSize({ width: largeur, height: hauteur });
          await page.goto(f.chemin);
          await attendreEcran(page, f);
          if (grand) await page.addStyleTag({ content: AGRANDIR });

          const bouton = page.getByTestId(fm.bouton);
          await expect(bouton).toBeVisible();
          expect((await bouton.boundingBox())!.height, `${contexte} : hauteur du bouton`).toBeGreaterThanOrEqual(44);
          const tronque = await bouton.evaluate((el) => el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1);
          expect(tronque, `${contexte} : libellé tronqué`).toBe(false);
          await sansDefilementHorizontal(page, `${contexte} fermé`);

          await ouvrirFormulaire(page, f.nom);
          await page.getByTestId(f.boutonCreer).click();
          await expect(page.getByTestId(f.nom === 'course' ? 'course-erreur-nom' : `${f.nom}-erreur-pseudo`)).toBeVisible();
          await sansDefilementHorizontal(page, `${contexte} ouvert avec erreurs`);
          expect(await debordementsFormulaire(page, f), `${contexte} : débordement de l'encart`).toEqual([]);
          for (const id of [f.boutonCreer, f.boutonAnnuler]) {
            const b = (await page.getByTestId(id).boundingBox())!;
            expect(b.height, `${contexte} : ${id}`).toBeGreaterThanOrEqual(44);
            expect(b.x + b.width, `${contexte} : ${id} dans la largeur`).toBeLessThanOrEqual(largeur + 0.5);
          }
        }
      }

      // mouvement réduit : aucune animation à l'ouverture ni à la fermeture, transition nulle
      await page.setViewportSize({ width: 1280, height: 800 });
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.goto(f.chemin);
      await attendreEcran(page, f);
      await ouvrirFormulaire(page, f.nom);
      expect(await page.evaluate(() => document.getAnimations().length), 'animations à l\'ouverture').toBe(0);
      await page.getByTestId(f.boutonAnnuler).click();
      await verifierFerme(page, f.nom);
      expect(await page.evaluate(() => document.getAnimations().length), 'animations à la fermeture').toBe(0);
      await expect(page.getByTestId(fm.bouton)).toHaveCSS('transition-duration', '0s');

      // contraste forcé : bordure du formulaire ouvert d'au moins 1 px
      await page.emulateMedia({ reducedMotion: 'no-preference', forcedColors: 'active' });
      await page.goto(f.chemin);
      await attendreEcran(page, f);
      await ouvrirFormulaire(page, f.nom);
      const bordure = await page.getByTestId(fm.formulaire).evaluate((el) => ({ largeur: parseFloat(getComputedStyle(el).borderTopWidth), style: getComputedStyle(el).borderTopStyle }));
      expect(bordure.largeur).toBeGreaterThanOrEqual(1);
      expect(bordure.style).not.toBe('none');
      await page.emulateMedia({ forcedColors: 'none' });
    });
  }

  test('CA12 - les scènes « formulaire ouvert » des trois pages respectent contrastes AA, palette et bordure de champ >= 3', async ({ page, playwright }) => {
    test.setTimeout(150_000);
    const jeu = await preparerJeuReference(playwright);
    const autorisees = [...RGB_PALETTE, RGB_TRANSPARENT];
    for (const f of PAGES) {
      const scene = ETATS.find((e) => e.nom === `gestion des ${f.nom === 'course' ? 'courses' : f.nom === 'admin' ? 'admins' : 'benevoles'} formulaire ouvert`);
      expect(scene, `scène « formulaire ouvert » de ${f.chemin}`).toBeDefined();
      await page.context().clearCookies();
      await ouvrirScene(page, scene!, jeu);
      await page.mouse.move(0, 0);
      await expect(page.getByTestId(FORMULAIRES[f.nom].formulaire)).toBeVisible();
      const ecarts = (await page.evaluate((a) => (window as unknown as { __t: { horsPalette(x: string[]): EcartCouleur[] } }).__t.horsPalette(a), autorisees)) as EcartCouleur[];
      expect(ecarts, `${scene!.nom} : couleurs hors palette`).toEqual([]);
      const mesures = (await page.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
      expect(mesures.length).toBeGreaterThan(0);
      expect(mesures.filter((m) => m.ratio + 1e-9 < m.seuil).map((m) => `${m.element} ${m.ratio.toFixed(2)} < ${m.seuil}`), `${scene!.nom} : contrastes`).toEqual([]);
      const bordures = mesures.filter((m) => m.type === 'bordure-champ' && m.element.startsWith(f.champs[0]));
      expect(bordures.length, `${scene!.nom} : bordure du premier champ mesurée`).toBeGreaterThan(0);
      for (const m of bordures) expect(m.ratio, `${scene!.nom} : bordure de champ`).toBeGreaterThanOrEqual(3);
      await page.evaluate(AIDES_PAGE);
    }
  });

  /* ---------------------------------------------------------------- CA13 */

  for (const f of PAGES) {
    test(`CA13 - ${f.chemin} : l'état vide désigne le bouton d'ouverture et la première création le remplace par une ligne`, async ({ page }) => {
      test.setTimeout(90_000);
      const fm = FORMULAIRES[f.nom];
      let creeReellement = false;
      await page.route(f.api, async (route) => {
        const methode = route.request().method();
        if (methode === 'POST') {
          creeReellement = true;
          return route.continue();
        }
        if (methode === 'GET' && !creeReellement) {
          return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
        }
        return route.continue();
      });
      await ouvrirEn(page, f);

      const vide = page.getByTestId(f.vide);
      await expect(vide).toBeVisible();
      await expect(vide).toHaveText(f.texteVide);
      await expect(page.getByText(f.aideVide, { exact: true })).toBeVisible();
      await expect(vide.getByText(f.aideVide)).toHaveCount(0);
      await expect(vide).not.toContainText(/Utilisez|Créez-en un/);
      await expect(page.getByTestId(fm.bouton)).toBeVisible();

      await ouvrirFormulaire(page, f.nom);
      let nom: string;
      if (f.nom === 'course') {
        nom = nomCourseUnique();
        await remplirCourse(page, nom, dateDansJours(30));
      } else {
        nom = f.nom === 'admin' ? pseudoAdminUnique() : pseudoBenevoleUnique();
        await remplirCompte(page, f.nom, nom);
      }
      await page.getByTestId(f.boutonCreer).click();
      await verifierFerme(page, f.nom);
      await expect(page.getByTestId(f.vide)).toHaveCount(0);
      await expect(page.getByTestId(f.ligne).filter({ hasText: nom })).toHaveCount(1);
    });
  }

  /* ---------------------------------------------------------------- CA14 */

  test('CA14 - les trois scènes « formulaire ouvert » existent et un formulaire fermé n\'accepte aucune saisie', async ({ page }) => {
    for (const nom of ['gestion des courses formulaire ouvert', 'gestion des admins formulaire ouvert', 'gestion des benevoles formulaire ouvert']) {
      expect(ETATS.some((e) => e.nom === nom), `scène ${nom}`).toBe(true);
    }
    for (const f of PAGES) {
      await ouvrirEn(page, f);
      await verifierFerme(page, f.nom);
      // le formulaire est dans le DOM mais masqué : une saisie directe échoue tant qu'il n'est pas ouvert
      await expect(page.getByTestId(FORMULAIRES[f.nom].premierChamp).fill('x', { timeout: 1000 })).rejects.toThrow();
      await ouvrirFormulaire(page, f.nom);
      await page.getByTestId(FORMULAIRES[f.nom].premierChamp).fill('x');
      await expect(page.getByTestId(FORMULAIRES[f.nom].premierChamp)).toHaveValue('x');
    }
  });
});
