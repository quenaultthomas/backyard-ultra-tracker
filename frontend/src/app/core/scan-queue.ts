import { ScanResponse } from './api.types';
import { backoffDelayMs } from './backoff';
import { CaptureDebouncer } from './capture-debouncer';
import { toIsoMillis } from './formats';
import { classify, ClassifiedResult, DATA_INTEGRITY_CODE, RawHttpResult, ResponseClass } from './http-classification';
import { normalizeQrToken } from './qr-token';
import { isRetentionExpired } from './retention';
import {
  isPending,
  isUnseenRejection,
  newScanItem,
  readStoredItems,
  ScanError,
  ScanItem,
  scanRequestBody,
  sortByQueuePosition,
  StoredRecord,
} from './scan-item';
import { Scheduler, TimerHandle } from './scheduler';

/**
 * File locale des scans et filet réseau (RG19 à RG27) :
 * - chaque capture est écrite dans le stockage persistant avant tout envoi ;
 * - les éléments EN_ATTENTE partent un par un, dans l'ordre de la file, depuis un seul émetteur par appareil ;
 * - un échec TRANSITOIRE bloque la tête de file et programme un nouvel essai avec backoff, sans abandon ;
 * - 401 et 403 suspendent la file sans rien perdre ; un rejet définitif passe à l'élément suivant ;
 * - chaque envoi d'un élément transmet exactement le même corps (idempotence, RG23) ;
 * - le résultat serveur d'une capture est signalé (`accepted`, `rejected`) par le seul contexte qui l'a faite, qu'il
 *   l'ait envoyée lui-même ou qu'il en observe l'état final dans le stockage partagé ; le contexte qui envoie la
 *   capture d'un autre contexte n'émet qu'un `foreign-result` (RG50, arbitrage OBS-T2).
 * Aucune dépendance au navigateur : stockage, transport, horloges et minuterie sont injectés.
 */

export interface ScanQueueStorage {
  loadAll(): Promise<StoredRecord[]>;
  save(item: ScanItem): Promise<void>;
  remove(key: string): Promise<void>;
}

/** Envoi d'un corps à E6 ; ne rejette jamais la promesse, les échecs sont dans le résultat. */
export interface ScanTransport {
  send(body: string): Promise<RawHttpResult>;
}

export interface ScanQueueDependencies {
  readonly storage: ScanQueueStorage;
  readonly transport: ScanTransport;
  readonly scheduler: Scheduler;
  /** Instant de l'appareil (ms epoch). */
  readonly now: () => number;
  /** Décalage d'horloge courant avec le serveur (RG4), 0 sans mesure. */
  readonly clockOffsetMs: () => number;
  /** Tirage uniforme dans [0 ; 1] pour le backoff. */
  readonly random: () => number;
  readonly newLocalId: () => string;
}

export type Suspension = 'AUTH' | 'ROLE';

export type CaptureResult =
  | { readonly kind: 'invalid' }
  | { readonly kind: 'duplicate'; readonly qrToken: string }
  | { readonly kind: 'captured'; readonly item: ScanItem }
  | { readonly kind: 'storage-failed'; readonly item: ScanItem; readonly error: unknown };

export type QueueEvent =
  /** Acceptation d'une capture faite par ce contexte (envoyée ici ou observée dans le stockage partagé). */
  | { readonly type: 'accepted'; readonly item: ScanItem; readonly persisted: boolean }
  /** Rejet d'une capture faite par ce contexte (envoyée ici ou observée dans le stockage partagé). */
  | { readonly type: 'rejected'; readonly item: ScanItem; readonly persisted: boolean }
  /** État final, obtenu par ce contexte émetteur, d'une capture d'un autre contexte (ouvert ou fermé). */
  | { readonly type: 'foreign-result'; readonly item: ScanItem }
  | { readonly type: 'retrying'; readonly item: ScanItem; readonly delayMs: number }
  | { readonly type: 'suspended'; readonly item: ScanItem; readonly suspension: Suspension }
  | { readonly type: 'changed' }
  /** Erreur inattendue d'une opération lancée en arrière-plan (stockage indisponible…), jamais ignorée. */
  | { readonly type: 'failure'; readonly error: unknown };

