import { expect, type APIRequestContext, type Browser, type Locator, type Page, type PlaywrightWorkerArgs, type Request } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique } from './aide-connexion';
import { MOT_DE_PASSE_ADMIN_MASTER, PSEUDO_ADMIN_MASTER, connecterParApi } from './aide-admin';
import { RACINE, poserPhoto, sessionCoureur } from './aide-photo';
import { BASE_URL, avecApi } from './aide-recherche-coureurs';

/** Aides des E2E de l'écran « Mon compte » (10.1b) : photo de profil, ré-encodage dans le navigateur. */

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export const MESSAGE_ENREGISTREE = 'Votre photo a été enregistrée.';
export const MESSAGE_RETIREE = 'Votre photo a été retirée.';
export const MESSAGE_ILLISIBLE = "Ce fichier n'est pas une image lisible.";
export const MESSAGE_TROP_VOLUMINEUX = 'Ce fichier est trop volumineux (20 Mo maximum).';
export const MESSAGE_IMPOSSIBLE = "La photo n'a pas pu être préparée. Essayez avec une autre image.";
export const MESSAGE_INDISPONIBLE = 'Service indisponible, veuillez réessayer plus tard.';
export const URL_PHOTO = /^\/api\/photos\/[0-9a-f]{32}$/;

export const fixture = (nom: string): string => join(RACINE, 'e2e', 'fixtures', nom);
export const octetsFixture = (nom: string): Buffer => readFileSync(fixture(nom));
export const t = (page: Page, id: string): Locator => page.getByTestId(id);
export const urlPhoto = '**/api/comptes/moi/photo';

export const estPut = (r: Request): boolean => r.method() === 'PUT' && new URL(r.url()).pathname === '/api/comptes/moi/photo';
export const estDelete = (r: Request): boolean => r.method() === 'DELETE' && new URL(r.url()).pathname === '/api/comptes/moi/photo';
const touchePhoto = (r: Request): boolean => new URL(r.url()).pathname === '/api/comptes/moi/photo';

/** Coureur sans photo (créé par l'API). */
export async function nouveauCoureur(playwright: PlaywrightLib, prefixe = 'photo'): Promise<string> {
  const pseudo = pseudoUnique(prefixe);
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  return pseudo;
}

/** Coureur dont la photo est posée par l'API (fixture PNG par défaut). */
export async function coureurAvecPhoto(playwright: PlaywrightLib, fichier = 'logo.png', prefixe = 'photo'): Promise<{ pseudo: string; jeton: string }> {
  const pseudo = await nouveauCoureur(playwright, prefixe);
  const jeton = await avecApi(playwright, async (ctx) => poserPhoto(ctx, await sessionCoureur(ctx, pseudo), octetsFixture(fichier)));
  return { pseudo, jeton };
}

/** Ouvre /mon-compte en tant que `pseudo` (session par l'API) et attend que le bloc photo ait reçu le Compte. */
export async function ouvrirMonCompte(page: Page, pseudo: string, motDePasse = MOT_DE_PASSE, attendreBloc = true): Promise<void> {
  await page.context().clearCookies();
  await connecterParApi(page.request, pseudo, motDePasse);
  const lecture = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/comptes/moi' && r.request().method() === 'GET');
  await page.goto('/mon-compte');
  await lecture;
  await expect(t(page, 'titre-mon-compte')).toBeVisible();
  if (attendreBloc) {
    await expect(t(page, 'mon-compte-photo')).toBeVisible();
  }
}

/** Journal des requêtes vers /api/comptes/moi/photo ; la route `continue` permet de lire le corps des PUT. */
export async function espionnerPhoto(page: Page): Promise<{ appels: Request[]; puts: () => Request[]; deletes: () => Request[] }> {
  const appels: Request[] = [];
  await page.route(urlPhoto, (route) => route.continue());
  page.on('request', (r) => {
    if (touchePhoto(r)) appels.push(r);
  });
  return { appels, puts: () => appels.filter(estPut), deletes: () => appels.filter(estDelete) };
}

export type Fichier = string | { name: string; mimeType: string; buffer: Buffer };

