import { Component, computed, ElementRef, inject, OnDestroy, OnInit, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { clockStatusLabel } from '../../core/clock-offset';
import { formatTimeOfDay } from '../../core/formats';
import { tokenSuffix } from '../../core/qr-token';
import { itemStateLabel, knownRunner, networkIndicator, rejectionText } from '../../core/scan-feedback';
import { ScanItem } from '../../core/scan-item';
import { AuthState } from '../../infra/auth-state';
import { CameraScanner } from '../../infra/camera-scanner';
import { ClockOffsetService } from '../../infra/clock-offset.service';
import { FeedbackPlayer } from '../../infra/feedback-player';
import { ScanQueueService } from '../../infra/scan-queue.service';
import { LogoutButton } from '../../shared/logout-button';
import { secondTicker } from '../../shared/page-lifecycle';

const HISTORY_SIZE = 20;
const PERSISTENCE_ASKED_KEY = 'backyard.storage-persist-asked';

type CameraState = 'off' | 'starting' | 'on' | 'denied' | 'unavailable';

/**
 * Écran de scan (RG14 à RG27, RG47, RG50). La capture ne dépend ni du réseau ni des identifiants ; seul
 * l'envoi en dépend. Aucune action de course (DNF, réintégration) n'existe ici.
 */
@Component({
  selector: 'app-scan-page',
  imports: [RouterLink, LogoutButton],
  template: `
    <h1>Scan</h1>
    <section class="indicators" aria-label="État de l'appareil">
      <span class="indicator">{{ network() }}</span>
      <span class="indicator">{{ account() }}</span>
      <span class="indicator">{{ snapshot().pendingCount }} en attente</span>
      @if (snapshot().unseenRejectionCount > 0) {
        <span class="indicator indicator-error">✖ {{ snapshot().unseenRejectionCount }} rejeté{{ snapshot().unseenRejectionCount > 1 ? 's' : '' }}</span>
      }
      <span class="indicator" [class.indicator-warning]="clockStatus().kind !== 'verified'">{{ clockLabel() }}</span>
    </section>

    @if (storageWarning()) {
      <p class="banner banner-warning">
        Le navigateur peut effacer les scans en attente s'il manque de place. Installez l'application et ne videz
        pas les données du site pendant la course.
      </p>
    }
    @if (queueFailure(); as message) {
      <p class="banner banner-error" role="alert">{{ message }}</p>
    }
    @if (!connected()) {
      <div class="banner banner-warning">
        <span>Non connecté : envoi suspendu</span>
        <a class="button" routerLink="/connexion" [queryParams]="{ retour: '/scan' }">Se connecter</a>
      </div>
    }
    @if (snapshot().pendingCount > 0) {
      <p class="banner banner-info">Gardez l'application ouverte : {{ snapshot().pendingCount }} scans en attente</p>
    }

    <div class="scan-result" [class]="'scan-result tone-' + (feedback()?.tone ?? 'none')">
      <div aria-live="polite" role="status">
        @if (feedback(); as result) {
          @if (result.live === 'polite') {
            <p class="scan-result-text"><span aria-hidden="true">{{ result.icon }}</span> {{ result.text }}</p>
          }
        }
      </div>
      <div aria-live="assertive" role="alert">
        @if (feedback(); as result) {
          @if (result.live === 'assertive') {
            <p class="scan-result-text"><span aria-hidden="true">{{ result.icon }}</span> {{ result.text }}</p>
          }
        }
      </div>
    </div>

    <div class="toolbar">
      @if (camera() === 'on') {
        <button type="button" class="button" (click)="stopCamera()">Arrêter la caméra</button>
        @if (torchAvailable()) {
          <button type="button" class="button" [attr.aria-pressed]="torchOn()" (click)="toggleTorch()">Lampe</button>
        }
      } @else {
        <button type="button" class="button button-primary" [disabled]="camera() === 'starting'"
                (click)="startCamera()">Activer la caméra</button>
      }
      <button type="button" class="button" (click)="retryNow()">Réessayer maintenant</button>
      <button type="button" class="button" [attr.aria-pressed]="soundEnabled()" (click)="toggleSound()">
        Son : {{ soundEnabled() ? 'activé' : 'coupé' }}
      </button>
      @if (connected()) {
        <app-logout-button />
      }
    </div>

    @if (camera() === 'denied') {
      <p class="banner banner-error">
        Accès à la caméra refusé. Autorisez-le dans les réglages du navigateur, ou utilisez la saisie manuelle.
      </p>
    }
    @if (camera() === 'unavailable') {
      <p class="banner banner-error">Caméra indisponible sur cet appareil</p>
    }

    <form class="form manual-entry" [class.manual-entry-first]="camera() === 'denied' || camera() === 'unavailable'"
          (submit)="submitManual($event)" novalidate>
      <div class="field">
        <label for="manual-token">Code du QR</label>
        <div class="inline-field">
          <input #manualInput id="manual-token" name="qrToken" type="text" autocomplete="off" autocapitalize="none"
                 spellcheck="false" [value]="manualToken()" (input)="manualToken.set(inputValue($event))" />
          <button type="submit" class="button button-primary">Valider</button>
        </div>
      </div>
    </form>

    <video #video class="camera-preview" [class.hidden]="camera() !== 'on'" muted playsinline
           aria-label="Aperçu de la caméra"></video>

    @if (rejected().length > 0) {
      <section class="rejections" aria-labelledby="rejections-title">
        <h2 id="rejections-title">Rejetés</h2>
        <ul class="card-list">
          @for (item of rejected(); track item.localId) {
            <li class="card" [class.card-seen]="item.seen">
              <p><strong>{{ time(item.capturedAt) }}</strong> — {{ runnerOf(item) }} — …{{ suffix(item) }}</p>
              <p>{{ item.lastError?.status ?? '—' }} {{ item.lastError?.code ?? '' }} : {{ rejection(item) }}</p>
              <div class="toolbar">
                @if (!item.seen) {
                  <button type="button" class="button" (click)="markSeen(item)">Marquer comme vu</button>
                }
                <button type="button" class="button" (click)="resend(item)">Renvoyer</button>
              </div>
            </li>
          }
        </ul>
      </section>
    }

    <section aria-labelledby="history-title">
      <h2 id="history-title">Dernières captures</h2>
      @if (history().length === 0) {
        <p>Aucune capture sur cet appareil.</p>
      }
      <ol class="history">
        @for (item of history(); track item.localId) {
          <li>
            {{ time(item.capturedAt) }} — {{ runnerOf(item) }} — …{{ suffix(item) }} — {{ state(item) }}
          </li>
        }
      </ol>
    </section>
  `,
})
export class ScanPage implements OnInit, OnDestroy {
  private readonly queue = inject(ScanQueueService);
  private readonly authState = inject(AuthState);
  private readonly clockOffset = inject(ClockOffsetService);
  private readonly player = inject(FeedbackPlayer);
  private readonly now = secondTicker();
  private readonly video = viewChild.required<ElementRef<HTMLVideoElement>>('video');
  private readonly manualInput = viewChild.required<ElementRef<HTMLInputElement>>('manualInput');
  private scanner: CameraScanner | null = null;
  private resumeCameraOnVisible = false;

  protected readonly snapshot = this.queue.snapshot;
  protected readonly feedback = this.queue.lastFeedback;
  protected readonly queueFailure = this.queue.failure;
  protected readonly soundEnabled = this.player.soundEnabled;
  protected readonly clockStatus = this.clockOffset.status;
  protected readonly camera = signal<CameraState>('off');
  protected readonly torchAvailable = signal(false);
  protected readonly torchOn = signal(false);
  protected readonly manualToken = signal('');
  protected readonly storageWarning = signal(false);

  protected readonly connected = computed(() => this.authState.session() !== null);
  protected readonly account = computed(() => {
    const role = this.authState.role();
    return role === null ? 'Non connecté' : role === 'ADMIN' ? 'admin' : 'scanner';
  });
  protected readonly network = computed(() => networkIndicator(this.queue.online(), this.snapshot(), this.now()));
  protected readonly clockLabel = computed(() => clockStatusLabel(this.clockStatus()));
  protected readonly rejected = computed(() => this.snapshot().items.filter((item) => item.state === 'REJETÉ'));
  protected readonly history = computed(() =>
    [...this.snapshot().items].sort((a, b) => b.capturedAt - a.capturedAt).slice(0, HISTORY_SIZE));

  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'hidden' && this.scanner?.active) {
      this.resumeCameraOnVisible = true;
      this.stopCamera();
    } else if (document.visibilityState === 'visible' && this.resumeCameraOnVisible) {
      this.resumeCameraOnVisible = false;
      void this.startCamera();
    }
  };

  ngOnInit(): void {
    void this.queue.start();
    document.addEventListener('visibilitychange', this.onVisibilityChange);
    void this.requestPersistentStorage();
  }

  ngOnDestroy(): void {
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
    this.stopCamera();
  }

  protected async startCamera(): Promise<void> {
    this.player.unlockAudio();
    this.camera.set('starting');
    this.scanner ??= new CameraScanner(this.video().nativeElement, (content) => void this.capture(content),
      (error) => console.warn('Lecture d\'image impossible', error));
    const result = await this.scanner.start();
    this.camera.set(result === 'started' ? 'on' : result);
    this.torchAvailable.set(result === 'started' && this.scanner.torchAvailable());
    if (result !== 'started') {
      this.manualInput().nativeElement.focus();
    }
  }

  protected stopCamera(): void {
    this.scanner?.stop();
    this.torchOn.set(false);
    if (this.camera() === 'on' || this.camera() === 'starting') {
      this.camera.set('off');
    }
  }

  protected async toggleTorch(): Promise<void> {
    const on = !this.torchOn();
    await this.scanner?.setTorch(on);
    this.torchOn.set(on);
  }

  /** Saisie manuelle ou lecteur clavier : la touche Entrée soumet le formulaire (RG18). */
  protected async submitManual(event: Event): Promise<void> {
    event.preventDefault();
    this.player.unlockAudio();
    const result = await this.capture(this.manualToken());
    if (result === 'captured') {
      this.manualToken.set('');
      this.manualInput().nativeElement.value = '';
    }
  }

  protected retryNow(): void {
    this.queue.retryNow();
  }

  protected toggleSound(): void {
    this.player.toggleSound();
  }

  protected markSeen(item: ScanItem): void {
    void this.queue.markSeen(item.localId);
  }

  protected resend(item: ScanItem): void {
    void this.queue.resend(item.localId);
  }

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected time(instant: number): string {
    return formatTimeOfDay(instant);
  }

  protected suffix(item: ScanItem): string {
    return tokenSuffix(item.qrToken);
  }

  protected runnerOf(item: ScanItem): string {
    const runner = knownRunner(item.qrToken, this.snapshot().items);
    return runner === null ? 'coureur inconnu' : `${runner.bib} — ${runner.name}`;
  }

  protected state(item: ScanItem): string {
    return itemStateLabel(item);
  }

  protected rejection(item: ScanItem): string {
    return rejectionText(item);
  }

  private async capture(content: string): Promise<string> {
    return (await this.queue.capture(content)).kind;
  }

  /** RG47 : stockage persistant demandé à la première ouverture ; avertissement unique en cas de refus. */
  private async requestPersistentStorage(): Promise<void> {
    if (localStorage.getItem(PERSISTENCE_ASKED_KEY) !== null) {
      return;
    }
    localStorage.setItem(PERSISTENCE_ASKED_KEY, 'true');
    const persisted = typeof navigator.storage?.persist === 'function' ? await navigator.storage.persist() : false;
    this.storageWarning.set(!persisted);
  }
}