export interface QueueSnapshot {
  /** Éléments dans l'ordre de la file. */
  readonly items: readonly ScanItem[];
  readonly pendingCount: number;
  readonly unseenRejectionCount: number;
  readonly suspension: Suspension | null;
  readonly sendingEnabled: boolean;
  readonly waitingForRetry: boolean;
  readonly lastTransientFailureAt: number | null;
  readonly unreadableCount: number;
}

export class ScanQueue {
  private items: ScanItem[] = [];
  private unreadable: StoredRecord[] = [];
  private readonly debouncer = new CaptureDebouncer();
  private readonly listeners = new Set<(event: QueueEvent) => void>();
  /**
   * Captures (ou renvois) faits par ce contexte dont le résultat n'a pas encore été signalé : lui seul le signale,
   * une seule fois (RG50). Propre au contexte, cet ensemble disparaît avec lui.
   */
  private readonly awaitingResult = new Set<string>();
  private emitter = false;
  private sendingEnabled = false;
  private sending = false;
  private inFlightId: string | null = null;
  private suspension: Suspension | null = null;
  private retryTimer: TimerHandle | null = null;
  private headId: string | null = null;
  private consecutiveFailures = 0;
  private lastTransientFailureAt: number | null = null;
  private idleWaiters: (() => void)[] = [];

  constructor(private readonly deps: ScanQueueDependencies) {}

  /**
   * Ouverture au démarrage (RG20) : lecture et migration du stockage, reprise des éléments restés EN_COURS
   * (application tuée pendant un envoi) en EN_ATTENTE, purge de rétention (RG27).
   */
  async open(): Promise<void> {
    await this.load();
    await this.resumeInterruptedSends();
    await this.purgeExpired();
  }

  /**
   * Relecture du stockage (modifications d'un autre onglet), sans toucher à l'élément en cours d'envoi. Un ajout
   * venu d'un autre contexte est traité comme une nouvelle capture : l'émetteur relance l'envoi, sans
   * court-circuiter une attente de backoff (RG21 « émetteur effectif », RG22).
   */
  async refresh(): Promise<void> {
    const inFlight = this.items.find((item) => item.localId === this.inFlightId);
    await this.load();
    if (inFlight !== undefined) {
      this.replaceInMemory(inFlight);
    }
    this.notify({ type: 'changed' });
    this.processInBackground();
  }

  /**
   * Ce contexte devient (ou cesse d'être) l'unique émetteur de l'appareil (RG21). En prenant le rôle, il relit
   * le stockage et reprend les éléments laissés EN_COURS par un contexte fermé pendant un envoi : un contexte
   * vivant ne rend le rôle qu'une fois son envoi terminé (`whenIdle`), l'idempotence (RG23) couvre le renvoi.
   */
  setEmitter(emitter: boolean): void {
    this.emitter = emitter;
    if (emitter) {
      this.inBackground(this.takeOverSending());
    }
  }

  /**
   * Présence d'identifiants : sans eux, la capture continue mais l'envoi est suspendu (RG14). Une connexion
   * réussie lève la suspension due à un 401 ou un 403 et relance l'envoi immédiatement (RG10, RG22).
   */
  setSendingEnabled(enabled: boolean): void {
    this.sendingEnabled = enabled;
    if (enabled) {
      this.suspension = null;
      this.retryNow();
      return;
    }
    this.notify({ type: 'changed' });
  }

  /** Essai immédiat qui annule l'attente de backoff (RG22) : online, premier plan, connexion, bouton. */
  retryNow(): void {
    this.cancelRetryTimer();
    this.processInBackground();
  }

