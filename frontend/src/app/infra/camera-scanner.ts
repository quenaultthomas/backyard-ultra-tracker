import { QrFrameReader } from './qr-code';

export type CameraStartResult = 'started' | 'denied' | 'unavailable';

const READ_INTERVAL_MS = 200;

/**
 * Caméra de l'écran de scan (RG15) : caméra arrière préférée, lecture continue des images, écran maintenu
 * allumé et lampe quand le navigateur le permet (capacités optionnelles).
 */
export class CameraScanner {
  private stream: MediaStream | null = null;
  private wakeLock: WakeLockSentinel | null = null;
  private timer: number | null = null;
  private readonly reader = new QrFrameReader();

  constructor(private readonly video: HTMLVideoElement, private readonly onContent: (content: string) => void,
              private readonly onError: (error: unknown) => void) {}

  get active(): boolean {
    return this.stream !== null;
  }

  async start(): Promise<CameraStartResult> {
    if (!window.isSecureContext || typeof navigator.mediaDevices?.getUserMedia !== 'function') {
      return 'unavailable';
    }
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' } },
        audio: false,
      });
    } catch (error: unknown) {
      return error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'SecurityError')
        ? 'denied'
        : 'unavailable';
    }
    this.video.srcObject = this.stream;
    this.video.setAttribute('playsinline', 'true');
    this.video.muted = true;
    await this.video.play();
    await this.reader.initialize();
    await this.keepScreenOn();
    this.scheduleRead();
    return 'started';
  }

  stop(): void {
    if (this.timer !== null) {
      window.clearTimeout(this.timer);
      this.timer = null;
    }
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    this.video.srcObject = null;
    void this.wakeLock?.release();
    this.wakeLock = null;
  }

  /** Vrai si la caméra expose une torche. */
  torchAvailable(): boolean {
    const track = this.stream?.getVideoTracks()[0];
    const capabilities = track?.getCapabilities?.() as (MediaTrackCapabilities & { torch?: boolean }) | undefined;
    return capabilities?.torch === true;
  }

  async setTorch(on: boolean): Promise<void> {
    const track = this.stream?.getVideoTracks()[0];
    await track?.applyConstraints({ advanced: [{ torch: on } as MediaTrackConstraintSet] });
  }

  private async keepScreenOn(): Promise<void> {
    if (!('wakeLock' in navigator)) {
      return;
    }
    try {
      this.wakeLock = await navigator.wakeLock.request('screen');
    } catch (error: unknown) {
      console.info('Maintien de l\'écran allumé refusé par le navigateur', error);
    }
  }

  private scheduleRead(): void {
    this.timer = window.setTimeout(() => {
      void this.readFrame();
    }, READ_INTERVAL_MS);
  }

  private async readFrame(): Promise<void> {
    if (this.stream === null) {
      return;
    }
    try {
      const content = await this.reader.read(this.video);
      if (content !== null) {
        this.onContent(content);
      }
    } catch (error: unknown) {
      this.onError(error);
    }
    if (this.stream !== null) {
      this.scheduleRead();
    }
  }
}
