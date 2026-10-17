// Génère, une fois, les PNG et le favicon de l'application depuis les SVG sources
// (frontend/public/icones/icone.svg et icone-maskable.svg). Procédure : docs/design.md,
// section « Icône de l'application ». Fichiers produits commités ; jamais lancé au build.
import { readFile, writeFile } from 'node:fs/promises';
import sharp from 'sharp';

const DOSSIER = process.argv[2] ?? 'frontend/public';
const FORET = '#14352A';

/** PNG opaque (type de couleur 2, sans canal alpha) : fond forêt aplati. */
async function png(source, taille) {
  const svg = await readFile(`${DOSSIER}/icones/${source}`);
  // Rendu à 1024 px (densité 144) puis réduction : bords lissés à toutes les tailles.
  return sharp(svg, { density: 144 })
    .resize(taille, taille)
    .flatten({ background: FORET })
    .removeAlpha()
    .png({ compressionLevel: 9 })
    .toBuffer();
}

/** Fichier ICO contenant une seule image PNG (format accepté par tous les navigateurs actuels). */
function ico(imagePng, taille) {
  const entete = Buffer.alloc(22);
  entete.writeUInt16LE(0, 0);
  entete.writeUInt16LE(1, 2);
  entete.writeUInt16LE(1, 4);
  entete.writeUInt8(taille, 6);
  entete.writeUInt8(taille, 7);
  entete.writeUInt16LE(1, 10);
  entete.writeUInt16LE(24, 12);
  entete.writeUInt32LE(imagePng.length, 14);
  entete.writeUInt32LE(22, 18);
  return Buffer.concat([entete, imagePng]);
}

const sorties = [
  ['icones/icone-192.png', await png('icone.svg', 192)],
  ['icones/icone-512.png', await png('icone.svg', 512)],
  ['icones/icone-512-maskable.png', await png('icone-maskable.svg', 512)],
  ['icones/apple-touch-icon.png', await png('icone.svg', 180)],
  ['favicon.ico', ico(await png('icone.svg', 32), 32)],
];
for (const [chemin, contenu] of sorties) {
  await writeFile(`${DOSSIER}/${chemin}`, contenu);
  console.log(`${chemin} : ${contenu.length} octets`);
}
