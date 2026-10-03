import { expect, test, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
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
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
  type DonneesCourse,
} from './aide-admin';
import { creerCompteParApi, MOT_DE_PASSE } from './aide-connexion';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const MOTIF_LISTE = '**/api/administration/courses';
const MOTIF_COURSE = '**/api/administration/courses/*';
const RETOUR_COURSES = /\/connexion\?retour=%2Fadministration%2Fcourses$/;
const CHAMPS = ['nom', 'date', 'distance', 'duree', 'denivele', 'participants-max', 'boucles-max'];

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

async function ouvrirGestionCourses(page: Page): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
  await page.goto('/administration/courses');
  await expect(page.getByTestId('titre-courses')).toBeVisible();
  await expect(page.getByTestId('liste-courses').or(page.getByTestId('courses-vide'))).toBeVisible();
}

function ligne(page: Page, nom: string) {
  return page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });
}

async function modifier(page: Page, nom: string): Promise<void> {
  await ligne(page, nom).getByTestId('course-bouton-modifier').click();
  await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
}

async function lireCourses(page: Page): Promise<Array<Record<string, unknown>>> {
  const reponse = await page.request.get('/api/administration/courses');
  expect(reponse.status()).toBe(200);
  return (await reponse.json()) as Array<Record<string, unknown>>;
}

function estPut(r: Request): boolean {
  return r.method() === 'PUT' && r.url().includes('/api/administration/courses/');
}

async function verifierChampsVides(page: Page): Promise<void> {
  for (const c of CHAMPS) {
    await expect(page.getByTestId(`course-champ-${c}`)).toHaveValue('');
  }
}

async function remplir(page: Page, d: Record<string, string>): Promise<void> {
  for (const [cle, valeur] of Object.entries(d)) {
    await page.getByTestId(`course-champ-${cle}`).fill(valeur);
  }
}

const probleme = (status: number, code: string, detail = '') => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ status, code, detail }),
});

