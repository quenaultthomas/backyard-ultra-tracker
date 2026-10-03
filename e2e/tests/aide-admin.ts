import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';

export const PSEUDO_ADMIN_MASTER = process.env.E2E_ADMIN_MASTER_PSEUDO ?? '';
export const MOT_DE_PASSE_ADMIN_MASTER = process.env.E2E_ADMIN_MASTER_MOT_DE_PASSE ?? '';
export const MOT_DE_PASSE_ADMIN_CREE = 'mot-de-passe-admin-1';

export function exigerIdentifiantsAdminMaster(): void {
  if (!PSEUDO_ADMIN_MASTER || !MOT_DE_PASSE_ADMIN_MASTER) {
    throw new Error('E2E_ADMIN_MASTER_PSEUDO et E2E_ADMIN_MASTER_MOT_DE_PASSE doivent être renseignées (mêmes valeurs que la stack).');
  }
}

async function jetonCsrf(request: APIRequestContext): Promise<string> {
  const csrf = await request.get('/api/csrf');
  expect(csrf.status()).toBe(204);
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  return jeton!;
}

/** Ouvre une session API (contexte isolé) pour le pseudo donné. */
export async function connecterParApi(request: APIRequestContext, pseudo: string, motDePasse: string): Promise<void> {
  const reponse = await request.post('/api/connexion', {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    data: { pseudo, motDePasse },
  });
  expect(reponse.status()).toBe(200);
}

/** Crée un admin par l'API avec la session de l'admin master (contexte isolé). */
export async function creerAdminParApi(request: APIRequestContext, pseudo: string, motDePasse = MOT_DE_PASSE_ADMIN_CREE): Promise<void> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const creation = await request.post('/api/administration/admins', {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    data: { pseudo, motDePasse },
  });
  expect(creation.status()).toBe(201);
}

export function pseudoAdminUnique(): string {
  return pseudoUnique('admin');
}

export async function connecterAdminMaster(page: Page, chemin = '/connexion'): Promise<void> {
  await ouvrirConnexion(page, chemin);
  await saisir(page, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  await page.getByTestId('bouton-connexion').click();
}

export const MOT_DE_PASSE_BENEVOLE_CREE = 'mot-de-passe-benevole-1';

export function pseudoBenevoleUnique(): string {
  return pseudoUnique('benevole');
}

/** Crée un bénévole par l'API avec la session de l'admin master (contexte isolé). */
export async function creerBenevoleParApi(request: APIRequestContext, pseudo: string, motDePasse = MOT_DE_PASSE_BENEVOLE_CREE): Promise<void> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const creation = await request.post('/api/administration/benevoles', {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    data: { pseudo, motDePasse },
  });
  expect(creation.status()).toBe(201);
}

export interface DonneesCourse {
  nom: string;
  date: string;
  distanceBoucleMetres: number;
  dureeBoucleMinutes: number;
  denivelePositifBoucleMetres: number;
  nombreMaxParticipants: number;
  nombreMaxBoucles: number;
}

export function nomCourseUnique(prefixe = 'Course E2E'): string {
  return `${prefixe} ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
}

/** Date `aaaa-mm-jj` à `jours` jours d'aujourd'hui (calcul UTC, sans effet de fuseau à J+30). */
export function dateDansJours(jours: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + jours);
  return d.toISOString().slice(0, 10);
}

/** `aaaa-mm-jj` vers `jj/mm/aaaa`. */
export function dateAffichee(iso: string): string {
  const [a, m, j] = iso.split('-');
  return `${j}/${m}/${a}`;
}

export function courseDeReference(surcharge: Partial<DonneesCourse> = {}): DonneesCourse {
  return {
    nom: nomCourseUnique(),
    date: dateDansJours(30),
    distanceBoucleMetres: 6706,
    dureeBoucleMinutes: 60,
    denivelePositifBoucleMetres: 120,
    nombreMaxParticipants: 50,
    nombreMaxBoucles: 24,
    ...surcharge,
  };
}

/** Déclare une course par l'API avec la session de l'admin master (contexte isolé, une session par appel). */
export async function creerCourseParApi(request: APIRequestContext, donnees: DonneesCourse): Promise<Record<string, unknown>> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const creation = await request.post('/api/administration/courses', {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    data: donnees,
  });
  expect(creation.status()).toBe(201);
  return (await creation.json()) as Record<string, unknown>;
}

/**
 * Envoie un logo par l'API (PUT multipart, partie `fichier`) avec la session de l'admin master
 * (contexte isolé, une session par appel). Renvoie la `CourseReponse` mise à jour.
 */
export async function envoyerLogoParApi(
  request: APIRequestContext,
  courseId: string,
  fichier: { nom: string; type: string; contenu: Buffer },
): Promise<Record<string, unknown>> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const reponse = await request.put(`/api/administration/courses/${courseId}/logo`, {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    multipart: { fichier: { name: fichier.nom, mimeType: fichier.type, buffer: fichier.contenu } },
  });
  expect(reponse.status()).toBe(200);
  return (await reponse.json()) as Record<string, unknown>;
}

/** Identifiants des bénévoles (par pseudo) lus par l'API avec la session de l'admin master (contexte isolé). */
export async function identifiantsBenevolesParApi(request: APIRequestContext, pseudos: string[]): Promise<string[]> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const reponse = await request.get('/api/administration/benevoles');
  expect(reponse.status()).toBe(200);
  const comptes = (await reponse.json()) as Array<{ id: string; pseudo: string }>;
  return pseudos.map((pseudo) => {
    const compte = comptes.find((c) => c.pseudo === pseudo);
    expect(compte, `bénévole ${pseudo} introuvable`).toBeTruthy();
    return compte!.id;
  });
}

/** Remplace les bénévoles affectés à une course par l'API (session de l'admin master, contexte isolé). */
export async function affecterBenevolesParApi(request: APIRequestContext, courseId: string, pseudos: string[]): Promise<void> {
  const ids = await identifiantsBenevolesParApi(request, pseudos);
  const reponse = await request.put(`/api/administration/courses/${courseId}/benevoles`, {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
    data: { benevoleIds: ids },
  });
  expect(reponse.status()).toBe(200);
}

/** Supprime une course par l'API avec la session de l'admin master (contexte isolé). Renvoie le statut HTTP. */
export async function supprimerCourseParApi(request: APIRequestContext, courseId: string): Promise<number> {
  await connecterParApi(request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  const reponse = await request.delete(`/api/administration/courses/${courseId}`, {
    headers: { 'X-XSRF-TOKEN': await jetonCsrf(request) },
  });
  return reponse.status();
}
