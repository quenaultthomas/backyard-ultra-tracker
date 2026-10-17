import { expect, type BrowserContext, type Page, type Route } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';

/**
 * Aides de l'incrément 5.1a (file de scan hors ligne) : lecture et écriture d'IndexedDB, suivi des requêtes
 * `passages`, horloge de la page (`page.clock`), réponses d'API simulées.
 */

export const MOTIF_PASSAGES = '**/api/benevole/courses/*/passages';
export const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const MESSAGE_ATTENTE = 'Scan gardé sur l\'appareil. Il sera envoyé dès que possible.';
export const MESSAGE_HORS_LIGNE = 'Hors ligne : les scans sont gardés sur cet appareil.';
export const MESSAGE_STOCKAGE = 'Les scans en attente seront perdus si vous fermez ou rechargez cette page.';

const NOM_BASE = 'backyard-ultra-tracker';
const MAGASIN = 'scans-en-file';

export interface EntreeFile {
  identifiantScan: string;
  compteId: string;
  courseId: string;
  /** Absent d'une entrée `A_VERIFIER` (5.1b RG1 : le jeton est supprimé). */
  jetonQr?: string;
  sequence: number;
  creeLeMural?: number;
  creeLeMonotone?: number;
  sessionPage?: string;
  etat: 'EN_ENVOI' | 'EN_ATTENTE' | 'A_VERIFIER';
  tentatives?: number;
  /** Renseignés pour une entrée `A_VERIFIER` (5.1b RG1). */
  code?: string;
  motif?: string;
  detail?: string;
  dossard?: number;
  pseudo?: string;
  numeroBoucle?: number;
}

export const probleme = (status: number, code: string, detail = 'Détail simulé.', entetes: Record<string, string> = {}) => ({
  status,
  contentType: 'application/problem+json',
  headers: entetes,
  body: JSON.stringify({ title: 'Simulé', status, code, detail }),
});

export function reponseRejeu(dossard: number, pseudo: string, boucle = 1) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      passageId: '11111111-1111-4111-8111-111111111111',
      inscriptionId: '22222222-2222-4222-8222-222222222222',
      dossard,
      pseudo,
      numeroBoucle: boucle,
      scanneLe: new Date().toISOString(),
      origine: 'SCAN',
    }),
  };
}

export interface RequetePassage {
  jetonQr: string;
  identifiantScan: string;
  ageDuScanMillisecondes: number;
  [cle: string]: unknown;
}

/** Corps de toutes les requêtes `POST …/passages` émises par la page (y compris celles qui échouent), dans l'ordre. */
export function suivrePassages(page: Page): RequetePassage[] {
  const requetes: RequetePassage[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/api\/benevole\/courses\/[^/]+\/passages$/.test(new URL(r.url()).pathname)) {
      requetes.push(r.postDataJSON() as RequetePassage);
    }
  });
  return requetes;
}

export const identifiantDe = (route: Route): string => (route.request().postDataJSON() as RequetePassage).identifiantScan;

/** Exceptions JavaScript non gérées de la page (le navigateur journalise lui-même les 401/409 en console : on ne filtre pas `console`). */
export function suivreExceptions(page: Page): Error[] {
  const erreurs: Error[] = [];
  page.on('pageerror', (e) => erreurs.push(e));
  return erreurs;
}

/** Contenu d'IndexedDB (toutes les entrées de la file, tous Comptes confondus), triées par séquence. */
export async function lireFile(page: Page): Promise<EntreeFile[]> {
  return page.evaluate(
    ({ nom, magasin }) =>
      new Promise<EntreeFile[]>((resolve, reject) => {
        const ouverture = indexedDB.open(nom, 1);
        ouverture.onupgradeneeded = () => ouverture.result.createObjectStore(magasin, { keyPath: 'identifiantScan' });
        ouverture.onerror = () => reject(ouverture.error);
        ouverture.onsuccess = () => {
          const base = ouverture.result;
          const lecture = base.transaction(magasin, 'readonly').objectStore(magasin).getAll();
          lecture.onsuccess = () => {
            base.close();
            resolve((lecture.result as EntreeFile[]).sort((a, b) => a.sequence - b.sequence));
          };
          lecture.onerror = () => reject(lecture.error);
        };
      }),
    { nom: NOM_BASE, magasin: MAGASIN },
  );
}

