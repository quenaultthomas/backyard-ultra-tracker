import type { Page } from '@playwright/test';
import QRCode from 'qrcode';

/**
 * Caméra simulée (incrément 4.5) : remplace `navigator.mediaDevices` avant le chargement de la page.
 * La « caméra » est un canevas (`captureStream`) sur lequel on dessine soit un fond neutre, soit l'image
 * d'un QR généré côté Node. Aucune simulation de la bibliothèque de lecture : l'écran lit vraiment les images.
 */

export type ModeCamera = 'ok' | 'refuser' | 'absente' | 'occupee' | 'autre' | 'contrainteRejetee';

export interface OptionsCamera {
  mode?: ModeCamera;
  /** Nombre de caméras exposées (1 ou 2) ; la première est la caméra arrière. */
  cameras?: 1 | 2;
  /** Pas d'API de capture du tout (contexte non sécurisé simulé). */
  sansApi?: boolean;
}

export interface EtatCamera {
  demandes: number;
  contraintes: Array<{ audio?: unknown; video?: unknown }>;
  fluxActifs: number;
  /** Pistes vidéo « live » par flux, dans l'ordre de création. */
  pistesActivesParFlux: number[];
  /** Identifiants de caméra des demandes (flux créés). */
  camerasOuvertes: string[];
  demandesMicro: number;
}

export const CAMERA_ARRIERE = 'camera-arriere';
export const CAMERA_AVANT = 'camera-avant';

/** À appeler avant tout `goto` / connexion. */
export async function installerCameraSimulee(page: Page, options: OptionsCamera = {}): Promise<void> {
  await page.addInitScript(
    ({ mode, cameras, sansApi, arriere, avant }) => {
      if (sansApi) {
        Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true });
        return;
      }
      const w = window as unknown as Record<string, any>;
      const etat = {
        mode,
        cameras: [
          { deviceId: arriere, label: 'Caméra arrière', kind: 'videoinput', groupId: 'g1' },
          ...(cameras === 2 ? [{ deviceId: avant, label: 'Caméra avant', kind: 'videoinput', groupId: 'g2' }] : []),
        ],
        demandes: [] as unknown[],
        flux: [] as Array<{ stream: MediaStream; camera: string }>,
        image: null as HTMLImageElement | null,
        masque: false,
        demandesMicro: 0,
      };
      w['__camera'] = etat;

      const canevas = document.createElement('canvas');
      canevas.width = 640;
      canevas.height = 480;
      const ctx = canevas.getContext('2d')!;
      const dessiner = () => {
        ctx.fillStyle = '#9a9a9a';
        ctx.fillRect(0, 0, 640, 480);
        if (etat.image) {
          ctx.drawImage(etat.image, 120, 40, 400, 400);
        }
      };
      dessiner();
      setInterval(dessiner, 66);

      const erreur = (nom: string) => new DOMException(`simulée : ${nom}`, nom);

      const getUserMedia = async (contraintes: MediaStreamConstraints) => {
        etat.demandes.push(JSON.parse(JSON.stringify(contraintes ?? {})));
        if (contraintes?.audio) etat.demandesMicro++;
        if (etat.mode === 'refuser') throw erreur('NotAllowedError');
        if (etat.mode === 'absente') throw erreur('NotFoundError');
        if (etat.mode === 'occupee') throw erreur('NotReadableError');
        if (etat.mode === 'autre') throw erreur('AbortError');
        const video = contraintes?.video;
        const contrainte = typeof video === 'object' && video ? (video as MediaTrackConstraints) : {};
        if (etat.mode === 'contrainteRejetee' && contrainte.facingMode) throw erreur('OverconstrainedError');
        const exact = (contrainte.deviceId as ConstrainDOMStringParameters | undefined)?.exact;
        const identifiant = (Array.isArray(exact) ? exact[0] : exact) as string | undefined;
        const camera = etat.cameras.find((c) => c.deviceId === identifiant) ?? etat.cameras[0];
        const stream = canevas.captureStream(15);
        stream.getVideoTracks().forEach((piste) => {
          const reglages = piste.getSettings.bind(piste);
          piste.getSettings = () => ({ ...reglages(), deviceId: camera.deviceId });
        });
        etat.flux.push({ stream, camera: camera.deviceId });
        return stream;
      };

      const fausse = {
        getUserMedia,
        enumerateDevices: async () => etat.cameras.map((c) => ({ ...c, toJSON: () => c })),
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
      };
      Object.defineProperty(navigator, 'mediaDevices', { value: fausse, configurable: true });

      Object.defineProperty(document, 'hidden', { get: () => etat.masque, configurable: true });
      Object.defineProperty(document, 'visibilityState', { get: () => (etat.masque ? 'hidden' : 'visible'), configurable: true });
    },
    { mode: options.mode ?? 'ok', cameras: options.cameras ?? 1, sansApi: options.sansApi ?? false, arriere: CAMERA_ARRIERE, avant: CAMERA_AVANT },
  );
}

/** Dessine le QR de `contenu` devant la caméra (jeton ou contenu quelconque). */
export async function presenterQr(page: Page, contenu: string): Promise<void> {
  const url = await QRCode.toDataURL(contenu, { width: 400, margin: 4, errorCorrectionLevel: 'M', color: { dark: '#000000', light: '#ffffff' } });
  await page.evaluate(
    (src) =>
      new Promise<void>((resolve, reject) => {
        const image = new Image();
        image.onload = () => {
          (window as any)['__camera'].image = image;
          resolve();
        };
        image.onerror = () => reject(new Error('image du QR illisible'));
        image.src = src;
      }),
    url,
  );
}

/** Le canevas redevient neutre. */
export async function retirerQr(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as any)['__camera'].image = null;
  });
}

export async function changerMode(page: Page, mode: ModeCamera): Promise<void> {
  await page.evaluate((m) => {
    (window as any)['__camera'].mode = m;
  }, mode);
}

/** La caméra est débranchée : les pistes se terminent et émettent `ended`. */
export async function debrancher(page: Page): Promise<void> {
  await page.evaluate(() => {
    for (const { stream } of (window as any)['__camera'].flux as Array<{ stream: MediaStream }>) {
      for (const piste of stream.getTracks()) {
        if (piste.readyState === 'live') {
          piste.stop();
          piste.dispatchEvent(new Event('ended'));
        }
      }
    }
  });
}

/** L'onglet devient invisible (ou visible) : `document.hidden` et événement `visibilitychange`. */
export async function basculerVisibilite(page: Page, visible: boolean): Promise<void> {
  await page.evaluate((v) => {
    (window as any)['__camera'].masque = !v;
    document.dispatchEvent(new Event('visibilitychange'));
  }, visible);
}

export async function etatCamera(page: Page): Promise<EtatCamera> {
  return page.evaluate(() => {
    const etat = (window as any)['__camera'];
    if (!etat) return { demandes: 0, contraintes: [], fluxActifs: 0, pistesActivesParFlux: [], camerasOuvertes: [], demandesMicro: 0 };
    const pistes = (etat.flux as Array<{ stream: MediaStream }>).map(
      ({ stream }) => stream.getTracks().filter((p) => p.readyState === 'live').length,
    );
    return {
      demandes: etat.demandes.length,
      contraintes: etat.demandes,
      fluxActifs: pistes.filter((n) => n > 0).length,
      pistesActivesParFlux: pistes,
      camerasOuvertes: etat.flux.map((f: { camera: string }) => f.camera),
      demandesMicro: etat.demandesMicro,
    };
  });
}
