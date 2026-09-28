import { describe, expect, it } from 'vitest';
import { canEmit, EmitterLock, EmitterRole, EmittingQueue } from './emitter-role';
import { RawHttpResult } from './http-classification';
import { ScanQueue, ScanTransport } from './scan-queue';
import {
  FakeClock,
  FakeScheduler,
  InMemoryQueueStorage,
  problem,
  scanAccepted,
  settle,
  TOKEN_T,
  TOKEN_U,
} from './testing/fakes.spec-support';

/**
 * RG21 amendée (« émetteur effectif », 2026-09-27) et CL12 : un seul émetteur par appareil, jamais bloquant,
 * au plus une requête de scan en cours par appareil, aucun envoi en double.
 */

const START = Date.UTC(2026, 9, 3, 10, 0, 0, 0);

interface LockRequest {
  readonly name: string;
  readonly whileHeld: () => Promise<void>;
  readonly resolve: () => void;
  readonly reject: (error: unknown) => void;
}

/** Verrou exclusif partagé par les contextes d'un appareil, fidèle à Web Locks (file d'attente, annulation). */
class FakeDeviceLock {
  holder: string | null = null;
  private waiting: LockRequest[] = [];

  for(name: string): EmitterLock {
    return {
      request: (whileHeld, signal) => new Promise<void>((resolve, reject) => {
        const request: LockRequest = { name, whileHeld, resolve, reject };
        signal.addEventListener('abort', () => {
          if (this.waiting.includes(request)) {
            this.waiting = this.waiting.filter((candidate) => candidate !== request);
            reject(new DOMException('Demande annulée', 'AbortError'));
          }
        });
        this.waiting.push(request);
        this.grantNext();
      }),
    };
  }

  waitingNames(): string[] {
    return this.waiting.map((request) => request.name);
  }

  /** Fermeture brutale du contexte détenteur : le navigateur libère le verrou sans attendre `whileHeld`. */
  holderClosed(): void {
    this.holder = null;
    this.grantNext();
  }

  private grantNext(): void {
    if (this.holder !== null) {
      return;
    }
    const next = this.waiting.shift();
    if (next === undefined) {
      return;
    }
    this.holder = next.name;
    void Promise.resolve().then(() => next.whileHeld()).then(() => {
      if (this.holder === next.name) {
        this.holder = null;
        next.resolve();
        this.grantNext();
      }
    });
  }
}

/** Verrou qui échoue pour une autre raison qu'une annulation. */
const brokenLock: EmitterLock = {
  request: () => Promise.reject(new Error('Web Locks indisponible')),
};

/** File simulée : trace les changements de rôle, envoi en cours terminé à la demande du test. */
class FakeEmittingQueue implements EmittingQueue {
  readonly emitterChanges: boolean[] = [];
  private idle: { resolve: () => void; reject: (error: unknown) => void } | null = null;
  sendingInProgress = false;

  setEmitter(emitter: boolean): void {
    this.emitterChanges.push(emitter);
  }

  get isEmitter(): boolean {
    return this.emitterChanges.at(-1) ?? false;
  }

  whenIdle(): Promise<void> {
    if (!this.sendingInProgress) {
      return Promise.resolve();
    }
    return new Promise<void>((resolve, reject) => {
      this.idle = { resolve, reject };
    });
  }

  finishSending(): void {
    this.sendingInProgress = false;
    this.idle?.resolve();
    this.idle = null;
  }

  failWhileWaitingIdle(error: unknown): void {
    this.idle?.reject(error);
    this.idle = null;
  }
}

function roleContext(lock: EmitterLock, eligible: boolean) {
  const queue = new FakeEmittingQueue();
  const failures: unknown[] = [];
  const context = {
    eligible,
    queue,
    failures,
    role: null as unknown as EmitterRole,
  };
  context.role = new EmitterRole({
    lock,
    queue,
    canEmit: () => context.eligible,
    onFailure: (error) => failures.push(error),
  });
  return context;
}

describe('RG21 - canEmit : identifiants autorisés au scan et file non suspendue', () => {
  it('session présente, file non suspendue : peut émettre', () => {
    expect(canEmit(true, null)).toBe(true);
  });

  it('sans identifiants : ne peut pas émettre', () => {
    expect(canEmit(false, null)).toBe(false);
  });

  it('file suspendue par un 401 (AUTH) ou un 403 (ROLE) : ne peut pas émettre, même avec une session', () => {
    expect(canEmit(true, 'AUTH')).toBe(false);
    expect(canEmit(true, 'ROLE')).toBe(false);
    expect(canEmit(false, 'ROLE')).toBe(false);
  });
});

