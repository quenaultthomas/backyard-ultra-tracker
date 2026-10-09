import { expect, test, type Page } from '@playwright/test';
import { spawn } from 'node:child_process';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import {
  MOT_DE_PASSE_ADMIN_MASTER,
  PSEUDO_ADMIN_MASTER,
  connecterAdminMaster,
  creerAdminParApi,
  creerCourseParApi,
  connecterParApi,
  exigerIdentifiantsAdminMaster,
  type DonneesCourse,
} from './aide-admin';
import { ouvrirConnexion, saisir } from './aide-connexion';

/*
 * Scénarios du script scripts/donnees-demo.sh (incrément 2.1b, complété en 3.5 et 4.1 CA19).
 * Un compte ne se supprime pas et une course ne se supprime qu'à partir de 2.5 : les scénarios qui supposent
 * « rien n'existe » exigent une base neuve. Trois états de départ, choisis par DEMO_SCENARIO (une base neuve chacun) :
 *   neuve (défaut) : CA1 puis CA2 (relance), plus CA3 et CA4 qui n'en dépendent pas ;
 *   partiel        : CA2, Nadia et « Backyard express » créés seuls avant le script ;
 *   mini           : CA2, « Backyard mini » créée avant le script avec 2000 m.
 */
const SCENARIO = process.env.DEMO_SCENARIO ?? 'neuve';
const RACINE = resolve(__dirname, '../..');
const SCRIPT = join(RACINE, 'scripts/donnees-demo.sh');
const BASE_URL = process.env.BASE_URL ?? 'http://localhost';

const COMPTES = [
  { pseudo: 'Nadia', motDePasse: 'mot-de-passe-admin-1', role: 'ADMIN' },
  { pseudo: 'Léo', motDePasse: 'mot-de-passe-benevole-1', role: 'BENEVOLE' },
  { pseudo: 'Marc', motDePasse: 'mot-de-passe-benevole-1', role: 'BENEVOLE' },
  { pseudo: 'Alice', motDePasse: 'un-mot-de-passe-12', role: 'COUREUR' },
  { pseudo: 'Karim', motDePasse: 'un-mot-de-passe-12', role: 'COUREUR' },
  { pseudo: 'Sophie', motDePasse: 'un-mot-de-passe-12', role: 'COUREUR' },
  { pseudo: 'Tom', motDePasse: 'un-mot-de-passe-12', role: 'COUREUR' },
];
const NOMS_COURSES = ['Backyard de démo', 'Backyard express', 'Backyard mini', 'Backyard du jour'];
const ELEMENTS = ['Nadia', 'Léo', 'Marc', 'Alice', 'Karim', 'Sophie', 'Tom', ...NOMS_COURSES];
/** Inscriptions de démonstration (3.5 CA13, 4.1 CA19) : pseudo et course, dans l'ordre de création. */
const INSCRIPTIONS: Array<[string, string]> = [
  ['Karim', 'Backyard de démo'],
  ['Sophie', 'Backyard de démo'],
  ['Tom', 'Backyard de démo'],
  ['Karim', 'Backyard express'],
  ['Sophie', 'Backyard express'],
  ['Karim', 'Backyard du jour'],
  ['Sophie', 'Backyard du jour'],
  ['Tom', 'Backyard du jour'],
];
const MOTS_DE_PASSE_CONNUS = [...COMPTES.map((c) => c.motDePasse)];

interface Resultat {
  code: number | null;
  sortie: string;
  erreur: string;
  dureeMs: number;
  tmp: string;
}

interface Options {
  env?: Record<string, string>;
  /** Variables d'environnement à retirer de l'environnement hérité. */
  sans?: string[];
  cwd?: string;
}