  /** Capture d'un contenu lu (caméra ou saisie) : validation, anti-rebond, horodatage, écriture (RG16 à RG20). */
  async capture(content: string): Promise<CaptureResult> {
    const qrToken = normalizeQrToken(content);
    if (qrToken === null) {
      return { kind: 'invalid' };
    }
    const now = this.deps.now();
    if (this.debouncer.isRecentCapture(qrToken, now)) {
      return { kind: 'duplicate', qrToken };
    }
    this.debouncer.recordCapture(qrToken, now);
    const scannedAt = toIsoMillis(now + this.deps.clockOffsetMs());
    const item = newScanItem(this.deps.newLocalId(), qrToken, scannedAt, now, this.nextQueuePosition());
    try {
      await this.deps.storage.save(item);
    } catch (error: unknown) {
      this.inBackground(this.sendUnstored(item));
      return { kind: 'storage-failed', item, error };
    }
    this.items.push(item);
    this.awaitingResult.add(item.localId);
    this.notify({ type: 'changed' });
    this.processInBackground();
    return { kind: 'captured', item };
  }

  /** « Marquer comme vu » : le rejet sort du compteur, sans être supprimé (RG25). */
  async markSeen(localId: string): Promise<void> {
    const item = this.find(localId);
    if (item?.state === 'REJETÉ' && !item.seen) {
      await this.persist({ ...item, seen: true, seenAt: this.deps.now() });
      this.notify({ type: 'changed' });
    }
  }

  /** « Renvoyer » : l'élément rejeté repasse EN_ATTENTE en fin de file, avec le même corps (RG25, RG23). */
  async resend(localId: string): Promise<void> {
    const item = this.find(localId);
    if (item?.state !== 'REJETÉ') {
      return;
    }
    await this.persist({ ...item, state: 'EN_ATTENTE', queuePosition: this.nextQueuePosition(), nextAttemptAt: null });
    this.awaitingResult.add(localId);
    this.notify({ type: 'changed' });
    this.processInBackground();
  }

  /** Suppression des éléments dont la durée de rétention est écoulée (RG27). */
  async purgeExpired(): Promise<void> {
    const now = this.deps.now();
    const expired = this.items.filter((item) => isRetentionExpired(item, now));
    for (const item of expired) {
      await this.deps.storage.remove(item.localId);
    }
    if (expired.length > 0) {
      this.items = this.items.filter((item) => !expired.includes(item));
      expired.forEach((item) => this.awaitingResult.delete(item.localId));
      this.notify({ type: 'changed' });
    }
  }

  /** Résolue quand plus aucun envoi n'est en cours : permet de rendre le rôle d'émetteur sans envoi en double. */
  whenIdle(): Promise<void> {
    if (!this.sending) {
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => this.idleWaiters.push(resolve));
  }

