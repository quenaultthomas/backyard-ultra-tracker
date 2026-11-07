// Génère les fixtures de photo de profil du 10.1b (sans outil externe) : `node fixtures/generer-photos.mjs` depuis e2e/.
// Les images sont dessinées dans Chromium (canvas) ; l'EXIF, le GIF animé et le WebP animé sont assemblés à la main.
import { chromium } from '@playwright/test';
import { PNG } from 'pngjs';
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dossier = dirname(fileURLToPath(import.meta.url));
const ecrire = (nom, octets) => writeFileSync(join(dossier, nom), octets);

const navigateur = await chromium.launch();
const page = await navigateur.newPage();
await page.goto('about:blank');

/** Dessine un dégradé avec repères et renvoie l'image encodée (base64). */
async function dessiner(l, h, type, qualite, coinVert = false) {
  const b64 = await page.evaluate(async ([l, h, type, qualite, coinVert]) => {
    const c = document.createElement('canvas');
    c.width = l; c.height = h;
    const x = c.getContext('2d');
    const g = x.createLinearGradient(0, 0, l, h);
    g.addColorStop(0, '#d9822b'); g.addColorStop(0.5, '#3a7d44'); g.addColorStop(1, '#2b5fd9');
    x.fillStyle = g; x.fillRect(0, 0, l, h);
    x.fillStyle = '#ffffff'; x.beginPath(); x.arc(l / 2, h / 2, Math.min(l, h) / 4, 0, 7); x.fill();
    if (coinVert) { x.fillStyle = '#00ff00'; x.fillRect(0, 0, l / 4, h / 4); x.fillStyle = '#ff0000'; x.fillRect(l * 0.75, h * 0.75, l / 4, h / 4); }
    const blob = await new Promise((r) => c.toBlob(r, type, qualite));
    const buf = new Uint8Array(await blob.arrayBuffer());
    let s = ''; for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
    return btoa(s);
  }, [l, h, type, qualite, coinVert]);
  return Buffer.from(b64, 'base64');
}
const uni = (l, h, couleur, type) => page.evaluate(async ([l, h, couleur, type]) => {
  const c = document.createElement('canvas'); c.width = l; c.height = h;
  const x = c.getContext('2d'); x.fillStyle = couleur; x.fillRect(0, 0, l, h);
  const blob = await new Promise((r) => c.toBlob(r, type, 1));
  const buf = new Uint8Array(await blob.arrayBuffer());
  return btoa(String.fromCharCode(...buf));
}, [l, h, couleur, type]).then((b) => Buffer.from(b, 'base64'));

const u16 = (n) => { const b = Buffer.alloc(2); b.writeUInt16LE(n); return b; };
const u32 = (n) => { const b = Buffer.alloc(4); b.writeUInt32LE(n); return b; };

/** Insère un segment juste après SOI. */
function inserer(jpeg, marqueur, charge) {
  const seg = Buffer.concat([Buffer.from([0xff, marqueur]), Buffer.from([(charge.length + 2) >> 8, (charge.length + 2) & 0xff]), charge]);
  return Buffer.concat([jpeg.subarray(0, 2), seg, jpeg.subarray(2)]);
}
/** APP1 Exif (little endian) : orientation et, si `gps`, un bloc GPS (48°51'N, 2°21'E). */
function exif({ orientation, gps }) {
  const entrees = [];
  const ifd0 = [];
  ifd0.push([0x010f, 2, 6, Buffer.from('Test\0\0\0\0')]); // Make (inline < 4 ? no: 5 octets -> déporté ; simplifié ci-dessous)
  // Construction simple : IFD0 avec Orientation (+ GPSInfo si gps), GPS IFD ensuite.
  const n = 1 + (gps ? 1 : 0);
  const tailleIfd0 = 2 + n * 12 + 4;
  const decalGps = 8 + tailleIfd0;
  const e0 = [];
  e0.push(Buffer.concat([u16(0x0112), u16(3), u32(1), u16(orientation ?? 1), u16(0)]));
  if (gps) e0.push(Buffer.concat([u16(0x8825), u16(4), u32(1), u32(decalGps)]));
  const ifd = Buffer.concat([u16(n), ...e0, u32(0)]);
  let gpsIfd = Buffer.alloc(0);
  if (gps) {
    const nb = 4;
    const debut = decalGps + 2 + nb * 12 + 4;
    const lat = Buffer.concat([u32(48), u32(1), u32(51), u32(1), u32(0), u32(1)]);
    const lon = Buffer.concat([u32(2), u32(1), u32(21), u32(1), u32(0), u32(1)]);
    gpsIfd = Buffer.concat([
      u16(nb),
      Buffer.concat([u16(1), u16(2), u32(2), Buffer.from('N\0\0\0')]),
      Buffer.concat([u16(2), u16(5), u32(3), u32(debut)]),
      Buffer.concat([u16(3), u16(2), u32(2), Buffer.from('E\0\0\0')]),
      Buffer.concat([u16(4), u16(5), u32(3), u32(debut + 24)]),
      u32(0), lat, lon,
    ]);
  }
  const tiff = Buffer.concat([Buffer.from('II'), u16(42), u32(8), ifd, gpsIfd]);
  return Buffer.concat([Buffer.from('Exif\0\0', 'latin1'), tiff]);
}