/** Exécute le script comme processus enfant ; TMPDIR dédié pour détecter tout fichier résiduel. */
function lancerScript(options: Options = {}): Promise<Resultat> {
  const tmp = mkdtempSync(join(tmpdir(), 'demo-tmp-'));
  const env: Record<string, string> = {};
  for (const [cle, valeur] of Object.entries(process.env)) {
    if (valeur !== undefined) env[cle] = valeur;
  }
  for (const cle of ['ADMIN_MASTER_PSEUDO', 'ADMIN_MASTER_MOT_DE_PASSE', 'DEMO_FORCER', 'BASE_URL', ...(options.sans ?? [])]) {
    delete env[cle];
  }
  Object.assign(env, { BASE_URL, TMPDIR: tmp }, options.env);
  const debut = Date.now();
  return new Promise((resolvePromesse, rejeter) => {
    const enfant = spawn('bash', [SCRIPT], { cwd: options.cwd ?? RACINE, env });
    let sortie = '';
    let erreur = '';
    enfant.stdout.on('data', (d) => (sortie += d));
    enfant.stderr.on('data', (d) => (erreur += d));
    enfant.on('error', rejeter);
    enfant.on('close', (code) => resolvePromesse({ code, sortie, erreur, dureeMs: Date.now() - debut, tmp }));
  });
}

function envAdminMaster(): Record<string, string> {
  return { ADMIN_MASTER_PSEUDO: PSEUDO_ADMIN_MASTER, ADMIN_MASTER_MOT_DE_PASSE: MOT_DE_PASSE_ADMIN_MASTER };
}

function lignes(texte: string, prefixe: string): string[] {
  return texte.split('\n').filter((l) => l.startsWith(prefixe));
}

function verifierAucunSecret(r: Resultat): void {
  const tout = `${r.sortie}\n${r.erreur}`;
  for (const secret of [MOT_DE_PASSE_ADMIN_MASTER, ...MOTS_DE_PASSE_CONNUS]) {
    expect(tout).not.toContain(secret);
  }
  expect(readdirSync(r.tmp)).toEqual([]);
}

function verifierSeptElements(r: Resultat, etat: 'créé' | 'déjà présent'): void {
  const lignesEtat = lignes(r.sortie, `${etat} : `);
  // 20 lignes (4.1 CA19) : 7 comptes, 4 courses, l'affectation de Léo à « Backyard de démo » (2.4) et 8 inscriptions
  expect(lignesEtat).toHaveLength(20);
  for (const [pseudo, course] of INSCRIPTIONS) {
    expect(lignesEtat.filter((l) => l === `${etat} : ${pseudo} (inscription à ${course})`), `${pseudo} / ${course}`).toHaveLength(1);
  }
  expect(lignesEtat.filter((l) => l.includes('(inscription à '))).toHaveLength(8);
  expect(lignesEtat.filter((l) => l.includes('Alice') && l.includes('inscription'))).toHaveLength(0);
  expect(lignesEtat.filter((l) => l.includes('inscription à Backyard mini'))).toHaveLength(0);
  expect(lignesEtat.filter((l) => l.includes('Léo affecté à Backyard de démo'))).toHaveLength(1);
  for (const element of ELEMENTS) {
    expect(lignesEtat.filter((l) => l.includes(element))).not.toHaveLength(0);
  }
}