  subscribe(listener: (event: QueueEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  snapshot(): QueueSnapshot {
    const items = sortByQueuePosition(this.items);
    return {
      items,
      pendingCount: items.filter(isPending).length,
      unseenRejectionCount: items.filter(isUnseenRejection).length,
      suspension: this.suspension,
      sendingEnabled: this.sendingEnabled,
      waitingForRetry: this.retryTimer !== null,
      lastTransientFailureAt: this.lastTransientFailureAt,
      unreadableCount: this.unreadable.length,
    };
  }

  /**
   * Envoi FIFO : au plus une requête à la fois, uniquement depuis l'émetteur, avec identifiants, hors
   * suspension et hors attente de backoff (RG21, RG22).
   */
  async process(): Promise<void> {
    if (!this.canSend()) {
      return;
    }
    this.sending = true;
    try {
      let head = this.head();
      while (head !== undefined && this.canContinue()) {
        const next = await this.sendHead(head);
        head = next ? this.head() : undefined;
      }
    } finally {
      this.sending = false;
      this.inFlightId = null;
      this.releaseIdleWaiters();
    }
  }

  private releaseIdleWaiters(): void {
    const waiters = this.idleWaiters;
    this.idleWaiters = [];
    waiters.forEach((resolve) => resolve());
  }

  private canSend(): boolean {
    return this.emitter && this.sendingEnabled && !this.sending && this.suspension === null && this.retryTimer === null;
  }

  private canContinue(): boolean {
    return this.emitter && this.sendingEnabled && this.suspension === null;
  }

  private head(): ScanItem | undefined {
    return sortByQueuePosition(this.items).find((item) => item.state === 'EN_ATTENTE');
  }

  /** Envoie l'élément de tête ; vrai si la file peut passer à l'élément suivant. */
  private async sendHead(head: ScanItem): Promise<boolean> {
    if (head.localId !== this.headId) {
      this.headId = head.localId;
      this.consecutiveFailures = 0;
    }
    this.inFlightId = head.localId;
    const sending = await this.persist({ ...head, state: 'EN_COURS', attempts: head.attempts + 1, nextAttemptAt: null });
    this.notify({ type: 'changed' });
    const result = classify<ScanResponse>(await this.deps.transport.send(scanRequestBody(sending)));
    return this.applyResult(sending, result);
  }

  private async applyResult(item: ScanItem, result: ClassifiedResult<ScanResponse>): Promise<boolean> {
    switch (result.responseClass) {
      case 'SUCCESS':
        await this.accept(item, result);
        return true;
      case 'DEFINITIVE':
        await this.reject(item, result);
        return true;
      case 'AUTH':
      case 'ROLE':
        await this.suspend(item, result, result.responseClass);
        return false;
      case 'TRANSIENT':
        await this.scheduleRetry(item, result);
        return false;
    }
  }

  private async accept(item: ScanItem, result: ClassifiedResult<ScanResponse>): Promise<void> {
    this.lastTransientFailureAt = null;
    const accepted = await this.persist({
      ...item, state: 'ACCEPTÉ', response: result.body, acceptedAt: this.deps.now(), lastError: null,
    });
    this.notify(this.takeAwaitedResult(accepted)
      ? { type: 'accepted', item: accepted, persisted: true }
      : { type: 'foreign-result', item: accepted });
  }

  private async reject(item: ScanItem, result: ClassifiedResult<ScanResponse>): Promise<void> {
    this.lastTransientFailureAt = null;
    const rejected = await this.persist({
      ...item, state: 'REJETÉ', lastError: scanError(result), seen: false, seenAt: null,
    });
    this.notify(this.takeAwaitedResult(rejected)
      ? { type: 'rejected', item: rejected, persisted: true }
      : { type: 'foreign-result', item: rejected });
  }

  private async suspend(item: ScanItem, result: ClassifiedResult<ScanResponse>, responseClass: ResponseClass):
    Promise<void> {
    this.suspension = responseClass === 'AUTH' ? 'AUTH' : 'ROLE';
    const waiting = await this.persist({ ...item, state: 'EN_ATTENTE', lastError: scanError(result) });
    this.notify({ type: 'suspended', item: waiting, suspension: this.suspension });
  }

  private async scheduleRetry(item: ScanItem, result: ClassifiedResult<ScanResponse>): Promise<void> {
    this.consecutiveFailures += 1;
    this.lastTransientFailureAt = this.deps.now();
    const delayMs = backoffDelayMs(this.consecutiveFailures, this.deps.random);
    const waiting = await this.persist({
      ...item, state: 'EN_ATTENTE', lastError: scanError(result), nextAttemptAt: this.deps.now() + delayMs,
    });
    this.retryTimer = this.deps.scheduler.schedule(() => {
      this.retryTimer = null;
      this.processInBackground();
    }, delayMs);
    this.notify({ type: 'retrying', item: waiting, delayMs });
  }

  /** Échec d'écriture locale (RG20) : une tentative d'envoi directe unique, sans nouvel essai. */
  private async sendUnstored(item: ScanItem): Promise<void> {
    const result = classify<ScanResponse>(await this.deps.transport.send(scanRequestBody(item)));
    if (result.responseClass === 'SUCCESS') {
      this.notify({
        type: 'accepted',
        item: { ...item, state: 'ACCEPTÉ', response: result.body, acceptedAt: this.deps.now(), attempts: 1 },
        persisted: false,
      });
      return;
    }
    this.notify({
      type: 'rejected',
      item: { ...item, state: 'REJETÉ', lastError: scanError(result), attempts: 1 },
      persisted: false,
    });
  }

  private async takeOverSending(): Promise<void> {
    await this.load();
    await this.resumeInterruptedSends();
    this.notify({ type: 'changed' });
    await this.process();
  }

  /** Éléments restés EN_COURS sans envoi vivant (application tuée ou contexte fermé) : de nouveau EN_ATTENTE. */
  private async resumeInterruptedSends(): Promise<void> {
    for (const item of this.items.filter((candidate) => candidate.state === 'EN_COURS')) {
      await this.persist({ ...item, state: 'EN_ATTENTE' });
    }
  }

  /**
   * Relecture du stockage partagé. Une capture de ce contexte qu'un autre contexte émetteur a fait passer à un état
   * final est signalée ici, au moment où ce contexte en prend connaissance (RG50, arbitrage OBS-T2).
   */
  private async load(): Promise<void> {
    const reading = readStoredItems(await this.deps.storage.loadAll());
    this.items = reading.items;
    this.unreadable = reading.unreadable;
    for (const migrated of reading.migrated) {
      await this.deps.storage.save(migrated);
    }
    this.items
      .filter((item) => !isPending(item) && this.takeAwaitedResult(item))
      .forEach((item) => this.notify({
        type: item.state === 'ACCEPTÉ' ? 'accepted' : 'rejected', item, persisted: true,
      }));
  }

  /** Vrai, une seule fois, si ce contexte attend le résultat de cet élément (capture ou renvoi faits ici). */
  private takeAwaitedResult(item: ScanItem): boolean {
    return this.awaitingResult.delete(item.localId);
  }

  private async persist(item: ScanItem): Promise<ScanItem> {
    await this.deps.storage.save(item);
    this.replaceInMemory(item);
    return item;
  }

  private replaceInMemory(item: ScanItem): void {
    const index = this.items.findIndex((candidate) => candidate.localId === item.localId);
    if (index >= 0) {
      this.items[index] = item;
    } else {
      this.items.push(item);
    }
  }

  private find(localId: string): ScanItem | undefined {
    return this.items.find((item) => item.localId === localId);
  }

  private nextQueuePosition(): number {
    return Math.max(0, ...this.items.map((item) => item.queuePosition)) + 1;
  }

  private cancelRetryTimer(): void {
    if (this.retryTimer !== null) {
      this.deps.scheduler.cancel(this.retryTimer);
      this.retryTimer = null;
    }
  }

  /** Traitement de la file lancé sans attendre la fin des envois (réponse du serveur). */
  private processInBackground(): void {
    this.inBackground(this.process());
  }

  /** Opération non attendue par l'appelant : son échec est signalé par un événement `failure`. */
  private inBackground(operation: Promise<void>): void {
    operation.catch((error: unknown) => this.notify({ type: 'failure', error }));
  }

  private notify(event: QueueEvent): void {
    this.listeners.forEach((listener) => listener(event));
  }
}

/** Erreur conservée sur l'élément : statut, code et détail du serveur, ou cause technique. */
export function scanError(result: ClassifiedResult<unknown>): ScanError {
  if (result.failure === 'network') {
    return { status: null, code: null, detail: 'En attente de réseau' };
  }
  if (result.failure !== null) {
    return { status: result.status, code: null, detail: 'Serveur indisponible' };
  }
  if (result.problem?.code === DATA_INTEGRITY_CODE) {
    return { status: result.status, code: DATA_INTEGRITY_CODE, detail: 'Conflit temporaire, nouvel essai' };
  }
  return {
    status: result.status,
    code: result.problem?.code ?? null,
    detail: result.problem?.detail ?? (result.status !== null && result.status >= 500 ? 'Serveur indisponible' : `Erreur ${result.status}`),
  };
}