// JPEG sans EXIF, 800 x 600
ecrire('photo.jpg', await dessiner(800, 600, 'image/jpeg', 0.9));
// JPEG 4032 x 3024 avec EXIF + GPS + commentaire
let gps = await dessiner(4032, 3024, 'image/jpeg', 0.9);
gps = inserer(gps, 0xfe, Buffer.from('GPS 48.8566N 2.3522E'));
gps = inserer(gps, 0xe1, exif({ orientation: 1, gps: true }));
ecrire('photo-gps.jpg', gps);
// JPEG 400 x 300 stocké, orientation EXIF 6 (coin supérieur gauche vert stocké)
ecrire('photo-orientation6.jpg', inserer(await dessiner(400, 300, 'image/jpeg', 0.92, true), 0xe1, exif({ orientation: 6, gps: false })));
// PNG
ecrire('photo-petite.png', await dessiner(200, 100, 'image/png'));
ecrire('photo-2.png', await dessiner(640, 480, 'image/png'));
const t = new PNG({ width: 300, height: 300 });
for (let y = 0; y < 300; y++) for (let x = 0; x < 300; x++) {
  const i = (y * 300 + x) * 4; const opaque = x >= 150;
  t.data[i] = 20; t.data[i + 1] = 90; t.data[i + 2] = 200; t.data[i + 3] = opaque ? 255 : 0;
}
ecrire('photo-transparente.png', PNG.sync.write(t));
ecrire('photo.webp', await dessiner(640, 480, 'image/webp', 0.9));
ecrire('faux-photo.png', Buffer.from('Ceci est un texte, pas une image.\n'));

// GIF animé 64 x 64 : rouge puis bleu (palette de 4 couleurs, LZW sur 2 bits)
function lzw2bits(indices) {
  // Chaque pixel émis avec un code de 3 bits, "clear" (4) régulièrement : valide sans dictionnaire.
  const bits = []; const pousser = (v) => { for (let i = 0; i < 3; i++) bits.push((v >> i) & 1); };
  pousser(4);
  indices.forEach((v, k) => { pousser(v); if ((k + 1) % 2 === 0) pousser(4); });
  pousser(5);
  const octets = []; for (let i = 0; i < bits.length; i += 8) { let o = 0; for (let j = 0; j < 8; j++) o |= (bits[i + j] ?? 0) << j; octets.push(o); }
  const blocs = []; for (let i = 0; i < octets.length; i += 255) { const b = octets.slice(i, i + 255); blocs.push(b.length, ...b); }
  return Buffer.from([2, ...blocs, 0]);
}
function trameGif(indice) {
  return Buffer.concat([Buffer.from([0x21, 0xf9, 4, 0, 10, 0, 0, 0]), Buffer.from([0x2c, 0, 0, 0, 0, 64, 0, 64, 0, 0]), lzw2bits(new Array(64 * 64).fill(indice))]);
}
ecrire('animation.gif', Buffer.concat([
  Buffer.from('GIF89a'), Buffer.from([64, 0, 64, 0, 0xf1, 0, 0]),
  Buffer.from([255, 0, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255]), // palette : rouge, bleu, noir, blanc
  Buffer.from([0x21, 0xff, 11]), Buffer.from('NETSCAPE2.0'), Buffer.from([3, 1, 0, 0, 0]),
  trameGif(0), trameGif(1), Buffer.from([0x3b]),
]));

// WebP animé : deux trames (rouge puis bleu) assemblées à partir de WebP fixes
function chunk(id, charge) { return Buffer.concat([Buffer.from(id), u32(charge.length), charge, charge.length % 2 ? Buffer.from([0]) : Buffer.alloc(0)]); }
/** Chunks d'image (ALPH, VP8, VP8L) d'un WebP fixe, sans VP8X ni profil ICC. */
function trameDe(webp) {
  const gardes = [];
  for (let pos = 12; pos + 8 <= webp.length;) {
    const id = webp.toString('latin1', pos, pos + 4); const taille = webp.readUInt32LE(pos + 4);
    const fin = pos + 8 + taille + (taille % 2);
    if (['ALPH', 'VP8 ', 'VP8L'].includes(id)) gardes.push(webp.subarray(pos, fin));
    pos = fin;
  }
  return Buffer.concat(gardes);
}
const rouge = await uni(64, 64, '#ff0000', 'image/webp'); const bleu = await uni(64, 64, '#0000ff', 'image/webp');
const u24 = (n) => Buffer.from([n & 255, (n >> 8) & 255, (n >> 16) & 255]);
const anmf = (webp) => chunk('ANMF', Buffer.concat([u24(0), u24(0), u24(63), u24(63), u24(100), Buffer.from([0]), trameDe(webp)]));
const corps = Buffer.concat([
  Buffer.from('WEBP'),
  chunk('VP8X', Buffer.concat([Buffer.from([0x02, 0, 0, 0]), u24(63), u24(63)])),
  chunk('ANIM', Buffer.concat([u32(0xffffffff), u16(0)])),
  anmf(rouge), anmf(bleu),
]);
ecrire('photo-animee.webp', Buffer.concat([Buffer.from('RIFF'), u32(corps.length), corps]));

await navigateur.close();
console.log('fixtures de photo générées');