/** Écrit des entrées directement dans IndexedDB (préparation d'une entrée ancienne ou d'un autre Compte). */
export async function ecrireEntrees(page: Page, entrees: EntreeFile[]): Promise<void> {
  await page.evaluate(
    ({ nom, magasin, entrees: aEcrire }) =>
      new Promise<void>((resolve, reject) => {
        const ouverture = indexedDB.open(nom, 1);
        ouverture.onupgradeneeded = () => ouverture.result.createObjectStore(magasin, { keyPath: 'identifiantScan' });
        ouverture.onerror = () => reject(ouverture.error);
        ouverture.onsuccess = () => {
          const base = ouverture.result;
          const transaction = base.transaction(magasin, 'readwrite');
          aEcrire.forEach((e) => transaction.objectStore(magasin).put(e));
          transaction.oncomplete = () => {
            base.close();
            resolve();
          };
          transaction.onerror = () => reject(transaction.error);
        };
      }),
    { nom: NOM_BASE, magasin: MAGASIN, entrees },
  );
}

/** Identifiant du Compte connecté (celui que la file porte dans `compteId`). */
export async function identifiantDuCompte(page: Page): Promise<string> {
  return page.evaluate(async () => ((await (await fetch('/api/comptes/moi')).json()) as { id: string }).id);
}

/**
 * Attend que la file n'ait plus aucun scan à envoyer (`EN_ATTENTE` ou `EN_ENVOI`). Depuis 5.1b, les refus définitifs
 * restent en IndexedDB à l'état `A_VERIFIER` jusqu'à « Compris » : ils ne comptent pas.
 */
export async function attendreFileVide(page: Page): Promise<void> {
  await expect.poll(() => lireFile(page).then((e) => e.filter((x) => x.etat !== 'A_VERIFIER').length)).toBe(0);
}

/** Attend qu'IndexedDB ne contienne plus aucune entrée, `A_VERIFIER` comprises (après « Compris »). */
export async function attendreBaseLocaleVide(page: Page): Promise<void> {
  await expect.poll(() => lireFile(page).then((e) => e.length)).toBe(0);
}

/** Laisse passer un aller-retour réseau réel avec l'application (les réponses en cours sont alors traitées). */
export async function laisserPasserLeReseau(page: Page): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await page.evaluate(() => fetch('/api/csrf').then((r) => r.status));
  }
}

/** Vérifie que `lire()` ne change pas de valeur pendant `millisecondes` (observation d'une absence d'événement). */
export async function resteStable<T>(lire: () => T | Promise<T>, valeur: T, millisecondes = 2000): Promise<void> {
  await expect(async () => expect(JSON.stringify(await lire())).not.toEqual(JSON.stringify(valeur))).not.toPass({ timeout: millisecondes, intervals: [100, 250] });
}

/** Horloge de la page : `page.clock.install()` avant le premier chargement ; le temps continue de s'écouler normalement. */
export async function installerHorloge(page: Page, course: { id: string }): Promise<void> {
  // Les sauts d'horloge de la page vieillissent les scans plus vite que le temps réel : le départ de la Course est
  // reculé (Boucles de 60 min : toujours la Boucle 1) pour qu'un scan ne soit jamais placé avant le départ.
  executerSql(`update course set demarree_le = demarree_le - interval '10 minutes' where id = '${exigerUuid(course.id)}'`);
  await page.clock.install();
}

function exigerUuid(valeur: string): string {
  if (!/^[0-9a-f-]{36}$/.test(valeur)) throw new Error('identifiant invalide');
  return valeur;
}

export const maintenantDeLaPage = (page: Page): Promise<number> => page.evaluate(() => Date.now());

