import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { MOT_DE_PASSE, ouvrirConnexion, saisir } from './aide-connexion';
import { connecterParApi } from './aide-admin';
import { ouvrirMenuCompte } from './aide-entete';

export interface Inscription {
  id: string;
  courseId: string;
  dossard: number;
  statut: string;
}

/** Connecte le coureur dans le contexte de requêtes donné, relit le jeton CSRF et s'inscrit par l'API. */
export async function sinscrireParApi(request: APIRequestContext, pseudo: string, courseId: string): Promise<Inscription> {
  await connecterParApi(request, pseudo, MOT_DE_PASSE);
  await request.get('/api/csrf');
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  const reponse = await request.post(`/api/coureur/courses/${courseId}/inscriptions`, {
    headers: { 'X-XSRF-TOKEN': jeton! },
  });
  expect(reponse.status()).toBe(201);
  return (await reponse.json()) as Inscription;
}

/** Connexion d'un coureur par l'écran, sans paramètre `retour` : arrivée sur `/`, puis lien « Espace coureur » vers `/coureur`. */
export async function connecterCoureur(page: Page, pseudo: string, motDePasse = MOT_DE_PASSE): Promise<void> {
  await ouvrirConnexion(page);
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
  await ouvrirMenuCompte(page);
  await page.getByTestId('menu-lien-espace-coureur').click();
  await expect(page).toHaveURL(/\/coureur$/);
  await expect(page.getByTestId('coureur-titre')).toBeVisible();
}

export function ligneCoureur(page: Page, nom: string) {
  return page.getByTestId('coureur-ligne-course').filter({ has: page.getByTestId('coureur-course-nom').getByText(nom, { exact: true }) });
}

/**
 * Change le statut d'une course directement en base (aucun endpoint avant 4.1), via `docker compose exec`
 * depuis la racine du dépôt. Les valeurs sont contrôlées : identifiant UUID et statut de l'énumération.
 */
export function placerStatutCourseEnBase(courseId: string, statut: 'EN_PREPARATION' | 'EN_COURS' | 'TERMINEE'): void {
  if (!/^[0-9a-f-]{36}$/.test(courseId)) throw new Error('identifiant de course invalide');
  execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'base', 'psql', '-U', process.env.E2E_BASE_UTILISATEUR ?? 'backyard', '-d', process.env.E2E_BASE_NOM ?? 'backyard',
      '-c', `update course set statut = '${statut}' where id = '${courseId}'`],
    { cwd: join(__dirname, '..', '..'), stdio: 'pipe' },
  );
}

/** Exécute une requête SQL en base (docker compose exec, depuis la racine du dépôt) et renvoie la sortie brute sans décoration. */
function executerSql(requete: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'base', 'psql', '-U', process.env.E2E_BASE_UTILISATEUR ?? 'backyard', '-d', process.env.E2E_BASE_NOM ?? 'backyard', '-t', '-A', '-c', requete],
    { cwd: join(__dirname, '..', '..'), stdio: 'pipe', encoding: 'utf8' },
  ).trim();
}

function exigerUuid(valeur: string): void {
  if (!/^[0-9a-f-]{36}$/.test(valeur)) throw new Error('identifiant invalide');
}

/** Lit la colonne `jeton_qr` d'une inscription : la référence indépendante de l'API. */
export function lireJetonQrEnBase(inscriptionId: string): string {
  exigerUuid(inscriptionId);
  return executerSql(`select jeton_qr from inscription where id = '${inscriptionId}'`);
}

/** Change le statut d'une inscription directement en base (aucun endpoint avant le jalon 4). */
export function placerStatutInscriptionEnBase(inscriptionId: string, statut: 'EN_COURSE' | 'ABANDON' | 'VAINQUEUR'): void {
  exigerUuid(inscriptionId);
  executerSql(`update inscription set statut = '${statut}' where id = '${inscriptionId}'`);
}

/** Indique si la ligne `inscription` existe encore en base. */
export function inscriptionExisteEnBase(inscriptionId: string): boolean {
  exigerUuid(inscriptionId);
  return executerSql(`select count(*) from inscription where id = '${inscriptionId}'`) === '1';
}

/** Se désinscrit par l'API (session du coureur, CSRF relu) et renvoie le statut HTTP. */
export async function seDesinscrireParApi(request: APIRequestContext, pseudo: string, inscriptionId: string): Promise<number> {
  await connecterParApi(request, pseudo, MOT_DE_PASSE);
  await request.get('/api/csrf');
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  const reponse = await request.delete(`/api/coureur/inscriptions/${inscriptionId}`, { headers: { 'X-XSRF-TOKEN': jeton! } });
  return reponse.status();
}