/** Date du jour + N jours à Paris, comme le script (TZ=Europe/Paris) et l'API (4.1 RG4). */
function jourPlus(jours: number): string {
  const aujourdhui = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Paris' }).format(new Date());
  const d = new Date(`${aujourdhui}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + jours);
  return d.toISOString().slice(0, 10);
}

async function coursesParApi(request: Parameters<typeof connecterParApi>[0]): Promise<Array<Record<string, unknown>>> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const reponse = await request.get('/api/administration/courses');
  expect(reponse.status()).toBe(200);
  return (await reponse.json()) as Array<Record<string, unknown>>;
}

async function verifierConnexionComptes(playwright: Parameters<Parameters<typeof test>[2]>[0]['playwright']): Promise<void> {
  for (const compte of COMPTES) {
    const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
    try {
      const csrf = await ctx.get('/api/csrf');
      expect(csrf.status()).toBe(204);
      const jeton = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value;
      const connexion = await ctx.post('/api/connexion', {
        headers: { 'X-XSRF-TOKEN': jeton },
        data: { pseudo: compte.pseudo, motDePasse: compte.motDePasse },
      });
      expect(connexion.status(), `connexion de ${compte.pseudo}`).toBe(200);
      const corps = (await connexion.json()) as { pseudo: string; role: string };
      expect(corps.pseudo).toBe(compte.pseudo);
      expect(corps.role).toBe(compte.role);
    } finally {
      await ctx.dispose();
    }
  }
}

async function lireLigneCourse(page: Page, nom: string): Promise<Record<string, string>> {
  const ligne = page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });
  await expect(ligne).toHaveCount(1);
  const champs = ['date', 'statut', 'distance', 'duree', 'denivele', 'participants-max', 'boucles-max'];
  const resultat: Record<string, string> = {};
  for (const champ of champs) {
    resultat[champ] = ((await ligne.getByTestId(`course-${champ}`).textContent()) ?? '').trim();
  }
  return resultat;
}

/** CA13 : dossards et places restantes des inscriptions de démo, lus par l'API en admin master. */
async function verifierInscriptionsDemo(request: Parameters<typeof connecterParApi>[0]): Promise<void> {
  const courses = await coursesParApi(request);
  const attendu: Record<string, { pseudos: string[]; places: number }> = {
    'Backyard de démo': { pseudos: ['Karim', 'Sophie', 'Tom'], places: 47 },
    'Backyard express': { pseudos: ['Karim', 'Sophie'], places: 8 },
    'Backyard mini': { pseudos: [], places: 2 },
    'Backyard du jour': { pseudos: ['Karim', 'Sophie', 'Tom'], places: 7 },
  };
  for (const [nom, { pseudos, places }] of Object.entries(attendu)) {
    const course = courses.find((c) => c['nom'] === nom);
    expect(course, nom).toBeTruthy();
    const reponse = await request.get(`/api/administration/courses/${course!['id']}/inscriptions`);
    expect(reponse.status(), nom).toBe(200);
    const corps = (await reponse.json()) as { nombreInscrits: number; placesRestantes: number; inscrits: Array<{ dossard: number; pseudo: string }> };
    expect(corps.nombreInscrits, nom).toBe(pseudos.length);
    expect(corps.placesRestantes, nom).toBe(places);
    expect(corps.inscrits.map((i) => [i.dossard, i.pseudo]), nom).toEqual(pseudos.map((p, i) => [i + 1, p]));
  }
}

type Contexte = Parameters<typeof connecterParApi>[0];

/** Contexte de requêtes isolé (une session par préparation de données). */
async function avecContexte(playwright: { request: { newContext: (o: { baseURL: string }) => Promise<Contexte & { dispose: () => Promise<void> }> } }, action: (ctx: Contexte) => Promise<unknown>): Promise<void> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

const affichee = (iso: string) => iso.split('-').reverse().join('/');

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test.describe('Données de démonstration, base neuve', () => {
  test.skip(SCENARIO !== 'neuve', 'scénario réservé à une base neuve (DEMO_SCENARIO=neuve)');
  test.describe.configure({ mode: 'serial' });

  test('CA1 et CA13 - le premier lancement crée vingt éléments, les comptes se connectent et les courses sont listées', async ({ page, request, playwright }) => {
    const r = await lancerScript({ env: envAdminMaster() });
    expect(r.code, `sortie : ${r.sortie}\nerreur : ${r.erreur}`).toBe(0);
    verifierSeptElements(r, 'créé');
    expect(lignes(r.sortie, 'déjà présent')).toHaveLength(0);
    expect(r.sortie).toContain('créé : Nadia (compte ADMIN)');
    verifierAucunSecret(r);

    await verifierConnexionComptes(playwright);

    const courses = await coursesParApi(request);
    const attendues: Record<string, DonneesCourse> = {
      'Backyard de démo': { nom: 'Backyard de démo', date: jourPlus(30), distanceBoucleMetres: 6706, dureeBoucleMinutes: 60, denivelePositifBoucleMetres: 120, nombreMaxParticipants: 50, nombreMaxBoucles: 24 },
      'Backyard express': { nom: 'Backyard express', date: jourPlus(7), distanceBoucleMetres: 400, dureeBoucleMinutes: 1, denivelePositifBoucleMetres: 5, nombreMaxParticipants: 10, nombreMaxBoucles: 5 },
      'Backyard mini': { nom: 'Backyard mini', date: jourPlus(14), distanceBoucleMetres: 1000, dureeBoucleMinutes: 2, denivelePositifBoucleMetres: 10, nombreMaxParticipants: 2, nombreMaxBoucles: 2 },
      'Backyard du jour': { nom: 'Backyard du jour', date: jourPlus(0), distanceBoucleMetres: 400, dureeBoucleMinutes: 1, denivelePositifBoucleMetres: 5, nombreMaxParticipants: 10, nombreMaxBoucles: 5 },
    };
    for (const [nom, attendue] of Object.entries(attendues)) {
      const trouvees = courses.filter((c) => c['nom'] === nom);
      expect(trouvees, nom).toHaveLength(1);
      expect(trouvees[0]).toMatchObject({ ...attendue, statut: 'EN_PREPARATION' });
    }

    await avecContexte(playwright, verifierInscriptionsDemo);

    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto('/administration/courses');
    await expect(page.getByTestId('titre-courses')).toBeVisible();
    const noms = await page.getByTestId('course-nom').allTextContents();
    const positions = NOMS_COURSES.map((n) => noms.indexOf(n));
    positions.forEach((p, i) => expect(p, NOMS_COURSES[i]).toBeGreaterThanOrEqual(0));
    expect(noms.indexOf('Backyard de démo')).toBeLessThan(noms.indexOf('Backyard mini'));
    expect(noms.indexOf('Backyard mini')).toBeLessThan(noms.indexOf('Backyard express'));
    expect(noms.indexOf('Backyard express')).toBeLessThan(noms.indexOf('Backyard du jour'));

    const ecran: Array<[string, number, number, number, number, number, number]> = [
      ['Backyard de démo', 30, 6706, 60, 120, 50, 24],
      ['Backyard mini', 14, 1000, 2, 10, 2, 2],
      ['Backyard express', 7, 400, 1, 5, 10, 5],
      ['Backyard du jour', 0, 400, 1, 5, 10, 5],
    ];
    for (const [nom, jours, distance, duree, denivele, participants, boucles] of ecran) {
      expect(await lireLigneCourse(page, nom), nom).toEqual({
        date: affichee(jourPlus(jours)),
        statut: 'En préparation',
        distance: `${distance} m`,
        duree: `${duree} min`,
        denivele: `${denivele} m`,
        'participants-max': String(participants),
        'boucles-max': String(boucles),
      });
    }
  });

  test('CA2 et CA13 - la relance ne crée rien : vingt éléments déjà présents et une seule course de chaque nom', async ({ request }) => {
    const r = await lancerScript({ env: envAdminMaster() });
    expect(r.code, `sortie : ${r.sortie}\nerreur : ${r.erreur}`).toBe(0);
    verifierSeptElements(r, 'déjà présent');
    expect(lignes(r.sortie, 'créé')).toHaveLength(0);
    verifierAucunSecret(r);

    const courses = await coursesParApi(request);
    for (const nom of NOMS_COURSES) {
      expect(courses.filter((c) => c['nom'] === nom), nom).toHaveLength(1);
    }
  });

  // Dernier test de la série : démarrer « Backyard du jour » est irréversible (le script échouerait ensuite sur ses inscriptions).
  test('CA19 - Nadia démarre « Backyard du jour » (3 inscrits) et se voit refuser le démarrage de « Backyard express » et « Backyard mini »', async ({ page }) => {
    await ouvrirConnexion(page);
    await saisir(page, 'Nadia', 'mot-de-passe-admin-1');
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(/\/administration$/);

    const ouvrirFiche = async (nom: string) => {
      await page.goto('/administration/courses');
      const ligne = page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(nom, { exact: true }) });
      await expect(ligne).toHaveCount(1);
      await ligne.getByTestId('course-lien-fiche').click();
      await expect(page.getByTestId('fiche-titre')).toHaveText(nom);
    };
    const demarrer = async () => {
      await page.getByTestId('fiche-bouton-demarrer').click();
      await page.getByTestId('fiche-bouton-confirmer-demarrage').click();
    };

    // refus de date : express (J+7) et mini (J+14, aussi sans inscrit : le message de date prime)
    for (const nom of ['Backyard express', 'Backyard mini']) {
      await ouvrirFiche(nom);
      await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
      await demarrer();
      await expect(page.getByTestId('fiche-erreur-demarrage'), nom).toHaveText('La course ne peut être démarrée que le jour de sa date.');
      await expect(page.getByTestId('fiche-statut'), nom).toHaveText('En préparation');
      await expect(page.getByTestId('fiche-bouton-demarrer'), nom).toBeEnabled();
    }

    // la Course du jour : 3 inscrits, démarrage accepté
    await ouvrirFiche('Backyard du jour');
    await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
    await expect(page.getByTestId('fiche-inscrits-compteur')).toHaveText('3 inscrits sur 10');
    await demarrer();
    await expect(page.getByTestId('fiche-statut')).toHaveText('En cours');
    await expect(page.getByTestId('fiche-message-demarrage')).toHaveText('La course Backyard du jour a été démarrée.');
    await expect(page.getByTestId('fiche-demarree-le')).toBeVisible();
    await expect(page.getByTestId('fiche-inscrits-compteur')).toHaveText('3 inscrits sur 10');
  });
});

test.describe('Données de démonstration, lancement partiel', () => {
  test.skip(SCENARIO !== 'partiel', 'scénario réservé à une base neuve (DEMO_SCENARIO=partiel)');

  test('CA2 et CA13 - avec Nadia et Backyard express déjà présents, deux éléments déjà présents et dix-huit créés', async ({ playwright }) => {
    await avecContexte(playwright, (ctx) => creerAdminParApi(ctx, 'Nadia', 'mot-de-passe-admin-1'));
    await avecContexte(playwright, (ctx) => creerCourseParApi(ctx, {
      nom: 'Backyard express', date: jourPlus(7), distanceBoucleMetres: 400, dureeBoucleMinutes: 1,
      denivelePositifBoucleMetres: 5, nombreMaxParticipants: 10, nombreMaxBoucles: 5,
    }));

    const r = await lancerScript({ env: envAdminMaster() });
    expect(r.code, `sortie : ${r.sortie}\nerreur : ${r.erreur}`).toBe(0);
    const presents = lignes(r.sortie, 'déjà présent : ');
    const crees = lignes(r.sortie, 'créé : ');
    expect(presents).toHaveLength(2);
    expect(crees).toHaveLength(18);
    expect(presents.join('\n')).toContain('Nadia');
    expect(presents.join('\n')).toContain('Backyard express');
    for (const nom of ['Léo', 'Marc', 'Alice', 'Karim', 'Sophie', 'Tom', 'Backyard de démo', 'Backyard mini', 'Backyard du jour', 'Léo affecté à Backyard de démo']) {
      expect(crees.join('\n')).toContain(nom);
    }
    verifierAucunSecret(r);
  });
});

test.describe('Données de démonstration, course de même nom', () => {
  test.skip(SCENARIO !== 'mini', 'scénario réservé à une base neuve (DEMO_SCENARIO=mini)');

  test('CA2 et CA13 - une course Backyard mini préexistante avec 2000 m n\'est pas modifiée', async ({ request, playwright }) => {
    await avecContexte(playwright, (ctx) => creerCourseParApi(ctx, {
      nom: 'Backyard mini', date: jourPlus(14), distanceBoucleMetres: 2000, dureeBoucleMinutes: 2,
      denivelePositifBoucleMetres: 10, nombreMaxParticipants: 2, nombreMaxBoucles: 2,
    }));

    const r = await lancerScript({ env: envAdminMaster() });
    expect(r.code, `sortie : ${r.sortie}\nerreur : ${r.erreur}`).toBe(0);
    const presents = lignes(r.sortie, 'déjà présent : ');
    expect(presents).toHaveLength(1);
    expect(presents[0]).toContain('Backyard mini');
    expect(lignes(r.sortie, 'créé : ')).toHaveLength(19);

    const mini = (await coursesParApi(request)).filter((c) => c['nom'] === 'Backyard mini');
    expect(mini).toHaveLength(1);
    expect(mini[0]['distanceBoucleMetres']).toBe(2000);
  });
});

test.describe('Données de démonstration, garde-fous', () => {
  let proxy: Server;
  let requetesProxy: string[];
  let urlProxy: string;

  test.beforeAll(async () => {
    requetesProxy = [];
    proxy = createServer((req, res) => {
      requetesProxy.push(`${req.method} ${req.url}`);
      res.statusCode = 502;
      res.end();
    });
    await new Promise<void>((ok) => proxy.listen(0, '127.0.0.1', ok));
    urlProxy = `http://127.0.0.1:${(proxy.address() as AddressInfo).port}`;
  });

  test.afterAll(async () => {
    await new Promise<void>((ok) => proxy.close(() => ok()));
  });

  test('CA3 - une cible distante sans DEMO_FORCER est refusée (code 2) sans aucun appel réseau', async () => {
    for (const forcer of [undefined, 'true', '1']) {
      requetesProxy.length = 0;
      const r = await lancerScript({
        env: { ...envAdminMaster(), BASE_URL: 'http://exemple.invalid', http_proxy: urlProxy, HTTP_PROXY: urlProxy, ...(forcer ? { DEMO_FORCER: forcer } : {}) },
      });
      expect(r.code, `DEMO_FORCER=${forcer}`).toBe(2);
      expect(r.erreur).toContain('cible refusée');
      expect(r.erreur).toContain('exemple.invalid');
      expect(r.sortie).toBe('');
      expect(requetesProxy).toEqual([]);
      expect(r.dureeMs).toBeLessThan(2000);
      verifierAucunSecret(r);
    }
  });

  test('CA3 - avec DEMO_FORCER=oui le script poursuit vers la cible distante', async () => {
    requetesProxy.length = 0;
    const r = await lancerScript({
      env: { ...envAdminMaster(), BASE_URL: 'http://exemple.invalid', DEMO_FORCER: 'oui', http_proxy: urlProxy, HTTP_PROXY: urlProxy },
    });
    expect(r.code).toBe(1);
    expect(requetesProxy.length).toBeGreaterThan(0);
    expect(r.erreur).toContain('HTTP 502');
    verifierAucunSecret(r);
  });

  test('CA3 - sans identifiants ni fichier .env, le script s\'arrête (code 1) en nommant la variable manquante', async () => {
    const vide = mkdtempSync(join(tmpdir(), 'demo-sans-env-'));
    try {
      const sansPseudo = await lancerScript({ cwd: vide });
      expect(sansPseudo.code).toBe(1);
      expect(sansPseudo.erreur).toContain('ADMIN_MASTER_PSEUDO');
      expect(sansPseudo.sortie).toBe('');

      const sansMotDePasse = await lancerScript({ cwd: vide, env: { ADMIN_MASTER_PSEUDO: PSEUDO_ADMIN_MASTER } });
      expect(sansMotDePasse.code).toBe(1);
      expect(sansMotDePasse.erreur).toContain('ADMIN_MASTER_MOT_DE_PASSE');
      expect(sansMotDePasse.erreur).not.toContain(PSEUDO_ADMIN_MASTER);
      verifierAucunSecret(sansMotDePasse);
    } finally {
      rmSync(vide, { recursive: true, force: true });
    }
  });

  test('CA3 - un mauvais mot de passe d\'admin master donne le code 1 avec le statut HTTP 401 et ne crée rien', async () => {
    const r = await lancerScript({ env: { ADMIN_MASTER_PSEUDO: PSEUDO_ADMIN_MASTER, ADMIN_MASTER_MOT_DE_PASSE: 'faux-mot-de-passe-1' } });
    expect(r.code).toBe(1);
    expect(r.erreur).toContain('HTTP 401');
    expect(lignes(r.sortie, 'créé')).toHaveLength(0);
    expect(r.sortie).not.toContain('créé');
    expect(`${r.sortie}${r.erreur}`).not.toContain('faux-mot-de-passe-1');
    expect(readdirSync(r.tmp)).toEqual([]);
  });

  test('CA3 - un mot de passe contenant $, des guillemets et des espaces est lu tel quel depuis le fichier .env', async ({ request }) => {
    exigerIdentifiantsAdminMaster();
    const pseudo = `admin-dollar-${Date.now().toString(36)}`;
    const motDePasse = 'p$a"ss mot $HOME 12';
    await creerAdminParApi(request, pseudo, motDePasse);

    const dossier = mkdtempSync(join(tmpdir(), 'demo-env-'));
    try {
      writeFileSync(join(dossier, '.env'), `ADMIN_MASTER_PSEUDO=${pseudo}\nADMIN_MASTER_MOT_DE_PASSE=${motDePasse}\n`);
      const r = await lancerScript({ cwd: dossier });
      // La connexion réussit (lecture littérale) ; la création de Nadia est ensuite refusée : ce compte est un simple admin.
      expect(r.erreur).not.toContain('connexion');
      expect(r.erreur).not.toContain('manquante');
      expect(r.code).toBe(1);
      expect(r.erreur).toContain('HTTP 403');
      expect(r.erreur).not.toContain(motDePasse);
      verifierAucunSecret(r);

      // Contre-épreuve : un mot de passe différent dans le même .env est bien refusé à la connexion (401).
      writeFileSync(join(dossier, '.env'), `ADMIN_MASTER_PSEUDO=${pseudo}\nADMIN_MASTER_MOT_DE_PASSE=p$a"ss mot 12\n`);
      const refus = await lancerScript({ cwd: dossier });
      expect(refus.code).toBe(1);
      expect(refus.erreur).toContain('HTTP 401');
    } finally {
      rmSync(dossier, { recursive: true, force: true });
    }
  });
});