/**
 * Enregistre une route qui note l'instant (horloge de la page) de chaque requête `passages`, laisse `reponse`
 * décider du sort de la requête (numéro 1, 2…) et renvoie les relevés.
 */
export async function routePassagesChronometree(
  page: Page,
  reponse: (numero: number, route: Route) => Promise<void> | void,
): Promise<{ instants: number[]; identifiants: string[]; jetons: string[]; ages: number[] }> {
  const releve = { instants: [] as number[], identifiants: [] as string[], jetons: [] as string[], ages: [] as number[] };
  await page.route(MOTIF_PASSAGES, async (route) => {
    const corps = route.request().postDataJSON() as RequetePassage;
    releve.instants.push(await maintenantDeLaPage(page));
    releve.identifiants.push(corps.identifiantScan);
    releve.jetons.push(corps.jetonQr);
    releve.ages.push(corps.ageDuScanMillisecondes);
    await reponse(releve.instants.length, route);
  });
  return releve;
}

/**
 * Fait avancer l'horloge de la page jusqu'à la `attendu`-ième requête, après avoir vérifié qu'aucune requête ne part
 * avant `minimumSecondes` (saut d'horloge presque jusqu'au minimum, puis pas de 100 ms). Renvoie le délai écoulé (s).
 */
export async function avancerJusquaRequete(
  page: Page,
  releve: { instants: number[] },
  attendu: number,
  minimumSecondes: number,
  maximumSecondes: number,
): Promise<number> {
  const precedent = releve.instants[attendu - 2];
  // La panne est enregistrée (tentatives + 1) juste avant la planification de la nouvelle tentative.
  await expect
    .poll(async () => Math.max(0, ...(await lireFile(page)).map((e) => e.tentatives ?? 0)))
    .toBeGreaterThanOrEqual(attendu - 1);
  await page.clock.runFor(Math.max(0, minimumSecondes * 1000 - 300));
  await laisserPasserLeReseau(page);
  expect(releve.instants.length, `aucune requête avant ${minimumSecondes} s`).toBe(attendu - 1);
  await expect
    .poll(
      async () => {
        await page.clock.runFor(100);
        return releve.instants.length;
      },
      { timeout: 30_000, intervals: [30] },
    )
    .toBe(attendu);
  const delai = (releve.instants[attendu - 1] - precedent) / 1000;
  expect(delai, `délai avant la requête ${attendu}`).toBeGreaterThanOrEqual(minimumSecondes - 0.05);
  expect(delai, `délai avant la requête ${attendu}`).toBeLessThanOrEqual(maximumSecondes + 0.8);
  return delai;
}

/** Coupe puis rétablit le réseau du navigateur : l'événement `online` déclenche un envoi immédiat. */
export async function simulerRetourEnLigne(contexte: BrowserContext): Promise<void> {
  await contexte.setOffline(true);
  await contexte.setOffline(false);
}

function executerSql(requete: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'base', 'psql', '-U', process.env.E2E_BASE_UTILISATEUR ?? 'backyard', '-d', process.env.E2E_BASE_NOM ?? 'backyard', '-t', '-A', '-c', requete],
    { cwd: join(__dirname, '..', '..'), stdio: 'pipe', encoding: 'utf8' },
  ).trim();
}

/** Écart en secondes entre `scanne_le` du Passage de ce dossard (Boucle donnée) et `demarree_le` de la Course. */
export function lireDecalageScanApresDepart(courseId: string, dossard: number, boucle: number): number {
  if (!/^[0-9a-f-]{36}$/.test(courseId) || !Number.isInteger(dossard) || !Number.isInteger(boucle)) throw new Error('paramètre invalide');
  const sortie = executerSql(
    `select extract(epoch from (p.scanne_le - c.demarree_le)) from passage p join inscription i on i.id = p.inscription_id join course c on c.id = i.course_id where c.id = '${courseId}' and i.dossard = ${dossard} and p.numero_boucle = ${boucle}`,
  );
  return Number(sortie);
}