describe('RG21 - EmitterRole : prise et libération du rôle d\'émetteur', () => {
  it('contexte capable d\'envoyer : il prend le verrou et devient émetteur', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), true);

    a.role.update();
    await settle();

    expect(lock.holder).toBe('A');
    expect(a.queue.emitterChanges).toEqual([true]);
  });

  it('contexte sans identifiants : ne demande pas le verrou et ne devient jamais émetteur', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), false);

    a.role.update();
    await settle();

    expect(lock.holder).toBeNull();
    expect(lock.waitingNames()).toEqual([]);
    expect(a.queue.emitterChanges).toEqual([]);
  });

  it('un seul émetteur par appareil : le second contexte capable attend le verrou', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), true);
    const b = roleContext(lock.for('B'), true);

    a.role.update();
    b.role.update();
    await settle();

    expect(a.queue.isEmitter).toBe(true);
    expect(b.queue.emitterChanges).toEqual([]);
    expect(lock.waitingNames()).toEqual(['B']);
  });

  it('update répété sans changement de capacité : aucune demande de verrou supplémentaire', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), true);

    a.role.update();
    a.role.update();
    await settle();
    a.role.update();
    await settle();

    expect(a.queue.emitterChanges).toEqual([true]);
    expect(lock.waitingNames()).toEqual([]);
  });

  it('émetteur qui perd sa capacité : cesse d\'émettre, et rend le verrou seulement après la fin de l\'envoi en cours',
    async () => {
      const lock = new FakeDeviceLock();
      const a = roleContext(lock.for('A'), true);
      const b = roleContext(lock.for('B'), true);
      a.role.update();
      b.role.update();
      await settle();
      a.queue.sendingInProgress = true;

      a.eligible = false;
      a.role.update();
      await settle();

      expect(a.queue.emitterChanges).toEqual([true, false]);
      expect(lock.holder).toBe('A');
      expect(b.queue.emitterChanges).toEqual([]);

      a.queue.finishSending();
      await settle();

      expect(lock.holder).toBe('B');
      expect(b.queue.emitterChanges).toEqual([true]);
    });

  it('émetteur sans envoi en cours qui perd sa capacité : le verrou passe aussitôt au contexte capable', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), true);
    const b = roleContext(lock.for('B'), true);
    a.role.update();
    b.role.update();
    await settle();

    a.eligible = false;
    a.role.update();
    await settle();

    expect(lock.holder).toBe('B');
    expect(b.queue.isEmitter).toBe(true);
    expect(lock.waitingNames()).toEqual([]);
  });

  it('capacité retrouvée pendant la libération : le contexte redemande le verrou une fois celui-ci rendu', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), true);
    a.role.update();
    await settle();
    a.queue.sendingInProgress = true;
    a.eligible = false;
    a.role.update();
    await settle();

    a.eligible = true;
    a.role.update();
    a.queue.finishSending();
    await settle();

    expect(lock.holder).toBe('A');
    expect(a.queue.emitterChanges).toEqual([true, false, true]);
  });
});

describe('RG21 / CL12 - abandon de la demande de rôle (déconnexion, suspension)', () => {
  it('déconnexion pendant l\'attente du verrou : la demande est annulée, sans erreur signalée', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), true);
    const b = roleContext(lock.for('B'), true);
    a.role.update();
    b.role.update();
    await settle();

    b.eligible = false;
    b.role.update();
    await settle();

    expect(lock.waitingNames()).toEqual([]);
    expect(b.failures).toEqual([]);

    a.eligible = false;
    a.role.update();
    await settle();
    expect(lock.holder).toBeNull();
    expect(b.queue.emitterChanges).toEqual([]);
  });

  it('demande annulée puis capacité retrouvée : nouvelle demande, puis rôle obtenu à la libération', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), true);
    const b = roleContext(lock.for('B'), true);
    a.role.update();
    b.role.update();
    await settle();
    b.eligible = false;
    b.role.update();
    await settle();

    b.eligible = true;
    b.role.update();
    await settle();
    expect(lock.waitingNames()).toEqual(['B']);

    a.eligible = false;
    a.role.update();
    await settle();
    expect(b.queue.emitterChanges).toEqual([true]);
  });

  it('verrou obtenu alors que le contexte ne peut plus envoyer : il ne devient pas émetteur et rend le verrou',
    async () => {
      const lock = new FakeDeviceLock();
      const a = roleContext(lock.for('A'), true);
      const b = roleContext(lock.for('B'), true);
      a.role.update();
      b.role.update();
      await settle();
      b.eligible = false;

      a.eligible = false;
      a.role.update();
      await settle();

      expect(b.queue.emitterChanges).toEqual([]);
      expect(lock.holder).toBeNull();
      expect(b.failures).toEqual([]);
    });

  it('échec du verrou autre qu\'une annulation : signalé, jamais ignoré, sans boucle de nouvelles demandes',
    async () => {
      const a = roleContext(brokenLock, true);

      a.role.update();
      await settle();

      expect(a.failures).toHaveLength(1);
      expect((a.failures[0] as Error).message).toBe('Web Locks indisponible');
      expect(a.queue.emitterChanges).toEqual([]);
    });

  it('échec de l\'attente de fin d\'envoi : signalé, jamais ignoré', async () => {
    const lock = new FakeDeviceLock();
    const a = roleContext(lock.for('A'), true);
    a.role.update();
    await settle();
    a.queue.sendingInProgress = true;
    a.eligible = false;
    a.role.update();

    a.queue.failWhileWaitingIdle(new Error('stockage indisponible'));
    await settle();

    expect(a.failures).toHaveLength(1);
  });
});

