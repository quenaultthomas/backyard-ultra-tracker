import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  MOT_DE_PASSE_ADMIN_MASTER,
  PSEUDO_ADMIN_MASTER,
  connecterParApi,
  courseDeReference,
  creerCourseParApi,
  envoyerLogoParApi,
  exigerIdentifiantsAdminMaster,
} from './aide-admin';
import { creerCompteParApi, pseudoUnique } from './aide-connexion';
import { BASE_URL, avecApi } from './aide-recherche-coureurs';
import { photoUrlDeMoi, poserPhoto, sessionCoureur } from './aide-photo';

/*
 * Correctif « 431 après un 413 de téléversement » (docs/specs/correctif-431-televersement.md, CA6 à CA8).
 * CA9 : les E2E existants logo-course.spec.ts et photo-deploiement.spec.ts restent verts (aucun test modifié).
 * Sans écran : APIRequestContext sur le port 80 de `web` (Caddy), connexions réutilisées comme par un navigateur.
 */
const png = readFileSync(join(__dirname, '..', 'fixtures', 'logo.png'));

const BOUCLES = 5;
const ITERATIONS = 100;

/** Fichier de `taille` octets commençant par un PNG valide (la taille est refusée avant tout contrôle de contenu). */
const fichierDe = (taille: number): Buffer => Buffer.concat([png, Buffer.alloc(taille - png.length)]);

interface Constat {
  anomalies: string[];
  statutsPut: Map<number, number>;
  statutsGet: Map<number, number>;
  codes: Map<string, number>;
}

const compter = <K>(m: Map<K, number>, cle: K): void => void m.set(cle, (m.get(cle) ?? 0) + 1);

/** Rend le message de l'écart (ou chaîne vide) : jamais d'exception, pour décompter toutes les anomalies. */
async function iteration(
  constat: Constat,
  envoyer: () => Promise<{ status: () => number; json: () => Promise<unknown>; headers: () => Record<string, string> }>,
  lire: () => Promise<{ status: () => number }>,
  etiquette: string,
): Promise<void> {
  try {
    const put = await envoyer();
    compter(constat.statutsPut, put.status());
    if ((put.headers()['content-type'] ?? '').includes('json')) {
      compter(constat.codes, String(((await put.json()) as { code?: string }).code));
    } else {
      compter(constat.codes, '(non JSON)');
    }
    const get = await lire();
    compter(constat.statutsGet, get.status());
  } catch (erreur) {
    constat.anomalies.push(`${etiquette} : ${erreur instanceof Error ? erreur.message : String(erreur)}`);
  }
}

const nouveauConstat = (): Constat => ({ anomalies: [], statutsPut: new Map(), statutsGet: new Map(), codes: new Map() });
const resume = (c: Constat): string =>
  `PUT ${JSON.stringify([...c.statutsPut])}, GET ${JSON.stringify([...c.statutsGet])}, codes ${JSON.stringify([...c.codes])}, anomalies (${c.anomalies.length}) ${c.anomalies.slice(0, 5).join(' | ')}`;

/** Corps multipart construit à la main pour maîtriser la taille exacte de la requête (octets du corps). */
function corpsMultipartDeTaille(tailleCorps: number): { corps: Buffer; typeContenu: string } {
  const frontiere = '----e2e413frontiere';
  const entete = Buffer.from(`--${frontiere}\r\nContent-Disposition: form-data; name="fichier"; filename="photo.png"\r\nContent-Type: image/png\r\n\r\n`);
  const queue = Buffer.from(`\r\n--${frontiere}--\r\n`);
  const fichier = fichierDe(tailleCorps - entete.length - queue.length);
  const corps = Buffer.concat([entete, fichier, queue]);
  expect(corps.length).toBe(tailleCorps);
  return { corps, typeContenu: `multipart/form-data; boundary=${frontiere}` };
}

