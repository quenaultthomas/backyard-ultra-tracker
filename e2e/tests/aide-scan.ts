import { expect, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_BENEVOLE_CREE,
  affecterBenevolesParApi,
  courseDeReference,
  creerBenevoleParApi,
  creerCourseParApi,
  dateDansJours,
  demarrerCourseParApi,
  pseudoBenevoleUnique,
} from './aide-admin';
import { lireJetonQrEnBase, sinscrireParApi } from './aide-coureur';
import { avecApi } from './aide-horloge';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export interface Coureur {
  pseudo: string;
  inscriptionId: string;
  dossard: number;
  jeton: string;
}

export interface CourseDeScan {
  id: string;
  nom: string;
  benevole: string;
  coureurs: Coureur[];
}

export const urlScan = (courseId: string) => `/benevole/courses/${courseId}/scan`;

/**
 * Course de `dureeBoucleMinutes` (60 par défaut), datée du jour, avec `nombreCoureurs` inscrits (dossards 1, 2…),
 * un bénévole affecté (créé si absent) et, par défaut, démarrée par l'API.
 */
export async function preparerCourseDeScan(
  playwright: PlaywrightLib,
  options: { nombreCoureurs?: number; nombreMaxBoucles?: number; dureeBoucleMinutes?: number; demarree?: boolean; benevole?: string; pseudos?: string[] } = {},
): Promise<CourseDeScan> {
  const { nombreCoureurs = 2, nombreMaxBoucles = 5, dureeBoucleMinutes = 60, demarree = true } = options;
  const benevole = options.benevole ?? pseudoBenevoleUnique();
  if (!options.benevole) await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, benevole));
  const donnees = courseDeReference({ date: dateDansJours(0), dureeBoucleMinutes, nombreMaxBoucles });
  const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, donnees));
  const id = course['id'] as string;
  await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, id, [benevole]));
  const coureurs: Coureur[] = [];
  for (let i = 0; i < nombreCoureurs; i++) {
    const pseudo = options.pseudos?.[i] ?? pseudoUnique('coureur');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
    const inscription = await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, id));
    coureurs.push({ pseudo, inscriptionId: inscription.id, dossard: inscription.dossard, jeton: lireJetonQrEnBase(inscription.id) });
  }
  if (demarree) await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, id));
  return { id, nom: donnees.nom, benevole, coureurs };
}

/** Course sans coureur ni démarrage, avec le bénévole donné affecté. */
export async function creerCourseAffectee(playwright: PlaywrightLib, benevole: string): Promise<{ id: string; nom: string }> {
  const donnees = courseDeReference({ date: dateDansJours(0), dureeBoucleMinutes: 60, nombreMaxBoucles: 5 });
  const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, donnees));
  const id = course['id'] as string;
  await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, id, [benevole]));
  return { id, nom: donnees.nom };
}

export async function creerBenevole(playwright: PlaywrightLib): Promise<string> {
  const pseudo = pseudoBenevoleUnique();
  await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, pseudo));
  return pseudo;
}

/** Connexion par l'écran ; `retour` (chemin applicatif) est suivi après la connexion. */
export async function seConnecterVers(page: Page, pseudo: string, motDePasse: string, retour?: string): Promise<void> {
  await ouvrirConnexion(page, retour ? `/connexion?retour=${encodeURIComponent(retour)}` : '/connexion');
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

export async function ouvrirEcranDeScan(page: Page, course: CourseDeScan): Promise<void> {
  await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
  await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
  await expect(page.getByTestId('scan-champ-jeton')).toBeVisible();
}

export const champ = (page: Page) => page.getByTestId('scan-champ-jeton');

/** Colle le jeton (remplissage) et valide par Entrée. */
export async function scannerParEntree(page: Page, jeton: string): Promise<void> {
  await champ(page).fill(jeton);
  await champ(page).press('Enter');
}

export async function verifierSucces(page: Page, dossard: number, pseudo: string, boucle: number): Promise<void> {
  await expect(page.getByTestId('scan-resultat-succes')).toContainText('Passage enregistré');
  await expect(page.getByTestId('scan-resultat-dossard')).toHaveText(`Dossard ${dossard}`);
  await expect(page.getByTestId('scan-resultat-pseudo')).toHaveText(pseudo);
  await expect(page.getByTestId('scan-resultat-boucle')).toHaveText(`Boucle ${boucle}`);
}

function executerSql(requete: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'base', 'psql', '-U', process.env.E2E_BASE_UTILISATEUR ?? 'backyard', '-d', process.env.E2E_BASE_NOM ?? 'backyard', '-t', '-A', '-c', requete],
    { cwd: join(__dirname, '..', '..'), stdio: 'pipe', encoding: 'utf8' },
  ).trim();
}

/** Passages de la Course : lignes « dossard:boucle:origine », triées. */
export function lirePassagesEnBase(courseId: string): string[] {
  if (!/^[0-9a-f-]{36}$/.test(courseId)) throw new Error('identifiant de course invalide');
  const sortie = executerSql(
    `select i.dossard || ':' || p.numero_boucle || ':' || p.origine from passage p join inscription i on i.id = p.inscription_id where i.course_id = '${courseId}' order by i.dossard, p.numero_boucle`,
  );
  return sortie === '' ? [] : sortie.split('\n');
}

export interface InscriptionEnBase {
  statut: string;
  motifAbandon: string;
  boucleAbandon: string;
}

/** Statut, motif et Boucle d'abandon de l'Inscription de ce dossard (4.8) ; champs vides si NULL. */
export function lireInscriptionEnBase(courseId: string, dossard: number): InscriptionEnBase {
  if (!/^[0-9a-f-]{36}$/.test(courseId)) throw new Error('identifiant de course invalide');
  if (!Number.isInteger(dossard)) throw new Error('dossard invalide');
  const sortie = executerSql(
    `select statut || '|' || coalesce(motif_abandon, '') || '|' || coalesce(boucle_abandon::text, '') from inscription where course_id = '${courseId}' and dossard = ${dossard}`,
  );
  const [statut, motifAbandon, boucleAbandon] = sortie.split('|');
  return { statut, motifAbandon, boucleAbandon };
}

/** Statut de la Course en base (4.9) : `EN_PREPARATION`, `EN_COURS` ou `TERMINEE`. */
export function lireStatutCourseEnBase(courseId: string): string {
  if (!/^[0-9a-f-]{36}$/.test(courseId)) throw new Error('identifiant de course invalide');
  return executerSql(`select statut from course where id = '${courseId}'`);
}