/** Choisit un fichier (nom de fixture ou contenu en mémoire) dans le champ masqué. */
export async function choisir(page: Page, fichier: Fichier): Promise<void> {
  await t(page, 'mon-compte-photo-champ').setInputFiles(typeof fichier === 'string' ? fixture(fichier) : fichier);
}

/** Choisit un fichier et attend l'aperçu. */
export async function choisirEtPrevisualiser(page: Page, fichier: Fichier): Promise<void> {
  await choisir(page, fichier);
  await expect(t(page, 'mon-compte-photo-apercu')).toBeVisible();
}

export async function dimensionsImage(image: Locator): Promise<{ largeur: number; hauteur: number }> {
  return image.evaluate((el) => ({ largeur: (el as HTMLImageElement).naturalWidth, hauteur: (el as HTMLImageElement).naturalHeight }));
}

/** Clique sur « Enregistrer la photo » et renvoie la requête PUT (la route de `espionnerPhoto` doit être active). */
export async function enregistrer(page: Page): Promise<Request> {
  const requete = page.waitForRequest(estPut);
  await t(page, 'mon-compte-photo-bouton-envoyer').click();
  return requete;
}

/** Partie `fichier` du corps multipart du PUT. */
export function partieFichier(requete: Request): Buffer {
  const corps = requete.postDataBuffer();
  expect(corps, 'corps du PUT lisible (route.continue active ?)').not.toBeNull();
  const type = requete.headers()['content-type'] ?? '';
  const frontiere = /boundary=(?:"([^"]+)"|([^;]+))/.exec(type);
  expect(frontiere, `content-type multipart : ${type}`).not.toBeNull();
  const delimiteur = Buffer.from(`--${frontiere![1] ?? frontiere![2]}`);
  let debut = 0;
  while ((debut = corps!.indexOf(delimiteur, debut)) !== -1) {
    const finEntetes = corps!.indexOf(Buffer.from('\r\n\r\n'), debut);
    const entetes = corps!.subarray(debut, finEntetes).toString('latin1');
    const contenu = finEntetes + 4;
    const suivant = corps!.indexOf(delimiteur, contenu);
    if (/name="fichier"/.test(entetes)) {
      return Buffer.from(corps!.subarray(contenu, suivant - 2));
    }
    debut = suivant === -1 ? corps!.length : suivant;
  }
  throw new Error('Partie « fichier » absente du multipart');
}

export interface AnalyseJpeg {
  signature: boolean;
  largeur: number;
  hauteur: number;
  /** Marqueurs des segments d'en-tête, jusqu'au début du balayage. */
  marqueurs: number[];
}

/** Lit les segments d'un JPEG jusqu'à SOS ; dimensions dans SOF0/SOF1/SOF2. */
export function analyserJpeg(jpeg: Buffer): AnalyseJpeg {
  const marqueurs: number[] = [];
  let largeur = 0;
  let hauteur = 0;
  let pos = 2;
  while (pos + 4 <= jpeg.length && jpeg[pos] === 0xff) {
    const marqueur = jpeg[pos + 1];
    marqueurs.push(marqueur);
    if (marqueur === 0xda) break;
    if (marqueur >= 0xc0 && marqueur <= 0xc2) {
      hauteur = jpeg.readUInt16BE(pos + 5);
      largeur = jpeg.readUInt16BE(pos + 7);
    }
    pos += 2 + jpeg.readUInt16BE(pos + 2);
  }
  return { signature: jpeg[0] === 0xff && jpeg[1] === 0xd8 && jpeg[2] === 0xff, largeur, hauteur, marqueurs };
}

/** Exige un JPEG propre : signature, pas d'APP1 (EXIF), pas de commentaire, ni « Exif\0\0 » ni « GPS ». */
export function exigerJpegSansMetadonnees(jpeg: Buffer): AnalyseJpeg {
  const a = analyserJpeg(jpeg);
  expect(a.signature, 'signature FF D8 FF').toBe(true);
  expect(a.marqueurs, 'pas de segment APP1 (FF E1)').not.toContain(0xe1);
  expect(a.marqueurs, 'pas de commentaire (FF FE)').not.toContain(0xfe);
  expect(jpeg.indexOf(Buffer.from('Exif\0\0', 'latin1')), 'pas de « Exif\\0\\0 »').toBe(-1);
  expect(jpeg.indexOf(Buffer.from('GPS', 'latin1')), 'pas de « GPS »').toBe(-1);
  return a;
}