test.describe('Modification d\'une course', () => {
  test('CA19 - un admin modifie une course depuis la liste : formulaire pré-rempli, enregistrement, liste à jour', async ({ page, browser, playwright }) => {
    const donnees = courseDeReference();
    const nom = donnees.nom;
    const date = donnees.date;
    const creee = await creerCourse(playwright, donnees);
    const id = creee['id'] as string;
    const autre = courseDeReference({ date: dateDansJours(35) });
    await creerCourse(playwright, autre);

    const getsParId: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'GET' && /\/api\/administration\/courses\/[^/]+$/.test(r.url())) getsParId.push(r.url());
    });
    await ouvrirGestionCourses(page);
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    await modifier(page, nom);

    await expect(page.getByTestId('course-champ-nom')).toHaveValue(nom);
    await expect(page.getByTestId('course-champ-date')).toHaveValue(dateAffichee(date));
    await expect(page.getByTestId('course-champ-distance')).toHaveValue('6706');
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('60');
    await expect(page.getByTestId('course-champ-denivele')).toHaveValue('120');
    await expect(page.getByTestId('course-champ-participants-max')).toHaveValue('50');
    await expect(page.getByTestId('course-champ-boucles-max')).toHaveValue('24');
    await expect(page.getByTestId('course-bouton-enregistrer')).toBeVisible();
    await expect(page.getByTestId('course-bouton-creer')).toHaveCount(0);
    expect(getsParId).toHaveLength(0);

    await page.getByTestId('course-champ-duree').fill('45');
    const requete = page.waitForRequest(estPut);
    await page.getByTestId('course-bouton-enregistrer').click();
    const put = await requete;
    expect(new URL(put.url()).pathname).toBe(`/api/administration/courses/${id}`);
    expect(put.postDataJSON().date).toBe(date);
    expect(put.postDataJSON().dureeBoucleMinutes).toBe(45);

    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été modifiée.`);
    const l = ligne(page, nom);
    await expect(l).toHaveCount(1);
    await expect(l.getByTestId('course-duree')).toHaveText('45 min');
    await expect(l.getByTestId('course-distance')).toHaveText('6706 m');
    await expect(l.getByTestId('course-denivele')).toHaveText('120 m');
    await expect(l.getByTestId('course-participants-max')).toHaveText('50');
    await expect(l.getByTestId('course-boucles-max')).toHaveText('24');
    await expect(l.getByTestId('course-date')).toHaveText(dateAffichee(date));
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    await verifierChampsVides(page);
    expect(getsParId).toHaveLength(0);

    let courses = await lireCourses(page);
    expect(courses.find((c) => c['id'] === id)!['dureeBoucleMinutes']).toBe(45);
    const intacte = courses.find((c) => c['nom'] === autre.nom)!;
    expect(intacte['dureeBoucleMinutes']).toBe(60);
    expect(intacte['date']).toBe(autre.date);

    // même modification par un ADMIN non master
    const admin = pseudoAdminUnique();
    await creerAdmin(playwright, admin);
    const contexte = await browser.newContext();
    const pageAdmin = await contexte.newPage();
    try {
      await ouvrirConnexion(pageAdmin);
      await saisir(pageAdmin, admin, MOT_DE_PASSE_ADMIN_CREE);
      await pageAdmin.getByTestId('bouton-connexion').click();
      await expect(pageAdmin).toHaveURL(/\/administration$/);
      await pageAdmin.goto('/administration/courses');
      await modifier(pageAdmin, nom);
      await pageAdmin.getByTestId('course-champ-duree').fill('40');
      await pageAdmin.getByTestId('course-bouton-enregistrer').click();
      await expect(pageAdmin.getByTestId('course-message-succes')).toHaveText(`La course ${nom} a été modifiée.`);
      await expect(ligne(pageAdmin, nom).getByTestId('course-duree')).toHaveText('40 min');
    } finally {
      await contexte.close();
    }

    // changement de nom et de date : la ligne se déplace selon l'ordre de l'API (date décroissante)
    const nomBis = `${nom} bis`;
    await page.reload();
    await modifier(page, nom);
    await remplir(page, { nom: nomBis, date: dateAffichee(dateDansJours(40)) });
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nomBis} a été modifiée.`);
    await expect(ligne(page, nomBis)).toHaveCount(1);
    await expect(ligne(page, nom)).toHaveCount(0);
    await expect(ligne(page, nomBis).getByTestId('course-date')).toHaveText(dateAffichee(dateDansJours(40)));
    const noms = await page.getByTestId('course-nom').allTextContents();
    expect(noms.indexOf(nomBis)).toBeGreaterThanOrEqual(0);
    expect(noms.indexOf(nomBis)).toBeLessThan(noms.indexOf(autre.nom));
    courses = await lireCourses(page);
    const apiNoms = courses.map((c) => c['nom']);
    expect(apiNoms.indexOf(nomBis)).toBeLessThan(apiNoms.indexOf(autre.nom));
    const modifiee = courses.find((c) => c['id'] === id)!;
    expect(modifiee['statut']).toBe('EN_PREPARATION');
    expect(modifiee['date']).toBe(dateDansJours(40));
  });

  test('CA20 - annuler, changer de course en édition, puis déclarer après une modification', async ({ page, playwright }) => {
    const a = courseDeReference({ dureeBoucleMinutes: 60, nombreMaxParticipants: 10 });
    const b = courseDeReference({ dureeBoucleMinutes: 30, nombreMaxParticipants: 20, distanceBoucleMetres: 4000 });
    await creerCourse(playwright, a);
    await creerCourse(playwright, b);
    await ouvrirGestionCourses(page);

    const puts: string[] = [];
    const posts: string[] = [];
    page.on('request', (r) => {
      if (estPut(r)) puts.push(r.url());
      if (r.method() === 'POST' && r.url().includes('/api/administration/courses')) posts.push(r.url());
    });

    await modifier(page, a.nom);
    await page.getByTestId('course-champ-duree').fill('15');
    await page.getByTestId('course-bouton-annuler').click();
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    await expect(page.getByTestId('course-bouton-creer')).toBeVisible();
    await expect(page.getByTestId('course-bouton-annuler')).toHaveCount(0);
    await verifierChampsVides(page);
    await expect(ligne(page, a.nom).getByTestId('course-duree')).toHaveText('60 min');
    expect(puts).toHaveLength(0);

    await modifier(page, a.nom);
    await page.getByTestId('course-champ-duree').fill('15');
    await modifier(page, b.nom);
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(b.nom);
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('30');
    await expect(page.getByTestId('course-champ-distance')).toHaveValue('4000');
    await expect(page.getByTestId('course-champ-participants-max')).toHaveValue('20');
    expect(puts).toHaveLength(0);

    // succès de modification puis déclaration : POST, pas PUT
    await page.getByTestId('course-champ-duree').fill('25');
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${b.nom} a été modifiée.`);
    expect(puts).toHaveLength(1);
    await expect(page.getByTestId('course-bouton-creer')).toBeVisible();

    const nouvelle = courseDeReference();
    await remplir(page, {
      nom: nouvelle.nom, date: dateAffichee(nouvelle.date), distance: '5000', duree: '50', denivele: '10',
      'participants-max': '5', 'boucles-max': '3',
    });
    await page.getByTestId('course-bouton-creer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nouvelle.nom} a été déclarée.`);
    expect(posts).toHaveLength(1);
    expect(puts).toHaveLength(1);
    await expect(ligne(page, nouvelle.nom)).toHaveCount(1);
    await expect(ligne(page, b.nom).getByTestId('course-duree')).toHaveText('25 min');
    await expect(ligne(page, b.nom)).toHaveCount(1);
  });

  test('CA21 - les contrôles côté écran en mode édition bloquent l\'envoi', async ({ page, playwright }) => {
    const donnees = courseDeReference();
    await creerCourse(playwright, donnees);
    await ouvrirGestionCourses(page);
    const puts: string[] = [];
    page.on('request', (r) => {
      if (estPut(r)) puts.push(r.url());
    });
    await modifier(page, donnees.nom);
    for (const c of CHAMPS) {
      await page.getByTestId(`course-champ-${c}`).fill('');
    }
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-erreur-nom')).toHaveText('Le nom est obligatoire.');
    await expect(page.getByTestId('course-erreur-date')).toHaveText('La date est obligatoire.');
    await expect(page.getByTestId('course-erreur-distance')).toHaveText('La distance d\'une boucle est obligatoire.');
    await expect(page.getByTestId('course-erreur-duree')).toHaveText('La durée d\'une boucle est obligatoire.');
    await expect(page.getByTestId('course-erreur-denivele')).toHaveText('Le dénivelé positif d\'une boucle est obligatoire.');
    await expect(page.getByTestId('course-erreur-participants-max')).toHaveText('Le nombre maximum de participants est obligatoire.');
    await expect(page.getByTestId('course-erreur-boucles-max')).toHaveText('Le nombre maximum de boucles est obligatoire.');

    for (const valeur of ['0', '-5']) {
      await page.getByTestId('course-champ-distance').fill(valeur);
      await page.getByTestId('course-bouton-enregistrer').click();
      await expect(page.getByTestId('course-erreur-distance')).toHaveText('La distance d\'une boucle doit être un nombre entier supérieur à 0.');
    }
    await page.getByTestId('course-champ-denivele').fill('-5');
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-erreur-denivele')).toHaveText(
      'Le dénivelé positif d\'une boucle doit être un nombre entier supérieur ou égal à 0.',
    );
    await page.getByTestId('course-champ-denivele').fill('0');
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-erreur-denivele')).toHaveCount(0);
    await expect(page.getByTestId('course-erreur-nom')).toBeVisible();

    for (const saisie of ['31/02/2026', '14-11-2026', '2026-11-14', '14/11/26', '1/2/2026', '29/02/2027', 'abc']) {
      await page.getByTestId('course-champ-date').fill(saisie);
      await page.getByTestId('course-bouton-enregistrer').click();
      await expect(page.getByTestId('course-erreur-date'), `saisie ${saisie}`).toHaveText('La date doit être au format jj/mm/aaaa.');
    }
    expect(puts).toHaveLength(0);
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
  });

  test('CA22 - les erreurs de validation du serveur en mode édition sont affichées, les valeurs conservées', async ({ page, playwright }) => {
    const donnees = courseDeReference();
    await creerCourse(playwright, donnees);
    await ouvrirGestionCourses(page);
    await modifier(page, donnees.nom);
    const hierAffiche = dateAffichee(dateDansJours(-1));
    await remplir(page, {
      nom: 'ab', date: hierAffiche, distance: '50001', duree: '1441', denivele: '10001', 'participants-max': '5001', 'boucles-max': '501',
    });
    await page.getByTestId('course-bouton-enregistrer').click();

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
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await expect(page.getByTestId('course-message-succes')).toHaveCount(0);
    const l = ligne(page, donnees.nom);
    await expect(l.getByTestId('course-distance')).toHaveText('6706 m');
    await expect(l.getByTestId('course-duree')).toHaveText('60 min');
    await expect(l.getByTestId('course-date')).toHaveText(dateAffichee(donnees.date));

    const nomCorrige = `${donnees.nom} corrigée`;
    await remplir(page, {
      nom: nomCorrige, date: dateAffichee(donnees.date), distance: '7000', duree: '55', denivele: '0',
      'participants-max': '60', 'boucles-max': '10',
    });
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${nomCorrige} a été modifiée.`);
    const corrigee = ligne(page, nomCorrige);
    await expect(corrigee.getByTestId('course-distance')).toHaveText('7000 m');
    await expect(corrigee.getByTestId('course-duree')).toHaveText('55 min');
    await expect(corrigee.getByTestId('course-denivele')).toHaveText('0 m');
    await expect(page.getByTestId('course-erreur-nom')).toHaveCount(0);
    await verifierChampsVides(page);
  });

  test('CA23 - bouton absent hors EN_PREPARATION, date passée inchangée, erreurs 409, 404, CSRF, 500, 401, 403', async ({ page }) => {
    const suffixe = nomCourseUnique('Stub');
    const base = {
      date: '2026-10-01', distanceBoucleMetres: 6706, dureeBoucleMinutes: 60, denivelePositifBoucleMetres: 120,
      nombreMaxParticipants: 50, nombreMaxBoucles: 24,
    };
    const preparation = { ...base, id: '11111111-1111-4111-8111-111111111111', nom: `${suffixe} prep`, statut: 'EN_PREPARATION' };
    const enCours = { ...base, id: '22222222-2222-4222-8222-222222222222', nom: `${suffixe} cours`, statut: 'EN_COURS' };
    const terminee = { ...base, id: '33333333-3333-4333-8333-333333333333', nom: `${suffixe} fin`, statut: 'TERMINEE' };

    let courses: Array<Record<string, unknown>> = [preparation, enCours, terminee];
    let reponsePut: { status: number; contentType: string; body: string } = probleme(500, 'ERREUR_INTERNE');
    let apresPut: (() => void) | null = null;
    let nbListes = 0;
    const puts: Request[] = [];

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await page.route(MOTIF_LISTE, (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      nbListes++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(courses) });
    });
    await page.route(MOTIF_COURSE, (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      puts.push(route.request());
      apresPut?.();
      return route.fulfill(reponsePut);
    });

    const ouvrir = async () => {
      await page.goto('/administration/courses');
      await expect(page.getByTestId('liste-courses')).toBeVisible();
    };
    await ouvrir();

    await expect(page.getByTestId('ligne-course')).toHaveCount(3);
    await expect(page.getByTestId('course-bouton-modifier')).toHaveCount(1);
    await expect(ligne(page, preparation.nom).getByTestId('course-bouton-modifier')).toBeVisible();
    await expect(ligne(page, enCours.nom).getByTestId('course-bouton-modifier')).toHaveCount(0);
    await expect(ligne(page, terminee.nom).getByTestId('course-bouton-modifier')).toHaveCount(0);

    // date passée inchangée : pré-remplie et envoyée telle quelle
    await modifier(page, preparation.nom);
    await expect(page.getByTestId('course-champ-date')).toHaveValue('01/10/2026');
    await page.getByTestId('course-champ-duree').fill('45');
    reponsePut = {
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ ...preparation, dureeBoucleMinutes: 45 }),
    };
    courses = [{ ...preparation, dureeBoucleMinutes: 45 }, enCours, terminee];
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-message-succes')).toHaveText(`La course ${preparation.nom} a été modifiée.`);
    expect(puts).toHaveLength(1);
    expect(puts[0].postDataJSON().date).toBe('2026-10-01');
    expect(puts[0].postDataJSON().dureeBoucleMinutes).toBe(45);
    expect(new URL(puts[0].url()).pathname).toBe(`/api/administration/courses/${preparation.id}`);

    // 409 : detail affiché, liste rechargée, mode édition quitté
    courses = [preparation, enCours, terminee];
    await ouvrir();
    await modifier(page, preparation.nom);
    await page.getByTestId('course-champ-duree').fill('45');
    reponsePut = probleme(409, 'COURSE_NON_MODIFIABLE', "La course n'est plus en préparation : elle ne peut plus être modifiée.");
    apresPut = () => {
      courses = [{ ...preparation, statut: 'EN_COURS' }, enCours, terminee];
    };
    const listesAvant = nbListes;
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText(
      "La course n'est plus en préparation : elle ne peut plus être modifiée.",
    );
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    await expect(page.getByTestId('course-bouton-modifier')).toHaveCount(0);
    expect(nbListes).toBeGreaterThan(listesAvant);
    apresPut = null;

    // 404 : message, mode édition quitté
    courses = [preparation, enCours, terminee];
    await ouvrir();
    await modifier(page, preparation.nom);
    reponsePut = probleme(404, 'COURSE_INTROUVABLE', 'La course est introuvable.');
    const listes404 = nbListes;
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText("Cette course n'existe plus.");
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    expect(nbListes).toBeGreaterThan(listes404);

    // 403 CSRF : message, nouveau jeton, valeurs conservées
    await ouvrir();
    await modifier(page, preparation.nom);
    await page.getByTestId('course-champ-duree').fill('33');
    reponsePut = probleme(403, 'CSRF_INVALIDE');
    const nouveauJeton = page.waitForResponse((r) => r.url().includes('/api/csrf'));
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText('La page a expiré, veuillez réessayer.');
    await nouveauJeton;
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('33');
    await expect(page.getByTestId('course-champ-nom')).toHaveValue(preparation.nom);
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');

    // 500 : message, valeurs et mode édition conservés
    await ouvrir();
    await modifier(page, preparation.nom);
    await page.getByTestId('course-champ-duree').fill('34');
    reponsePut = probleme(500, 'ERREUR_INTERNE');
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page.getByTestId('course-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await expect(page.getByTestId('course-champ-duree')).toHaveValue('34');
    await expect(page.getByTestId('course-champ-date')).toHaveValue('01/10/2026');
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Modifier la course');
    await expect(page.getByTestId('course-bouton-enregistrer')).toBeEnabled();

    // 403 ACCES_REFUSE
    await ouvrir();
    await modifier(page, preparation.nom);
    reponsePut = probleme(403, 'ACCES_REFUSE');
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page).toHaveURL(/\/acces-refuse$/);

    // 401
    await ouvrir();
    await modifier(page, preparation.nom);
    reponsePut = probleme(401, 'NON_AUTHENTIFIE');
    await page.getByTestId('course-bouton-enregistrer').click();
    await expect(page).toHaveURL(RETOUR_COURSES);
  });

  test('CA24 - non-régression : titre de déclaration, accès coureur et bénévole refusé', async ({ page, browser, request, playwright }) => {
    const coureur = pseudoUnique('coureur');
    await creerCompteParApi(request, coureur);
    const benevole = pseudoBenevoleUnique();
    const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
    try {
      await creerBenevoleParApi(ctx, benevole);
    } finally {
      await ctx.dispose();
    }

    await ouvrirGestionCourses(page);
    await expect(page.getByTestId('course-formulaire-titre')).toHaveText('Déclarer une course');
    await expect(page.getByTestId('course-bouton-creer')).toBeVisible();
    await expect(page.getByTestId('course-bouton-enregistrer')).toHaveCount(0);
    await expect(page.getByTestId('course-bouton-annuler')).toHaveCount(0);

    for (const [pseudo, mdp] of [[coureur, MOT_DE_PASSE], [benevole, MOT_DE_PASSE_BENEVOLE_CREE]]) {
      const contexte = await browser.newContext();
      const p = await contexte.newPage();
      try {
        await p.goto('/administration/courses');
        await expect(p).toHaveURL(RETOUR_COURSES);
        await saisir(p, pseudo, mdp);
        await p.getByTestId('bouton-connexion').click();
        await expect(p).toHaveURL(/\/acces-refuse$/);
        await expect(p.getByTestId('titre-acces-refuse')).toHaveText('Accès refusé');
      } finally {
        await contexte.close();
      }
    }
  });
});