// --- Intégration du rôle et de la file réelle : plusieurs contextes d'un même appareil (CL12) ---

interface Sent {
  readonly from: string;
  readonly body: string;
  alive: boolean;
  resolve: (result: RawHttpResult) => void;
}

/** Réseau de l'appareil : journal de toutes les requêtes E6 de tous les contextes (comme le journal de CA44). */
class DeviceNetwork {
  readonly log: Sent[] = [];
  maxInFlight = 0;

  transportFor(name: string): ScanTransport {
    return {
      send: (body: string) => new Promise<RawHttpResult>((resolve) => {
        const sent: Sent = { from: name, body, alive: true, resolve };
        this.log.push(sent);
        this.maxInFlight = Math.max(this.maxInFlight, this.inFlight());
        sent.resolve = (result) => {
          sent.alive = false;
          resolve(result);
        };
      }),
    };
  }

  inFlight(): number {
    return this.log.filter((sent) => sent.alive).length;
  }

  /** Répond à la plus ancienne requête en cours. */
  async respond(result: RawHttpResult): Promise<void> {
    await settle();
    const next = this.log.find((sent) => sent.alive);
    if (next === undefined) {
      throw new Error('Aucune requête en cours');
    }
    next.resolve(result);
    await settle();
    await settle();
  }

  /** Le contexte a été fermé : sa requête en cours disparaît avec lui. */
  contextClosed(name: string): void {
    this.log.filter((sent) => sent.from === name && sent.alive).forEach((sent) => {
      sent.alive = false;
    });
  }

  tokens(): string[] {
    return this.log.map((sent) => (JSON.parse(sent.body) as { qrToken: string }).qrToken);
  }
}

function device() {
  const clock = new FakeClock(START);
  const scheduler = new FakeScheduler(clock);
  const storage = new InMemoryQueueStorage();
  const lock = new FakeDeviceLock();
  const network = new DeviceNetwork();
  const contexts: ReturnType<typeof newContext>[] = [];
  let nextId = 0;

  function newContext(name: string) {
    const failures: unknown[] = [];
    const queue = new ScanQueue({
      storage,
      transport: network.transportFor(name),
      scheduler,
      now: clock.now,
      clockOffsetMs: () => 0,
      random: () => 0,
      newLocalId: () => `local-${++nextId}`,
    });
    const context = { name, queue, failures, hasSession: false, role: null as unknown as EmitterRole };
    context.role = new EmitterRole({
      lock: lock.for(name),
      queue,
      canEmit: () => canEmit(context.hasSession, queue.snapshot().suspension),
      onFailure: (error) => failures.push(error),
    });
    queue.subscribe((event) => {
      if (event.type === 'failure') {
        failures.push(event.error);
      }
      context.role.update();
    });
    return context;
  }

  return {
    clock, storage, lock, network,
    async open(name: string, connected: boolean) {
      const context = newContext(name);
      contexts.push(context);
      await context.queue.open();
      context.hasSession = connected;
      context.queue.setSendingEnabled(connected);
      context.role.update();
      await settle();
      return context;
    },
    async logout(context: ReturnType<typeof newContext>) {
      context.hasSession = false;
      context.queue.setSendingEnabled(false);
      await settle();
    },
    /** Diffusion d'un changement de la file aux autres contextes (BroadcastChannel en production). */
    async broadcastFrom(context: ReturnType<typeof newContext>) {
      for (const other of contexts.filter((candidate) => candidate !== context)) {
        await other.queue.refresh();
      }
      await settle();
    },
  };
}