test.describe('Données de démonstration, documentation', () => {
  const lire = (chemin: string) => readFileSync(join(RACINE, chemin), 'utf8');

  test('CA4 - l\'en-tête du script décrit BASE_URL, DEMO_FORCER et les limites', () => {
    const entete = lire('scripts/donnees-demo.sh').split('\n').filter((l) => l.startsWith('#')).join('\n');
    expect(entete).toContain('BASE_URL');
    expect(entete).toContain('DEMO_FORCER');
    expect(entete).toMatch(/PRODUCTION/i);
    expect(entete).toMatch(/Limites/);
    expect(entete).toMatch(/PSEUDO_DEJA_UTILISE/);
    expect(entete).toMatch(/autre rôle/);
    expect(entete).toMatch(/Course est « déjà présente » si une Course du même nom existe/);
    expect(entete).toMatch(/ni comparée ni modifiée/);
    // 4.1 CA19 : la Course du jour et sa limite (date figée à la première création)
    expect(entete).toContain('Backyard du jour');
    expect(entete).toMatch(/date du jour à Paris/);
    expect(entete).toMatch(/n'est plus démarrable/);
    expect(entete).toMatch(/20 éléments/);
  });

  test('CA4 - docs/deploiement.md porte la mention « à ne pas utiliser en production »', () => {
    expect(lire('docs/deploiement.md')).toMatch(/à ne pas utiliser en production/i);
    expect(lire('docs/deploiement.md')).toContain('donnees-demo.sh');
  });

  test('CA4 - .env.example documente BASE_URL et DEMO_FORCER en commentaire, sans mot de passe', () => {
    const lignesEnv = lire('.env.example').split('\n');
    expect(lignesEnv.some((l) => /^#\s*BASE_URL=/.test(l))).toBe(true);
    expect(lignesEnv.some((l) => /^#\s*DEMO_FORCER=/.test(l))).toBe(true);
    // jamais en variable active
    expect(lignesEnv.some((l) => /^(BASE_URL|DEMO_FORCER)=/.test(l))).toBe(false);
    // aucune valeur de mot de passe
    for (const l of lignesEnv.filter((x) => /^[A-Z_]*MOT_DE_PASSE=/.test(x))) {
      expect(l, l).toMatch(/^[A-Z_]*MOT_DE_PASSE=\s*$/);
    }
    // aucun mot de passe de démonstration
    for (const secret of MOTS_DE_PASSE_CONNUS) expect(lire('.env.example')).not.toContain(secret);
  });

  test('CA4 - docker compose config reste valide avec .env.example copié en .env', () => {
    const verif = spawnSync('docker', ['--version']);
    test.skip(verif.error !== undefined || verif.status !== 0, 'docker absent de cet environnement (vérifié depuis l\'hôte)');
    const dossier = mkdtempSync(join(tmpdir(), 'demo-compose-'));
    try {
      mkdirSync(dossier, { recursive: true });
      writeFileSync(join(dossier, '.env'), readFileSync(join(RACINE, '.env.example')));
      const r = spawnSync('docker', ['compose', '-f', join(RACINE, 'docker-compose.yml'), '--env-file', join(dossier, '.env'), 'config', '-q'], { encoding: 'utf8' });
      expect(r.status, r.stderr).toBe(0);
    } finally {
      rmSync(dossier, { recursive: true, force: true });
    }
    expect(existsSync(dossier)).toBe(false);
  });
});
