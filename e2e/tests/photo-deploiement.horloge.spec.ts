import { expect, test } from './aide-horloge';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { creerCompteParApi, pseudoUnique } from './aide-connexion';
import { avecApi } from './aide-horloge';
import { RACINE, fichiersDuVolume, photoUrlDeMoi, poserPhoto, sessionCoureur } from './aide-photo';

/*
 * Incrément 10.1a, CA33 (partie intrusive, d'où le projet horloge : un seul worker, après les autres projets) :
 * la photo survit à `restart api` puis à `down` / `up` sans -v (même URL, même ETag) ; sauvegarder.sh produit une
 * archive de photos valide et la restauration documentée rend la photo servie.
 */
test.describe.configure({ mode: 'serial' });
test.setTimeout(420_000);

const png = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));
const FICHIERS_E2E = ['-f', 'docker-compose.yml', '-f', 'docker-compose.e2e.yml'];

function compose(...args: string[]): string {
  return execFileSync('docker', ['compose', ...FICHIERS_E2E, ...args], { cwd: RACINE, encoding: 'utf8', stdio: 'pipe', maxBuffer: 64 * 1024 * 1024 });
}

async function attendreSante(request: import('@playwright/test').APIRequestContext): Promise<void> {
  await expect.poll(async () => (await request.get('/api/sante').catch(() => null))?.status(), { timeout: 240_000, intervals: [2000] }).toBe(200);
}

async function photoDe(playwright: import('@playwright/test').PlaywrightWorkerArgs['playwright'], pseudo: string): Promise<{ url: string | null; etag: string | null; statut: number }> {
  return avecApi(playwright, async (ctx) => {
    await sessionCoureur(ctx, pseudo);
    const url = await photoUrlDeMoi(ctx);
    const r = url ? await ctx.get(url) : null;
    return { url, etag: r?.headers()['etag'] ?? null, statut: r?.status() ?? 0 };
  });
}

test('CA33 - la photo survit à restart api puis à down / up sans -v : même URL, même ETag', async ({ playwright, request }) => {
  const pseudo = pseudoUnique('photo');
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  const jeton = await avecApi(playwright, async (ctx) => poserPhoto(ctx, await sessionCoureur(ctx, pseudo), png));
  const avant = await photoDe(playwright, pseudo);
  expect(avant).toEqual({ url: `/api/photos/${jeton}`, etag: `"${jeton}"`, statut: 200 });

  compose('restart', 'api');
  await attendreSante(request);
  expect(await photoDe(playwright, pseudo)).toEqual(avant);

  compose('down');
  compose('up', '-d');
  await attendreSante(request);
  expect(await photoDe(playwright, pseudo)).toEqual(avant);
  expect(fichiersDuVolume()).toContain(`${jeton}.png`);
});

test('CA33 - sauvegarder.sh produit un dump et une archive de photos contenant le fichier, la restauration documentée rend la photo servie', async ({ playwright, request }) => {
  const pseudo = pseudoUnique('photo');
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  const jeton = await avecApi(playwright, async (ctx) => poserPhoto(ctx, await sessionCoureur(ctx, pseudo), png));
  const dossier = mkdtempSync(join(tmpdir(), 'sauvegarde-e2e-'));
  try {
    execFileSync('bash', [join(RACINE, 'scripts', 'sauvegarder.sh')], {
      cwd: RACINE, stdio: 'pipe',
      env: { ...process.env, SAUVEGARDE_DOSSIER: dossier, COMPOSE_FICHIERS: FICHIERS_E2E.join(' ') },
    });
    const noms = readdirSync(dossier);
    const dump = noms.filter((n) => /^sauvegarde-\d{8}-\d{4}.*\.dump$/.test(n));
    const archives = noms.filter((n) => /^sauvegarde-photos-\d{8}-\d{4}.*\.tar\.gz$/.test(n));
    expect(dump).toHaveLength(1);
    expect(archives).toHaveLength(1);
    const contenu = execFileSync('tar', ['tzf', join(dossier, archives[0])], { encoding: 'utf8' });
    expect(contenu).toContain(`${jeton}.png`);

    // Restauration documentée : api arrêtée, volume vidé puis restauré depuis l'archive
    compose('stop', 'api');
    execFileSync('docker', ['compose', ...FICHIERS_E2E, 'run', '--rm', '--no-deps', '-T', '--entrypoint', 'sh', 'api', '-c', 'find /donnees/photos -mindepth 1 -delete'], { cwd: RACINE, stdio: 'pipe' });
    const restaurees = execFileSync('docker', ['compose', ...FICHIERS_E2E, 'run', '--rm', '--no-deps', '-T', '--entrypoint', 'sh', 'api', '-c',
      'find /donnees/photos -mindepth 1 -delete && tar xzf - -C /donnees/photos --no-same-owner && chmod 600 /donnees/photos/*.* 2>/dev/null; ls /donnees/photos | wc -l'],
    { cwd: RACINE, input: readFileSync(join(dossier, archives[0])), encoding: 'utf8' });
    expect(Number(restaurees.trim())).toBeGreaterThan(0);
    compose('start', 'api');
    await attendreSante(request);
    expect(fichiersDuVolume()).toContain(`${jeton}.png`);
    const r = await avecApi(playwright, (ctx) => ctx.get(`/api/photos/${jeton}`));
    expect(r.status()).toBe(200);
    expect(r.headers()['content-type']).toBe('image/png');
  } finally {
    rmSync(dossier, { recursive: true, force: true });
    expect(existsSync(dossier)).toBe(false);
  }
});
