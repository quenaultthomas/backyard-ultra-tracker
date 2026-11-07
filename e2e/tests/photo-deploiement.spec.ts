import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { creerCompteParApi, pseudoUnique } from './aide-connexion';
import { avecApi } from './aide-horloge';
import { RACINE, envoyerPhoto, fichiersDuVolume, photoUrlDeMoi, poserPhoto, sessionCoureur } from './aide-photo';

/*
 * Incrément 10.1a, CA33 (partie non intrusive) : déploiement. Le redémarrage de api et la sauvegarde sont dans
 * photo-deploiement.horloge.spec.ts (projet séquentiel).
 */
const png = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));

function compose(...args: string[]): string {
  return execFileSync('docker', ['compose', ...args], { cwd: RACINE, encoding: 'utf8', stdio: 'pipe' });
}

test('CA33 - /api/sante répond via web et les trois services base, api, web sont sains', async ({ request }) => {
  const sante = await request.get('/api/sante');
  expect(sante.status()).toBe(200);
  const etats = compose('ps', '--format', '{{.Service}}={{.Health}}').split('\n').filter((l) => l.trim() !== '');
  for (const service of ['base', 'api', 'web']) expect(etats, service).toContain(`${service}=healthy`);
});

test('CA33 - docker compose config : volume nommé photos monté dans api et variable BACKYARD_PHOTOS_REPERTOIRE, hérités de la production', () => {
  for (const fichiers of [['-f', 'docker-compose.yml'], ['-f', 'docker-compose.yml', '-f', 'docker-compose.prod.yml']]) {
    const json = JSON.parse(execFileSync('docker', ['compose', ...fichiers, 'config', '--format', 'json'], {
      cwd: RACINE, encoding: 'utf8', stdio: 'pipe',
      env: { ...process.env, DOMAINE: 'exemple.test', BASE_MOT_DE_PASSE: 'valeur-factice-e2e', VERSION: 'e2e' },
    })) as { services: Record<string, { volumes?: Array<{ type: string; source: string; target: string }>; environment?: Record<string, string> }>; volumes: Record<string, unknown> };
    expect(Object.keys(json.volumes), fichiers.join(' ')).toContain('photos');
    expect(json.services['api'].volumes, fichiers.join(' ')).toContainEqual(expect.objectContaining({ type: 'volume', source: 'photos', target: '/donnees/photos' }));
    expect(json.services['api'].environment?.['BACKYARD_PHOTOS_REPERTOIRE'], fichiers.join(' ')).toBe('/donnees/photos');
  }
});

test('CA33 - l\'utilisateur backyard écrit dans /donnees/photos (PUT 200) et le fichier est en droits 600', async ({ playwright }) => {
  const pseudo = pseudoUnique('photo');
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  const jeton = await avecApi(playwright, async (ctx) => poserPhoto(ctx, await sessionCoureur(ctx, pseudo), png));
  const fichier = fichiersDuVolume().find((f) => f.startsWith(jeton));
  expect(fichier).toBe(`${jeton}.png`);
  expect(compose('exec', '-T', 'api', 'stat', '-c', '%a %U', `/donnees/photos/${fichier}`).trim()).toBe('600 backyard');
});

test('CA33 - un PUT de 5 000 000 octets via Caddy reçoit un 413 JSON PHOTO_TROP_VOLUMINEUSE et l\'ancienne photo est conservée', async ({ playwright }) => {
  const pseudo = pseudoUnique('photo');
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  await avecApi(playwright, async (ctx) => {
    const csrf = await sessionCoureur(ctx, pseudo);
    const jeton = await poserPhoto(ctx, csrf, png);
    const gros = Buffer.concat([png, Buffer.alloc(5_000_000 - png.length)]);
    expect(gros.length).toBe(5_000_000);
    const r = await envoyerPhoto(ctx, csrf, gros);
    expect(r.status()).toBe(413);
    expect(r.headers()['content-type']).toContain('json');
    const corps = (await r.json()) as { code?: string; detail?: string };
    expect(corps.code).toBe('PHOTO_TROP_VOLUMINEUSE');
    expect(corps.detail).toBe('La photo ne doit pas dépasser 2 Mo.');
    expect(await photoUrlDeMoi(ctx)).toBe(`/api/photos/${jeton}`);
    expect((await ctx.get(`/api/photos/${jeton}`)).status()).toBe(200);
    expect(fichiersDuVolume().filter((f) => f.startsWith(jeton))).toHaveLength(1);
  });
});
