import { binarize, Byte, Decoder, Detector, Encoder, grayscale } from '@nuintun/qrcode';

/**
 * Génération et décodage des QR codes, entièrement locaux (RG12, RG15, PO5) : bibliothèque embarquée dans le
 * bundle, sans service tiers ni `eval`. Le contenu d'un QR est exactement le `qrToken`, sans préfixe ni URL.
 */

const MODULE_SIZE_PX = 8;
const QUIET_ZONE_MODULES = 4;

/** Image GIF du QR code en URL `data:` (autorisée par la CSP `img-src 'self' data:`). */
export function qrCodeDataUrl(content: string): string {
  const encoded = new Encoder({ level: 'M' }).encode(new Byte(content));
  return encoded.toDataURL(MODULE_SIZE_PX, { margin: QUIET_ZONE_MODULES });
}

interface DetectedBarcode {
  readonly rawValue: string;
}

interface BarcodeDetectorInstance {
  detect(source: CanvasImageSource): Promise<DetectedBarcode[]>;
}

interface BarcodeDetectorConstructor {
  new (options: { formats: string[] }): BarcodeDetectorInstance;
  getSupportedFormats(): Promise<string[]>;
}

const MAX_ANALYSED_WIDTH = 640;

/**
 * Lecture d'une image de la caméra : l'API `BarcodeDetector` du navigateur quand elle sait lire les QR codes,
 * sinon la bibliothèque embarquée (Safari iOS notamment).
 */
export class QrFrameReader {
  private nativeDetector: BarcodeDetectorInstance | null = null;
  private readonly canvas = document.createElement('canvas');
  private readonly context = this.canvas.getContext('2d', { willReadFrequently: true });
  private readonly detector = new Detector();
  private readonly decoder = new Decoder();

  async initialize(): Promise<void> {
    const constructor = (globalThis as { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector;
    if (constructor === undefined) {
      return;
    }
    try {
      const formats = await constructor.getSupportedFormats();
      this.nativeDetector = formats.includes('qr_code') ? new constructor({ formats: ['qr_code'] }) : null;
    } catch (error: unknown) {
      console.warn('BarcodeDetector inutilisable, lecture par la bibliothèque embarquée', error);
      this.nativeDetector = null;
    }
  }

  /** Contenu du premier QR code lisible de l'image, ou null. */
  async read(video: HTMLVideoElement): Promise<string | null> {
    if (video.videoWidth === 0 || video.videoHeight === 0) {
      return null;
    }
    if (this.nativeDetector !== null) {
      const codes = await this.nativeDetector.detect(video);
      return codes.length > 0 ? codes[0].rawValue : null;
    }
    return this.readWithLibrary(video);
  }

  private readWithLibrary(video: HTMLVideoElement): string | null {
    if (this.context === null) {
      return null;
    }
    const scale = Math.min(1, MAX_ANALYSED_WIDTH / video.videoWidth);
    const width = Math.round(video.videoWidth * scale);
    const height = Math.round(video.videoHeight * scale);
    this.canvas.width = width;
    this.canvas.height = height;
    this.context.drawImage(video, 0, 0, width, height);
    const binarized = binarize(grayscale(this.context.getImageData(0, 0, width, height)), width, height);
    const candidates = this.detector.detect(binarized);
    let current = candidates.next();
    while (current.done !== true) {
      const content = this.tryDecode(current.value.matrix);
      if (content !== null) {
        return content;
      }
      current = candidates.next(false);
    }
    return null;
  }

  private tryDecode(matrix: Parameters<Decoder['decode']>[0]): string | null {
    try {
      return this.decoder.decode(matrix).content;
    } catch {
      // Zone détectée mais illisible (flou, reflet) : ce n'est pas une erreur, l'image suivante sera analysée.
      return null;
    }
  }
}
