import { CredentialPersistence, Session } from '../credentials';
import { RawHttpResult } from '../http-classification';
import { ScanItem, StoredRecord } from '../scan-item';
import { ScanQueueStorage, ScanTransport } from '../scan-queue';
import { Scheduler, TimerHandle } from '../scheduler';

/** Doublures de test de la logique pure (RG59) : horloge, minuterie, stockage et transport simulés. */

export class FakeClock {
  constructor(public current: number) {}

  now = (): number => this.current;

  advance(ms: number): void {
    this.current += ms;
  }
}

interface PendingTimer {
  readonly handle: TimerHandle;
  readonly dueAt: number;
  readonly task: () => void;
}

/** Minuterie manuelle adossée à une {@link FakeClock}. */
export class FakeScheduler implements Scheduler {
  private timers: PendingTimer[] = [];
  private nextId = 1;

  constructor(private readonly clock: FakeClock) {}

  schedule(task: () => void, delayMs: number): TimerHandle {
    const handle = { id: this.nextId++ };
    this.timers.push({ handle, dueAt: this.clock.current + delayMs, task });
    return handle;
  }

  cancel(handle: TimerHandle): void {
    this.timers = this.timers.filter((timer) => timer.handle !== handle);
  }

  pendingDelays(): number[] {
    return this.timers.map((timer) => timer.dueAt - this.clock.current);
  }

  /** Avance l'horloge et exécute les minuteries échues, dans l'ordre. */
  advance(ms: number): void {
    const target = this.clock.current + ms;
    for (;;) {
      const due = this.timers.filter((timer) => timer.dueAt <= target).sort((a, b) => a.dueAt - b.dueAt)[0];
      if (due === undefined) {
        break;
      }
      this.timers = this.timers.filter((timer) => timer !== due);
      this.clock.current = due.dueAt;
      due.task();
    }
    this.clock.current = target;
  }
}

/** Stockage de file en mémoire, dans l'ordre d'insertion ; peut simuler un échec d'écriture. */
export class InMemoryQueueStorage implements ScanQueueStorage {
  readonly records = new Map<string, unknown>();
  failWrites = false;

  async loadAll(): Promise<StoredRecord[]> {
    return [...this.records.entries()].map(([key, value]) => ({ key, value: structuredClone(value) }));
  }

  async save(item: ScanItem): Promise<void> {
    if (this.failWrites) {
      throw new Error('QuotaExceededError');
    }
    this.records.set(item.localId, structuredClone(item));
  }

  async remove(key: string): Promise<void> {
    this.records.delete(key);
  }

  stored(localId: string): ScanItem {
    return this.records.get(localId) as ScanItem;
  }
}

/** Réponse JSON du serveur simulé. */
export function jsonResponse(status: number, body: unknown): RawHttpResult {
  return { kind: 'response', status, json: true, body };
}

export function problem(status: number, code: string, detail: string): RawHttpResult {
  return jsonResponse(status, { status, code, detail, title: 'Erreur', type: 'about:blank' });
}

export function scanAccepted(bib: number, runnerName: string, yardNumber: number): RawHttpResult {
  return jsonResponse(200, {
    passageId: yardNumber, runnerId: bib, bib, runnerName, runnerStatus: 'ACTIVE', yardNumber, source: 'SCAN',
    scannedAt: '2026-10-03T10:00:00.000Z',
  });
}

/**
 * Serveur simulé : chaque envoi attend une réponse fournie par le test ({@link respond}), ce qui permet de
 * vérifier qu'une seule requête est en cours à tout instant.
 */
export class ScriptedTransport implements ScanTransport {
  readonly bodies: string[] = [];
  inFlight = 0;
  maxInFlight = 0;
  private waiting: ((result: RawHttpResult) => void)[] = [];

  send(body: string): Promise<RawHttpResult> {
    this.bodies.push(body);
    this.inFlight += 1;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    return new Promise((resolve) => {
      this.waiting.push((result) => {
        this.inFlight -= 1;
        resolve(result);
      });
    });
  }

  get pendingRequests(): number {
    return this.waiting.length;
  }

  /** Répond à la plus ancienne requête en attente, puis laisse la file réagir. */
  async respond(result: RawHttpResult): Promise<void> {
    await settle();
    const next = this.waiting.shift();
    if (next === undefined) {
      throw new Error('Aucune requête en attente de réponse');
    }
    next(result);
    await settle();
  }

  sentTokens(): string[] {
    return this.bodies.map((body) => (JSON.parse(body) as { qrToken: string }).qrToken);
  }
}

/** Laisse s'exécuter toutes les promesses en attente (une macro-tâche vide toute la file des micro-tâches). */
export function settle(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, 0);
  });
}

/** Stockage persistant d'identifiants espionné. */
export class SpyCredentialPersistence implements CredentialPersistence {
  value: unknown = null;
  readonly writes: Session[] = [];
  clears = 0;

  async read(): Promise<unknown> {
    return this.value;
  }

  async write(session: Session): Promise<void> {
    this.writes.push(session);
    this.value = structuredClone(session);
  }

  async clear(): Promise<void> {
    this.clears += 1;
    this.value = null;
  }
}

export const TOKEN_T = '3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f';
export const TOKEN_U = '11111111-2222-4333-8444-555555555555';
export const TOKEN_V = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
