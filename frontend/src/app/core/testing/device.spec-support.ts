import { EmitterLock } from '../emitter-role';
import { RawHttpResult } from '../http-classification';
import { captureFeedback, FeedbackSound, ScanFeedback, serverFeedback } from '../scan-feedback';
import { CaptureResult, QueueEvent, ScanQueue } from '../scan-queue';
import { FakeClock, FakeScheduler, InMemoryQueueStorage, ScriptedTransport, settle } from './fakes.spec-support';

/**
 * Doublures d'un appareil avec plusieurs contextes de la PWA (onglets) pour CA45 et CA46 (spec inc. 4) :
 * stockage partagé, réseau de l'appareil qui compte les requêtes E6 en cours tous contextes confondus,
 * verrou d'émetteur partagé et lecteur de retour instrumenté par contexte.
 */

/** Réseau de l'appareil : chaque contexte a son transport, le nombre de requêtes E6 en cours est commun. */
export class DeviceNetwork {
  inFlight = 0;
  maxInFlight = 0;
  readonly allBodies: string[] = [];

  transport(): ScriptedTransport {
    return new DeviceTransport(this);
  }

  onSend(body: string): void {
    this.allBodies.push(body);
    this.inFlight += 1;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
  }

  onDone(): void {
    this.inFlight -= 1;
  }
}

class DeviceTransport extends ScriptedTransport {
  constructor(private readonly network: DeviceNetwork) {
    super();
  }

  override send(body: string): Promise<RawHttpResult> {
    this.network.onSend(body);
    return super.send(body).then((result) => {
      this.network.onDone();
      return result;
    });
  }
}

/**
 * Verrou exclusif partagé (Web Locks simulé) : accordé au premier demandeur, puis aux suivants dans l'ordre,
 * à la résolution de la promesse de `whileHeld`. Une demande en attente est annulable par son signal.
 */
export class FakeSharedLock implements EmitterLock {
  private held = false;
  private waiting: { run: () => void; signal: AbortSignal; reject: (error: unknown) => void }[] = [];

  request(whileHeld: () => Promise<void>, signal: AbortSignal): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const run = () => {
        this.held = true;
        whileHeld().then(() => {
          this.held = false;
          resolve();
          this.grantNext();
        }, reject);
      };
      if (!this.held) {
        run();
        return;
      }
      const entry = { run, signal, reject };
      this.waiting.push(entry);
      signal.addEventListener('abort', () => {
        this.waiting = this.waiting.filter((candidate) => candidate !== entry);
        reject(new DOMException('Demande annulée', 'AbortError'));
      });
    });
  }

  /** Fermeture brutale du contexte détenteur : le navigateur libère le verrou sans attendre. */
  forceRelease(): void {
    this.held = false;
    this.grantNext();
  }

  private grantNext(): void {
    const next = this.waiting.shift();
    next?.run();
  }
}

/**
 * Traduction du tableau de RG50 (spec) en nombre de bips : capture = 1 bip aigu, accepté = 2 bips aigus,
 * rejet ou QR non reconnu = 3 bips graves.
 */
export const RG50_BEEPS: Readonly<Record<FeedbackSound, { readonly count: number; readonly pitch: 'aigu' | 'grave' }>> = {
  capture: { count: 1, pitch: 'aigu' },
  success: { count: 2, pitch: 'aigu' },
  error: { count: 3, pitch: 'grave' },
};

/** Durées des impulsions de vibration d'un motif `navigator.vibrate` (indices pairs ; les impairs sont des pauses). */
export function vibrationPulses(pattern: readonly number[] | null): number[] {
  return (pattern ?? []).filter((_, index) => index % 2 === 0);
}

/**
 * Contexte de la PWA (onglet) : sa propre instance de file sur le stockage partagé, son transport et son lecteur
 * de retour instrumenté. Le câblage reproduit celui d'un onglet : chaque événement de la file passe par
 * `serverFeedback`, chaque capture par `captureFeedback`, et tout changement autre qu'un simple `changed` est
 * diffusé aux autres contextes ouverts, qui relisent la file (`refresh`, BroadcastChannel en production).
 */
export class PwaContext {
  /** Retours donnés par ce contexte (bandeau, son, vibration), dans l'ordre. */
  readonly shown: ScanFeedback[] = [];
  readonly failures: unknown[] = [];
  opened = true;
  private readonly unsubscribe: () => void;

  constructor(readonly name: string, readonly queue: ScanQueue, readonly transport: ScriptedTransport,
              private readonly device: Device) {
    this.unsubscribe = queue.subscribe((event) => this.onEvent(event));
  }

  /** Bandeau affiché : dernier retour donné. */
  get banner(): ScanFeedback | null {
    return this.shown.at(-1) ?? null;
  }

  /** Bips joués par ce contexte, selon le tableau de RG50. */
  beeps(): { count: number; pitch: 'aigu' | 'grave' }[] {
    return this.shown.filter((feedback) => feedback.sound !== null)
      .map((feedback) => RG50_BEEPS[feedback.sound as FeedbackSound]);
  }

  /** Motifs de vibration joués par ce contexte. */
  vibrations(): (readonly number[])[] {
    return this.shown.flatMap((feedback) => (feedback.vibration === null ? [] : [feedback.vibration]));
  }

  async capture(content: string): Promise<CaptureResult> {
    const result = await this.queue.capture(content);
    this.shown.push(captureFeedback(result, true));
    this.device.broadcastFrom(this);
    await settle();
    return result;
  }

  /** Fermeture de l'onglet : plus aucun événement reçu ni affiché. */
  close(): void {
    this.opened = false;
    this.unsubscribe();
  }

  private onEvent(event: QueueEvent): void {
    if (event.type === 'failure') {
      this.failures.push(event.error);
      return;
    }
    const feedback = serverFeedback(event, this.device.clock.now());
    if (feedback !== null) {
      this.shown.push(feedback);
    }
    if (event.type !== 'changed') {
      this.device.broadcastFrom(this);
    }
  }
}

export interface DeviceOptions {
  readonly start: number;
  /** Tirage du facteur aléatoire du backoff (RG22). */
  readonly random?: () => number;
}

/** Appareil : horloge, minuterie, stockage et réseau partagés par ses contextes. */
export class Device {
  readonly clock: FakeClock;
  readonly scheduler: FakeScheduler;
  readonly storage = new InMemoryQueueStorage();
  readonly network = new DeviceNetwork();
  readonly contexts: PwaContext[] = [];
  private nextId = 0;

  constructor(private readonly options: DeviceOptions) {
    this.clock = new FakeClock(options.start);
    this.scheduler = new FakeScheduler(this.clock);
  }

  /** Ouvre un contexte connecté (envoi autorisé), non émetteur. */
  async openContext(name: string): Promise<PwaContext> {
    const transport = this.network.transport();
    const queue = new ScanQueue({
      storage: this.storage,
      transport,
      scheduler: this.scheduler,
      now: this.clock.now,
      clockOffsetMs: () => 0,
      random: this.options.random ?? (() => 0),
      newLocalId: () => `local-${++this.nextId}`,
    });
    const context = new PwaContext(name, queue, transport, this);
    await queue.open();
    queue.setSendingEnabled(true);
    this.contexts.push(context);
    await settle();
    return context;
  }

  /** Diffusion d'un changement de la file aux autres contextes ouverts, qui la relisent. */
  broadcastFrom(sender: PwaContext): void {
    for (const context of this.contexts) {
      if (context !== sender && context.opened) {
        context.queue.refresh().catch((error: unknown) => context.failures.push(error));
      }
    }
  }
}