/** Pixel (RGBA) d'un JPEG décodé dans la page. */
export async function pixelDe(page: Page, jpeg: Buffer, x: number, y: number): Promise<[number, number, number, number]> {
  return page.evaluate(
    async ([base64, px, py]) => {
      const image = new Image();
      image.src = `data:image/jpeg;base64,${base64}`;
      await image.decode();
      const c = document.createElement('canvas');
      c.width = image.naturalWidth;
      c.height = image.naturalHeight;
      const ctx = c.getContext('2d')!;
      ctx.drawImage(image, 0, 0);
      return Array.from(ctx.getImageData(px as number, py as number, 1, 1).data) as [number, number, number, number];
    },
    [jpeg.toString('base64'), x, y] as const,
  );
}

/** GET public (sans cookie) d'une photo. */
export async function lirePhotoPubliquement(playwright: PlaywrightLib, url: string): Promise<{ statut: number; type: string | undefined; corps: Buffer }> {
  return avecApi(playwright, async (ctx: APIRequestContext) => {
    const r = await ctx.get(url);
    return { statut: r.status(), type: r.headers()['content-type'], corps: await r.body() };
  });
}

/** JPEG de 6 000 x 4 000 (environ 8 Mo, sous la limite de 20 Mo) généré dans une page vierge. */
export async function genererGrosJpeg(browser: Browser): Promise<Buffer> {
  const contexte = await browser.newContext();
  try {
    const page = await contexte.newPage();
    await page.goto('about:blank');
    const base64 = await page.evaluate(async () => {
      const c = document.createElement('canvas');
      c.width = 6000;
      c.height = 4000;
      const x = c.getContext('2d')!;
      for (let j = 0; j < 4000; j += 6) {
        for (let i = 0; i < 6000; i += 6) {
          x.fillStyle = `rgb(${(Math.random() * 256) | 0},${(Math.random() * 256) | 0},${(Math.random() * 256) | 0})`;
          x.fillRect(i, j, 6, 6);
        }
      }
      const blob: Blob = await new Promise((r) => c.toBlob((b) => r(b!), 'image/jpeg', 0.7));
      return await new Promise<string>((r) => {
        const lecteur = new FileReader();
        lecteur.onload = () => r(String(lecteur.result).split(',')[1]);
        lecteur.readAsDataURL(blob);
      });
    });
    return Buffer.from(base64, 'base64');
  } finally {
    await contexte.close();
  }
}

/** PNG valide (fixture) complété par des zéros jusqu'à `taille` octets (le décodeur ignore la suite d'IEND). */
export function pngDeTaille(taille: number): Buffer {
  const png = octetsFixture('photo-petite.png');
  return Buffer.concat([png, Buffer.alloc(taille - png.length)]);
}

/**
 * Exécute scripts/donnees-demo.sh pour disposer de Karim (photo) et Tom (sans photo). Le script est idempotent mais
 * peut échouer sur une base déjà modifiée par d'autres tests (ex. « Backyard du jour » démarrée) : les Comptes et
 * leurs photos sont alors déjà en place, et les assertions de l'écran vérifient ce qui compte.
 */
export function assurerDonneesDemo(): void {
  const env: NodeJS.ProcessEnv = { ...process.env, BASE_URL, ADMIN_MASTER_PSEUDO: PSEUDO_ADMIN_MASTER, ADMIN_MASTER_MOT_DE_PASSE: MOT_DE_PASSE_ADMIN_MASTER };
  try {
    execFileSync('bash', [join(RACINE, 'scripts', 'donnees-demo.sh')], { cwd: RACINE, env, stdio: 'pipe', timeout: 180_000 });
  } catch {
    // Données déjà partiellement modifiées par d'autres tests : voir ci-dessus.
  }
}