describe('RG21 émetteur effectif / CL12 - plusieurs contextes sur le même appareil', () => {
  it('tableau de bord anonyme ouvert en premier : le contexte /scan connecté devient émetteur et envoie sa capture',
    async () => {
      const dev = device();
      const dashboard = await dev.open('A', false);
      const scan = await dev.open('B', true);

      await scan.queue.capture(TOKEN_T);
      await settle();

      expect(dev.lock.holder).toBe('B');
      expect(dev.network.log.map((sent) => sent.from)).toEqual(['B']);
      await dev.network.respond(scanAccepted(1, 'Alice', 1));
      expect(scan.queue.snapshot().pendingCount).toBe(0);
      expect(dashboard.failures).toEqual([]);
      expect(scan.failures).toEqual([]);
    });

  it('émetteur déconnecté pendant un envoi : jamais deux requêtes simultanées, aucun envoi en double, reprise par '
    + 'l\'autre contexte connecté', async () => {
    const dev = device();
    const a = await dev.open('A', true);
    const b = await dev.open('B', true);
    expect(dev.lock.holder).toBe('A');

    await a.queue.capture(TOKEN_T);
    await settle();
    await b.queue.capture(TOKEN_U);
    await dev.broadcastFrom(b);
    expect(dev.network.inFlight()).toBe(1);

    await dev.logout(a);
    expect(dev.lock.holder).toBe('A');
    expect(dev.network.log).toHaveLength(1);

    await dev.network.respond(scanAccepted(1, 'Alice', 1));
    await settle();

    expect(dev.lock.holder).toBe('B');
    expect(dev.network.log.map((sent) => sent.from)).toEqual(['A', 'B']);
    await dev.network.respond(scanAccepted(2, 'Bob', 1));

    expect(dev.network.tokens()).toEqual([TOKEN_T, TOKEN_U]);
    expect(dev.network.maxInFlight).toBe(1);
    const storedStates = [...dev.storage.records.values()].map((item) => (item as { state: string }).state);
    expect(storedStates).toEqual(['ACCEPTÉ', 'ACCEPTÉ']);
  });

  it('émetteur suspendu par un 401 : il rend le rôle, le contexte connecté renvoie la tête avec le même corps',
    async () => {
      const dev = device();
      const a = await dev.open('A', true);
      const b = await dev.open('B', true);

      await a.queue.capture(TOKEN_T);
      await settle();
      const firstBody = dev.network.log[0].body;
      await dev.network.respond(problem(401, 'UNAUTHORIZED', 'Identifiants refusés'));
      await settle();

      expect(a.queue.snapshot().suspension).toBe('AUTH');
      expect(dev.lock.holder).toBe('B');
      expect(dev.lock.waitingNames()).toEqual([]);
      expect(dev.network.log.map((sent) => sent.from)).toEqual(['A', 'B']);
      expect(dev.network.log[1].body).toBe(firstBody);

      await dev.network.respond(scanAccepted(1, 'Alice', 1));
      expect(dev.storage.stored(a.queue.snapshot().items[0].localId).state).toBe('ACCEPTÉ');
      expect(b.queue.snapshot().pendingCount).toBe(0);
      expect(dev.network.maxInFlight).toBe(1);
    });

  it('RG20 - émetteur fermé pendant un envoi : le nouvel émetteur reprend l\'élément EN_COURS avec le même corps',
    async () => {
      const dev = device();
      const a = await dev.open('A', true);
      const b = await dev.open('B', true);
      await a.queue.capture(TOKEN_T);
      await settle();
      const item = a.queue.snapshot().items[0];
      expect(dev.storage.stored(item.localId).state).toBe('EN_COURS');

      dev.network.contextClosed('A');
      dev.lock.holderClosed();
      await settle();
      await settle();

      expect(dev.lock.holder).toBe('B');
      expect(dev.network.log.map((sent) => sent.from)).toEqual(['A', 'B']);
      expect(dev.network.log[1].body).toBe(dev.network.log[0].body);
      expect(JSON.parse(dev.network.log[1].body)).toEqual({ qrToken: TOKEN_T, scannedAt: item.scannedAt });

      await dev.network.respond(scanAccepted(1, 'Alice', 1));
      expect(b.queue.snapshot().items.map((candidate) => candidate.state)).toEqual(['ACCEPTÉ']);
      expect(dev.network.log).toHaveLength(2);
    });

  it('CL12 - plusieurs captures dans plusieurs contextes connectés : une requête par capture, jamais deux en cours',
    async () => {
      const dev = device();
      const a = await dev.open('A', true);
      const b = await dev.open('B', true);
      const c = await dev.open('C', true);

      await b.queue.capture(TOKEN_T);
      await dev.broadcastFrom(b);
      dev.clock.advance(1000);
      await c.queue.capture(TOKEN_U);
      await dev.broadcastFrom(c);
      await dev.network.respond(scanAccepted(1, 'Alice', 1));
      await dev.network.respond(scanAccepted(2, 'Bob', 1));
      await dev.broadcastFrom(a);

      expect(dev.network.tokens()).toEqual([TOKEN_T, TOKEN_U]);
      expect(new Set(dev.network.log.map((sent) => sent.from))).toEqual(new Set(['A']));
      expect(dev.network.maxInFlight).toBe(1);
      expect(b.queue.snapshot().pendingCount).toBe(0);
      expect(c.queue.snapshot().pendingCount).toBe(0);
    });
});
