import { effect, inject, Injectable, signal } from '@angular/core';
import { API_PATHS } from '../core/api-paths';
import { canEmit, EmitterLock, EmitterRole } from '../core/emitter-role';
import { RawHttpResult, SCAN_TIMEOUT_MS } from '../core/http-classification';
import { captureFeedback, ScanFeedback, serverFeedback } from '../core/scan-feedback';
import { CaptureResult, QueueEvent, QueueSnapshot, ScanQueue } from '../core/scan-queue';
import { ApiClient } from './api-client';
import { AuthState } from './auth-state';
import { browserScheduler } from './browser-scheduler';
import { ClockOffsetService } from './clock-offset.service';
import { FeedbackPlayer } from './feedback-player';
import { indexedDbScanQueueStorage } from './indexed-db';

const EMITTER_LOCK = 'backyard-scan-emitter';
const CHANNEL = 'backyard-scan-queue';
const PURGE_INTERVAL_MS = 10 * 60 * 1000;

const EMPTY_SNAPSHOT: QueueSnapshot = {
  items: [], pendingCount: 0, unseenRejectionCount: 0, suspension: null, sendingEnabled: false,
  waitingForRetry: false, lastTransientFailureAt: null, unreadableCount: 0,
};

/**
 * File de scans de l'appareil branchée sur le navigateur (RG20 à RG27) : stockage IndexedDB, envoi par E6,
 * verrou partagé entre onglets pour un émetteur unique (Web Locks), synchronisation des onglets
 * (BroadcastChannel), essais immédiats sur `online`, retour au premier plan et connexion.
 */
@Injectable({ providedIn: 'root' })
export class ScanQueueService {
  private readonly api = inject(ApiClient);
  private readonly authState = inject(AuthState);
  private readonly clockOffset = inject(ClockOffsetService);
  private readonly player = inject(FeedbackPlayer);

  readonly snapshot = signal<QueueSnapshot>(EMPTY_SNAPSHOT);
  readonly lastFeedback = signal<ScanFeedback | null>(null);
  readonly online = signal(navigator.onLine);
  readonly failure = signal<string | null>(null);

  private readonly queue = new ScanQueue({
    storage: indexedDbScanQueueStorage,
    transport: { send: (body: string) => this.send(body) },
    scheduler: browserScheduler,
    now: () => Date.now(),
    clockOffsetMs: () => this.clockOffset.offsetMs(),
    random: () => Math.random(),
    newLocalId: () => crypto.randomUUID(),
  });
  private readonly channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL);
  private readonly emitterRole = new EmitterRole({
    lock: browserEmitterLock(),
    queue: this.queue,
    canEmit: () => canEmit(this.authState.session() !== null, this.queue.snapshot().suspension),
    onFailure: (error: unknown) => this.reportFailure(error),
  });
  private started: Promise<void> | null = null;
  private opened = false;

  constructor() {
    this.queue.subscribe((event) => this.onQueueEvent(event));
    effect(() => {
      this.queue.setSendingEnabled(this.authState.session() !== null);
      this.updateEmitterRole();
    });
  }

  /** Démarrage unique au lancement de l'application. */
  start(): Promise<void> {
    this.started ??= this.initialize();
    return this.started;
  }

  async capture(content: string): Promise<CaptureResult> {
    await this.start();
    const result = await this.queue.capture(content);
    const willSendNow = this.online() && this.authState.session() !== null && !this.snapshot().waitingForRetry
      && this.snapshot().suspension === null;
    this.show(captureFeedback(result, willSendNow));
    if (result.kind === 'storage-failed') {
      console.error('Échec de l\'écriture de la file locale', result.error);
    }
    this.announceChange();
    return result;
  }

  retryNow(): void {
    this.queue.retryNow();
  }

  async markSeen(localId: string): Promise<void> {
    await this.queue.markSeen(localId);
    this.announceChange();
  }

  async resend(localId: string): Promise<void> {
    await this.queue.resend(localId);
    this.announceChange();
  }

  private async initialize(): Promise<void> {
    try {
      await this.queue.open();
    } catch (error: unknown) {
      this.reportFailure(error);
    }
    this.refreshSnapshot();
    this.opened = true;
    this.updateEmitterRole();
    this.channel?.addEventListener('message', () => {
      this.queue.refresh().catch((error: unknown) => this.reportFailure(error));
    });
    window.addEventListener('online', () => {
      this.online.set(true);
      this.queue.retryNow();
    });
    window.addEventListener('offline', () => this.online.set(false));
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this.queue.retryNow();
      }
    });
    window.addEventListener('beforeunload', (event) => {
      if (this.snapshot().pendingCount > 0) {
        event.preventDefault();
      }
    });
    window.setInterval(() => {
      this.queue.purgeExpired().catch((error: unknown) => this.reportFailure(error));
    }, PURGE_INTERVAL_MS);
  }

  /**
   * Un seul émetteur par appareil, même avec plusieurs onglets (RG21), et seulement parmi les contextes qui
   * peuvent envoyer (« émetteur effectif ») ; les autres affichent la même file. Sans effet avant l'ouverture.
   */
  private updateEmitterRole(): void {
    if (this.opened) {
      this.emitterRole.update();
    }
  }

  private async send(body: string): Promise<RawHttpResult> {
    return (await this.api.exchange('POST', API_PATHS.scan, body, { timeoutMs: SCAN_TIMEOUT_MS })).raw;
  }

  /**
   * Le retour d'un résultat serveur n'est donné que pour les captures de ce contexte (`accepted`, `rejected`),
   * y compris quand un autre onglet émetteur les a envoyées : leur état final arrive alors par la diffusion
   * (BroadcastChannel) puis la relecture de la file (RG50, arbitrage OBS-T2).
   */
  private onQueueEvent(event: QueueEvent): void {
    if (event.type === 'failure') {
      this.reportFailure(event.error);
      return;
    }
    this.refreshSnapshot();
    this.updateEmitterRole();
    const feedback = serverFeedback(event, Date.now());
    if (feedback !== null) {
      this.show(feedback);
    }
    if (event.type !== 'changed') {
      this.announceChange();
    }
  }

  private show(feedback: ScanFeedback): void {
    this.lastFeedback.set(feedback);
    this.player.play(feedback);
  }

  private refreshSnapshot(): void {
    this.snapshot.set(this.queue.snapshot());
  }

  private announceChange(): void {
    this.refreshSnapshot();
    this.channel?.postMessage('changed');
  }

  private reportFailure(error: unknown): void {
    console.error('Erreur de la file locale des scans', error);
    this.failure.set('Erreur du stockage local des scans : gardez cet écran ouvert et notez les dossards.');
  }
}

/**
 * Verrou d'émetteur partagé entre les onglets de l'appareil (Web Locks). Sans Web Locks, le contexte est seul
 * candidat : le verrou est accordé immédiatement.
 */
function browserEmitterLock(): EmitterLock {
  if (!('locks' in navigator)) {
    return { request: (whileHeld) => whileHeld() };
  }
  return { request: (whileHeld, signal) => navigator.locks.request(EMITTER_LOCK, { signal }, whileHeld) };
}
