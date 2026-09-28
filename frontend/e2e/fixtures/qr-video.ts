import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Byte, Encoder } from '@nuintun/qrcode';

/**
 * CA27 (RG58, RG15) : sous Chromium, l'option `--use-file-for-fake-video-capture` remplace la caméra par la
 * lecture d'un fichier Y4M en boucle. On y encode le QR code du `qrToken` visé, en dessinant nous-mêmes la
 * matrice de modules (pas de dépendance à `canvas`, absente du dépôt) : image en niveaux de gris (Y),
 * chrominance neutre (U = V = 128), format I420 (4:2:0), dimensions paires exigées par le sous-échantillonnage.
 * Le QR est un format standard : n'importe quel lecteur conforme (BarcodeDetector ou la bibliothèque embarquée
 * de l'application) le décode, sans dépendre de l'implémentation d'encodage utilisée ici.
 */

const MODULE_PX = 8;
const MARGIN_MODULES = 4;
const FRAME_RATE = 10;
/**
 * Le QR n'est diffusé que pendant les premières images du fichier, suivies d'images blanches jusqu'à la fin
 * de la boucle (RG17, PO15) : cela suffit à exercer l'anti-rebond (plusieurs lectures identiques
 * consécutives, une seule capture), sans re-déclencher indéfiniment « déjà enregistré » à chaque image
 * suivante, ce qui rendrait le bandeau vert illisible par le test entre deux lectures (RG50).
 */
const QR_FRAME_COUNT = 4;
const BLANK_FRAME_COUNT = 80; // 8 s à 10 im/s : largement au-delà des 5 s d'observation de CA27.

/** Génère un fichier Y4M contenant le QR code de `token` (puis du blanc), et rend son chemin. */
export function generateQrCodeVideo(token: string): string {
  const encoded = new Encoder({ level: 'M' }).encode(new Byte(token));
  const modules = encoded.size;
  const marginPx = MARGIN_MODULES * MODULE_PX;
  const rawSize = modules * MODULE_PX + marginPx * 2;
  const size = rawSize % 2 === 0 ? rawSize : rawSize + 1; // I420 : dimensions paires

  const qrLuma = new Uint8Array(size * size).fill(255); // fond blanc
  for (let y = 0; y < modules * MODULE_PX; y++) {
    const moduleY = Math.floor(y / MODULE_PX);
    for (let x = 0; x < modules * MODULE_PX; x++) {
      const moduleX = Math.floor(x / MODULE_PX);
      if (encoded.get(moduleX, moduleY) === 1) {
        qrLuma[(y + marginPx) * size + (x + marginPx)] = 0; // module sombre
      }
    }
  }
  const blankLuma = new Uint8Array(size * size).fill(255);

  const chromaSize = size / 2;
  const chroma = new Uint8Array(chromaSize * chromaSize).fill(128); // achromatique

  const header = `YUV4MPEG2 W${size} H${size} F${FRAME_RATE}:1 Ip A1:1 C420jpeg\n`;
  const frameHeader = 'FRAME\n';
  const chunks: Uint8Array[] = [Buffer.from(header, 'ascii')];
  for (let frame = 0; frame < QR_FRAME_COUNT; frame++) {
    chunks.push(Buffer.from(frameHeader, 'ascii'), qrLuma, chroma, chroma);
  }
  for (let frame = 0; frame < BLANK_FRAME_COUNT; frame++) {
    chunks.push(Buffer.from(frameHeader, 'ascii'), blankLuma, chroma, chroma);
  }

  const directory = mkdtempSync(join(tmpdir(), 'backyard-e2e-qr-'));
  const filePath = join(directory, `${sanitize(token)}.y4m`);
  writeFileSync(filePath, Buffer.concat(chunks));
  return filePath;
}

function sanitize(token: string): string {
  return token.replace(/[^a-z0-9-]/gi, '_');
}
