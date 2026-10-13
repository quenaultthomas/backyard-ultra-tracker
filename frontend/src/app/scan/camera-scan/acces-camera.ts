/** Cause d'un échec de démarrage de la caméra, déduite du nom de l'erreur du navigateur. */
export type EchecCamera = 'refusee' | 'absente' | 'occupee' | 'autre';

export const MESSAGES_ECHEC_CAMERA: Record<EchecCamera, string> = {
  refusee:
    "L'accès à la caméra est refusé. Autorisez-la dans les réglages du navigateur, ou saisissez le jeton ci-dessous.",
  absente: 'Aucune caméra détectée. Saisissez le jeton ci-dessous.',
  occupee:
    'La caméra est utilisée par une autre application. Fermez-la puis réessayez, ou saisissez le jeton ci-dessous.',
  autre: 'Impossible de démarrer la caméra. Saisissez le jeton ci-dessous.',
};

/** L'API de capture n'existe qu'en contexte sécurisé (HTTPS, localhost). */
export function cameraDisponible(): boolean {
  return typeof navigator.mediaDevices?.getUserMedia === 'function';
}

/**
 * Ouvre une caméra, sans audio : celle demandée, sinon de préférence la caméra arrière.
 * Si le navigateur rejette cette préférence, la demande est refaite sans contrainte.
 */
export async function ouvrirCamera(identifiantCamera?: string): Promise<MediaStream> {
  const video: MediaTrackConstraints = identifiantCamera
    ? { deviceId: { exact: identifiantCamera } }
    : { facingMode: { ideal: 'environment' } };
  try {
    return await navigator.mediaDevices.getUserMedia({ audio: false, video });
  } catch (erreur: unknown) {
    if (identifiantCamera || nomErreur(erreur) !== 'OverconstrainedError') {
      throw erreur;
    }
    return navigator.mediaDevices.getUserMedia({ audio: false, video: true });
  }
}

/** Identifiants des caméras de l'appareil ; liste vide si le navigateur ne les expose pas. */
export async function listerCameras(): Promise<string[]> {
  if (typeof navigator.mediaDevices.enumerateDevices !== 'function') {
    return [];
  }
  const appareils = await navigator.mediaDevices.enumerateDevices();
  return appareils
    .filter((appareil) => appareil.kind === 'videoinput' && appareil.deviceId !== '')
    .map((appareil) => appareil.deviceId);
}

/** Caméra suivante dans l'ordre de la liste (rotation) ; caméra inconnue : traitée comme la première. */
export function cameraSuivante(cameras: string[], courante: string | undefined): string {
  const index = courante === undefined ? 0 : Math.max(cameras.indexOf(courante), 0);
  return cameras[(index + 1) % cameras.length];
}

export function causeEchec(erreur: unknown): EchecCamera {
  switch (nomErreur(erreur)) {
    case 'NotAllowedError':
    case 'SecurityError':
      return 'refusee';
    case 'NotFoundError':
      return 'absente';
    case 'NotReadableError':
      return 'occupee';
    default:
      return 'autre';
  }
}

export function couperFlux(flux: MediaStream): void {
  flux.getTracks().forEach((piste) => piste.stop());
}

function nomErreur(erreur: unknown): string {
  return typeof erreur === 'object' && erreur !== null && 'name' in erreur
    ? String(erreur.name)
    : '';
}
