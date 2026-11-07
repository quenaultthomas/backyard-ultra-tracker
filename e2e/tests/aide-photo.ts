import { expect, type APIRequestContext } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { MOT_DE_PASSE } from './aide-connexion';
import { connecterParApi } from './aide-admin';

/** Aides des E2E de la photo de profil (10.1a) : tout passe par l'API ; le volume se lit par `docker compose exec`. */

export const RACINE = join(__dirname, '..', '..');
export const JETON_PHOTO = /^[0-9a-f]{32}$/;

/** Se connecte (cookie de session dans `request`) et relit le jeton CSRF. */
export async function sessionCoureur(request: APIRequestContext, pseudo: string, motDePasse = MOT_DE_PASSE): Promise<string> {
  await connecterParApi(request, pseudo, motDePasse);
  await request.get('/api/csrf');
  const jeton = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  return jeton!;
}

export async function photoUrlDeMoi(request: APIRequestContext): Promise<string | null> {
  const r = await request.get('/api/comptes/moi');
  expect(r.status()).toBe(200);
  return ((await r.json()) as { photoUrl: string | null }).photoUrl;
}

/** PUT multipart de la partie `fichier` ; renvoie la réponse brute. */
export async function envoyerPhoto(request: APIRequestContext, csrf: string, buffer: Buffer, mimeType = 'image/png', name = 'photo.png') {
  return request.put('/api/comptes/moi/photo', { headers: { 'X-XSRF-TOKEN': csrf }, multipart: { fichier: { name, mimeType, buffer } } });
}

/** Pose une photo et renvoie le jeton (32 hexadécimaux) tiré de `photoUrl`. */
export async function poserPhoto(request: APIRequestContext, csrf: string, buffer: Buffer, mimeType = 'image/png', name = 'photo.png'): Promise<string> {
  const r = await envoyerPhoto(request, csrf, buffer, mimeType, name);
  expect(r.status()).toBe(200);
  const url = ((await r.json()) as { photoUrl: string }).photoUrl;
  const jeton = url.replace('/api/photos/', '');
  expect(jeton).toMatch(JETON_PHOTO);
  return jeton;
}

/** Noms des fichiers du volume des photos (dans le conteneur `api`). */
export function fichiersDuVolume(): string[] {
  const sortie = execFileSync('docker', ['compose', 'exec', '-T', 'api', 'ls', '-1', '/donnees/photos'], { cwd: RACINE, encoding: 'utf8', stdio: 'pipe' });
  return sortie.split('\n').map((l) => l.trim()).filter((l) => l !== '');
}
