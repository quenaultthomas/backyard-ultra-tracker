import { binarize, Decoder, Detector, grayscale } from '@nuintun/qrcode';
import type { Page } from '@playwright/test';

/**
 * Décodage réel d'un QR affiché par la page (CA22, CA36 : « le QR code affiché, décodé par le test »).
 * L'image (`<img>`, contenu `data:image/gif`) est dessinée dans un canevas côté navigateur pour obtenir ses
 * pixels bruts (décodage GIF natif du navigateur) ; la matrice de bits est ensuite détectée et décodée côté
 * test, avec les mêmes fonctions que `QrFrameReader` (grayscale, binarize, Detector, Decoder), mais dans une
 * instance de bibliothèque distincte de celle de l'application.
 */
export async function decodeQrImage(page: Page, selector: string): Promise<string | null> {
  const pixels = await page.evaluate((sel) => {
    const img = document.querySelector(sel) as HTMLImageElement | null;
    if (img === null) {
      return null;
    }
    const canvas = document.createElement('canvas');
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    const context = canvas.getContext('2d');
    if (context === null) {
      return null;
    }
    context.drawImage(img, 0, 0);
    const imageData = context.getImageData(0, 0, canvas.width, canvas.height);
    return { width: canvas.width, height: canvas.height, data: Array.from(imageData.data) };
  }, selector);
  if (pixels === null) {
    return null;
  }
  return decodeFromRgba(pixels.data, pixels.width, pixels.height);
}

function decodeFromRgba(data: readonly number[], width: number, height: number): string | null {
  const imageData = { data: Uint8ClampedArray.from(data), width, height } as unknown as ImageData;
  const luminances = grayscale(imageData);
  const matrix = binarize(luminances, width, height);
  const decoder = new Decoder();
  const candidates = new Detector().detect(matrix);
  let current = candidates.next();
  while (current.done !== true) {
    try {
      return decoder.decode(current.value.matrix).content;
    } catch {
      // Zone détectée mais illisible : on continue avec le candidat suivant, comme QrFrameReader.
    }
    current = candidates.next(false);
  }
  return null;
}
