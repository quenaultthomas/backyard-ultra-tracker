/** Côté long maximal de la photo envoyée, en pixels (jamais d'agrandissement). */
const COTE_MAX = 512;
const QUALITE_JPEG = 0.85;
/** Au-delà, le fichier d'origine n'est pas décodé (protection de la mémoire du navigateur). */
const TAILLE_MAX_ORIGINE = 20 * 1024 * 1024;
/** Plafond de l'API : un résultat plus lourd n'est jamais envoyé. */
const TAILLE_MAX_ENVOI = 2 * 1024 * 1024;
const FOND = '#ffffff';

/** Raison d'un échec de préparation, affichée sans qu'aucune requête ne soit émise. */
export type MotifEchecPreparation = 'ILLISIBLE' | 'TROP_VOLUMINEUX' | 'IMPOSSIBLE';

export class EchecPreparationPhoto extends Error {
  constructor(readonly motif: MotifEchecPreparation) {
    super(`Préparation de la photo impossible (${motif})`);
  }
}

type ImageDecodee = ImageBitmap | HTMLImageElement;

/**
 * Prépare la photo choisie, entièrement dans le navigateur : décodage avec l'orientation EXIF,
 * réduction du côté long à 512 px au plus, fond blanc, ré-encodage JPEG par canvas. Le canvas ne
 * conserve aucune métadonnée (EXIF, GPS, miniature, commentaire, profil ICC). Le serveur reste
 * la barrière de sécurité : il refait ses propres contrôles.
 */
export async function preparerPhoto(fichier: File): Promise<Blob> {
  if (fichier.size > TAILLE_MAX_ORIGINE) {
    throw new EchecPreparationPhoto('TROP_VOLUMINEUX');
  }
  const image = await decoder(fichier);
  try {
    return await encoderEnJpeg(image);
  } catch {
    throw new EchecPreparationPhoto('IMPOSSIBLE');
  } finally {
    if (image instanceof ImageBitmap) {
      image.close();
    }
  }
}

async function decoder(fichier: File): Promise<ImageDecodee> {
  try {
    if (typeof createImageBitmap === 'function') {
      return await createImageBitmap(fichier, { imageOrientation: 'from-image' });
    }
    return await decoderParElementImage(fichier);
  } catch {
    throw new EchecPreparationPhoto('ILLISIBLE');
  }
}

/** Repli sans `createImageBitmap` ; URL `data:` (la CSP `img-src 'self' data:` exclut `blob:`). */
async function decoderParElementImage(fichier: File): Promise<HTMLImageElement> {
  const url = await new Promise<string>((resoudre, rejeter) => {
    const lecteur = new FileReader();
    lecteur.onerror = () => rejeter(lecteur.error);
    lecteur.onload = () => resoudre(String(lecteur.result));
    lecteur.readAsDataURL(fichier);
  });
  const image = new Image();
  image.src = url;
  await image.decode();
  return image;
}

async function encoderEnJpeg(image: ImageDecodee): Promise<Blob> {
  const largeurOrigine = image instanceof ImageBitmap ? image.width : image.naturalWidth;
  const hauteurOrigine = image instanceof ImageBitmap ? image.height : image.naturalHeight;
  const echelle = Math.min(1, COTE_MAX / Math.max(largeurOrigine, hauteurOrigine));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(largeurOrigine * echelle));
  canvas.height = Math.max(1, Math.round(hauteurOrigine * echelle));
  const contexte = canvas.getContext('2d');
  if (contexte === null) {
    throw new EchecPreparationPhoto('IMPOSSIBLE');
  }
  // Fond blanc d'abord : une zone transparente deviendrait noire en JPEG.
  contexte.fillStyle = FOND;
  contexte.fillRect(0, 0, canvas.width, canvas.height);
  contexte.imageSmoothingQuality = 'high';
  contexte.drawImage(image, 0, 0, canvas.width, canvas.height);
  const resultat = await new Promise<Blob | null>((resoudre) =>
    canvas.toBlob(resoudre, 'image/jpeg', QUALITE_JPEG),
  );
  if (resultat === null || resultat.size > TAILLE_MAX_ENVOI) {
    throw new EchecPreparationPhoto('IMPOSSIBLE');
  }
  return resultat;
}