test.describe('Envois volumineux refusés (413) via web', () => {
  test.beforeAll(() => {
    exigerIdentifiantsAdminMaster();
  });

  test('CA6 - 5 boucles x 100 PUT de 5 000 000 octets sur la photo, chacun suivi d\'un GET /api/comptes/moi : 413 PHOTO_TROP_VOLUMINEUSE puis 200, jamais 431/400/502/ECONNRESET', async ({ playwright }) => {
    test.setTimeout(480_000);
    const pseudo = pseudoUnique('t413');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
    const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
    try {
      const csrf = await sessionCoureur(ctx, pseudo);
      const jeton = await poserPhoto(ctx, csrf, png);
      const urlAvant = await photoUrlDeMoi(ctx);
      const etagAvant = (await ctx.get(`/api/photos/${jeton}`)).headers()['etag'];
      const gros = fichierDe(5_000_000);
      expect(gros.length).toBe(5_000_000);

      const constat = nouveauConstat();
      await Promise.all(
        Array.from({ length: BOUCLES }, async (_, boucle) => {
          for (let i = 0; i < ITERATIONS; i++) {
            await iteration(
              constat,
              () => ctx.put('/api/comptes/moi/photo', { headers: { 'X-XSRF-TOKEN': csrf }, multipart: { fichier: { name: 'photo.png', mimeType: 'image/png', buffer: gros } } }),
              () => ctx.get('/api/comptes/moi'),
              `boucle ${boucle} itération ${i}`,
            );
          }
        }),
      );

      const attendu = BOUCLES * ITERATIONS;
      expect(constat.anomalies, resume(constat)).toHaveLength(0);
      expect(constat.statutsPut.get(413) ?? 0, resume(constat)).toBe(attendu);
      expect(constat.statutsGet.get(200) ?? 0, resume(constat)).toBe(attendu);
      expect(constat.codes.get('PHOTO_TROP_VOLUMINEUSE') ?? 0, resume(constat)).toBe(attendu);
      expect([...constat.statutsPut.keys()], resume(constat)).toEqual([413]);
      expect([...constat.statutsGet.keys()], resume(constat)).toEqual([200]);

      // la photo du coureur est inchangée (même URL, même ETag)
      expect(await photoUrlDeMoi(ctx)).toBe(urlAvant);
      expect((await ctx.get(`/api/photos/${jeton}`)).headers()['etag']).toBe(etagAvant);
    } finally {
      await ctx.dispose();
    }
  });

  test('CA7 - 5 boucles x 100 PUT de logo alternant 2 097 153 et 5 000 000 octets, suivis de GET de la Course : 413 LOGO_TROP_VOLUMINEUX puis 200, logo inchangé', async ({ playwright }) => {
    test.setTimeout(480_000);
    const course = await avecApi(playwright, (c) => creerCourseParApi(c, courseDeReference()));
    const id = course['id'] as string;
    await avecApi(playwright, (c) => envoyerLogoParApi(c, id, { nom: 'logo.png', type: 'image/png', contenu: png }));

    const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
    try {
      await connecterParApi(ctx, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
      await ctx.get('/api/csrf');
      const csrf = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value;
      const logoAvant = ((await (await ctx.get(`/api/administration/courses/${id}`)).json()) as { logoUrl: string | null }).logoUrl;
      expect(logoAvant).toMatch(/^\/api\/courses\/.+\/logo\?v=/);

      const tailles = [fichierDe(2_097_153), fichierDe(5_000_000)];
      const constat = nouveauConstat();
      await Promise.all(
        Array.from({ length: BOUCLES }, async (_, boucle) => {
          for (let i = 0; i < ITERATIONS; i++) {
            await iteration(
              constat,
              () => ctx.put(`/api/administration/courses/${id}/logo`, { headers: { 'X-XSRF-TOKEN': csrf }, multipart: { fichier: { name: 'logo.png', mimeType: 'image/png', buffer: tailles[(boucle + i) % 2] } } }),
              () => ctx.get(`/api/administration/courses/${id}`),
              `boucle ${boucle} itération ${i}`,
            );
          }
        }),
      );

      const attendu = BOUCLES * ITERATIONS;
      expect(constat.anomalies, resume(constat)).toHaveLength(0);
      expect(constat.statutsPut.get(413) ?? 0, resume(constat)).toBe(attendu);
      expect(constat.statutsGet.get(200) ?? 0, resume(constat)).toBe(attendu);
      expect(constat.codes.get('LOGO_TROP_VOLUMINEUX') ?? 0, resume(constat)).toBe(attendu);
      expect([...constat.statutsPut.keys()], resume(constat)).toEqual([413]);
      expect([...constat.statutsGet.keys()], resume(constat)).toEqual([200]);

      const logoApres = ((await (await ctx.get(`/api/administration/courses/${id}`)).json()) as { logoUrl: string | null }).logoUrl;
      expect(logoApres).toBe(logoAvant);
    } finally {
      await ctx.dispose();
    }
  });

  test('CA8 - au-delà de 8 MiB Caddy répond 413 et la requête suivante est en 200 (20 itérations) ; à 8 388 608 octets, 413 JSON PHOTO_TROP_VOLUMINEUSE', async ({ playwright }) => {
    test.setTimeout(240_000);
    const pseudo = pseudoUnique('t413');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
    const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
    try {
      const csrf = await sessionCoureur(ctx, pseudo);
      const urlAvant = await photoUrlDeMoi(ctx);
      const trop = fichierDe(9_000_000);

      const constat = nouveauConstat();
      for (let i = 0; i < 20; i++) {
        await iteration(
          constat,
          () => ctx.put('/api/comptes/moi/photo', { headers: { 'X-XSRF-TOKEN': csrf }, multipart: { fichier: { name: 'photo.png', mimeType: 'image/png', buffer: trop } } }),
          () => ctx.get('/api/comptes/moi'),
          `9 000 000 octets, itération ${i}`,
        );
      }
      expect(constat.anomalies, resume(constat)).toHaveLength(0);
      expect([...constat.statutsPut.keys()], resume(constat)).toEqual([413]);
      expect(constat.statutsPut.get(413), resume(constat)).toBe(20);
      expect([...constat.statutsGet.keys()], resume(constat)).toEqual([200]);
      expect(constat.statutsGet.get(200), resume(constat)).toBe(20);

      // limite exacte : 8 388 608 octets de corps passent Caddy et reçoivent le 413 JSON de Spring ; un octet de plus : Caddy
      const exact = corpsMultipartDeTaille(8_388_608);
      const limite = nouveauConstat();
      for (let i = 0; i < 5; i++) {
        await iteration(
          limite,
          () => ctx.put('/api/comptes/moi/photo', { headers: { 'X-XSRF-TOKEN': csrf, 'Content-Type': exact.typeContenu }, data: exact.corps }),
          () => ctx.get('/api/comptes/moi'),
          `8 388 608 octets, itération ${i}`,
        );
      }
      expect(limite.anomalies, resume(limite)).toHaveLength(0);
      expect(limite.statutsPut.get(413), resume(limite)).toBe(5);
      expect(limite.codes.get('PHOTO_TROP_VOLUMINEUSE'), resume(limite)).toBe(5);
      expect(limite.statutsGet.get(200), resume(limite)).toBe(5);

      const juste = corpsMultipartDeTaille(8_388_609);
      const audela = nouveauConstat();
      for (let i = 0; i < 5; i++) {
        await iteration(
          audela,
          () => ctx.put('/api/comptes/moi/photo', { headers: { 'X-XSRF-TOKEN': csrf, 'Content-Type': juste.typeContenu }, data: juste.corps }),
          () => ctx.get('/api/comptes/moi'),
          `8 388 609 octets, itération ${i}`,
        );
      }
      expect(audela.anomalies, resume(audela)).toHaveLength(0);
      expect([...audela.statutsPut.keys()], resume(audela)).toEqual([413]);
      expect(audela.statutsGet.get(200), resume(audela)).toBe(5);

      expect(await photoUrlDeMoi(ctx)).toBe(urlAvant);
    } finally {
      await ctx.dispose();
    }
  });
});
