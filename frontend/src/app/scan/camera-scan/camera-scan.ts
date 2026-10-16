import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  ElementRef,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';

import {
  MESSAGES_ECHEC_CAMERA,
  cameraDisponible,
  cameraSuivante,
  causeEchec,
  couperFlux,
  listerCameras,
  ouvrirCamera,
} from './acces-camera';
import { AntiRepetition } from './anti-repetition';
import { LecteurQr } from './lecteur-qr';

/** Format d'un jeton QR (43 caractères base64url) : tout autre contenu n'est pas envoyé. */
const FORMAT_JETON = /^[A-Za-z0-9_-]{43}$/;
const MESSAGE_ARRET_AUTOMATIQUE = "La caméra s'est arrêtée.";
const MESSAGE_QR_ETRANGER = "Ce QR code n'est pas un dossard.";
const DUREE_MESSAGE_QR_ETRANGER_MILLISECONDES = 3_000;

type EtatCamera = 'arretee' | 'activation' | 'active';

/** Échec ou arrêt automatique (`alert`, avec « Réessayer »), ou QR non reconnu (`status`). */
interface MessageCamera {
  texte: string;
  role: 'alert' | 'status';
}

/**
 * Scan à la caméra : activation explicite, aperçu, lecture continue des QR et arrêt.
 * Émet chaque jeton lu une seule fois par présentation ; l'envoi reste à l'écran de scan.
 */
@Component({
  selector: 'app-camera-scan',
  templateUrl: './camera-scan.html',
  styleUrl: './camera-scan.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CameraScan {
  private readonly document = inject(DOCUMENT);
  private readonly video = viewChild<ElementRef<HTMLVideoElement>>('video');
  private readonly lecteur = new LecteurQr();
  private readonly antiRepetition = new AntiRepetition();

  /** Chaque jeton lu est mis en file par l'écran de scan, sans attendre l'envoi précédent. */
  readonly jetonLu = output<string>();

  protected readonly disponible = cameraDisponible();
  protected readonly etat = signal<EtatCamera>('arretee');
  protected readonly flux = signal<MediaStream | null>(null);
  protected readonly plusieursCameras = signal(false);
  protected readonly message = signal<MessageCamera | null>(null);
  private cameras: string[] = [];
  private cameraCourante: string | undefined;
  private minuterieMessage: ReturnType<typeof setTimeout> | undefined;
  private detruit = false;

  constructor() {
    const surVisibilite = (): void => {
      if (this.document.hidden && this.etat() !== 'arretee') {
        this.arreter(MESSAGE_ARRET_AUTOMATIQUE);
      }
    };
    this.document.addEventListener('visibilitychange', surVisibilite);
    inject(DestroyRef).onDestroy(() => {
      this.detruit = true;
      this.document.removeEventListener('visibilitychange', surVisibilite);
      this.arreter(null);
    });
  }

  protected async activer(identifiantCamera?: string): Promise<void> {
    this.message.set(null);
    this.etat.set('activation');
    try {
      const flux = await ouvrirCamera(identifiantCamera);
      if (this.detruit || this.document.hidden || this.etat() !== 'activation') {
        couperFlux(flux);
        return;
      }
      this.brancher(flux, identifiantCamera);
    } catch (erreur: unknown) {
      if (!this.detruit && this.etat() === 'activation') {
        this.etat.set('arretee');
        this.message.set({ texte: MESSAGES_ECHEC_CAMERA[causeEchec(erreur)], role: 'alert' });
      }
    }
  }

  protected arreterManuellement(): void {
    this.arreter(null);
  }

  /** L'ancien flux est arrêté avant la demande de la caméra suivante. */
  protected basculer(): void {
    const suivante = cameraSuivante(this.cameras, this.cameraCourante);
    this.arreter(null);
    void this.activer(suivante);
  }

  private brancher(flux: MediaStream, identifiantCamera: string | undefined): void {
    const piste = flux.getVideoTracks()[0];
    this.cameraCourante = identifiantCamera ?? piste?.getSettings?.().deviceId;
    flux.getTracks().forEach((p) => p.addEventListener('ended', () => this.surFinDeFlux(flux)));
    this.flux.set(flux);
    this.etat.set('active');
    listerCameras().then(
      (cameras) => this.retenirCameras(flux, cameras),
      () => this.retenirCameras(flux, []),
    );
    this.lecteur
      .demarrer(
        () => this.video()?.nativeElement,
        (contenu, instant) => this.traiterLecture(contenu, instant),
      )
      .catch(() => this.arreter(MESSAGES_ECHEC_CAMERA.autre));
  }

  private retenirCameras(flux: MediaStream, cameras: string[]): void {
    if (this.flux() === flux) {
      this.cameras = cameras;
      this.plusieursCameras.set(cameras.length >= 2);
    }
  }

  private surFinDeFlux(flux: MediaStream): void {
    if (this.flux() === flux) {
      this.arreter(MESSAGE_ARRET_AUTOMATIQUE);
    }
  }

  private traiterLecture(contenu: string | null, instant: number): void {
    if (!this.antiRepetition.estNouveau(contenu, instant)) {
      return;
    }
    if (!FORMAT_JETON.test(contenu)) {
      this.antiRepetition.retenir(contenu, instant);
      this.afficherQrEtranger();
      return;
    }
    this.antiRepetition.retenir(contenu, instant);
    this.effacerQrEtranger();
    this.jetonLu.emit(contenu);
  }

  private afficherQrEtranger(): void {
    clearTimeout(this.minuterieMessage);
    this.message.set({ texte: MESSAGE_QR_ETRANGER, role: 'status' });
    this.minuterieMessage = setTimeout(
      () => this.effacerQrEtranger(),
      DUREE_MESSAGE_QR_ETRANGER_MILLISECONDES,
    );
  }

  private effacerQrEtranger(): void {
    clearTimeout(this.minuterieMessage);
    if (this.message()?.role === 'status') {
      this.message.set(null);
    }
  }

  /** Arrête la lecture et toutes les pistes ; un message d'arrêt automatique propose « Réessayer ». */
  private arreter(messageArret: string | null): void {
    this.lecteur.arreter();
    this.antiRepetition.oublier();
    clearTimeout(this.minuterieMessage);
    const flux = this.flux();
    if (flux) {
      couperFlux(flux);
    }
    this.flux.set(null);
    this.plusieursCameras.set(false);
    this.etat.set('arretee');
    this.message.set(messageArret ? { texte: messageArret, role: 'alert' } : null);
  }
}
