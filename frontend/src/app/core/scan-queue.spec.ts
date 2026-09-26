import { describe, expect, it } from 'vitest';
import { RawHttpResult } from './http-classification';
import { QueueEvent, ScanQueue } from './scan-queue';
import { ScanItem, scanRequestBody } from './scan-item';
import {
  FakeClock,
  FakeScheduler,
  InMemoryQueueStorage,
  jsonResponse,
  problem,
  scanAccepted,
  ScriptedTransport,
  settle,
  TOKEN_T,
  TOKEN_U,
  TOKEN_V,
} from './testing/fakes.spec-support';

const START = Date.UTC(2026, 9, 3, 10, 0, 0, 0);
const NETWORK_ERROR: RawHttpResult = { kind: 'network-error' };
const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

function setup() {
  const clock = new FakeClock(START);
  const scheduler = new FakeScheduler(clock);
  const storage = new InMemoryQueueStorage();
  const transport = new ScriptedTransport();
  const events: QueueEvent[] = [];
  let offsetMs = 0;
  let nextId = 0;
  const queue = new ScanQueue({
    storage,
    transport,
    scheduler,
    now: clock.now,
    clockOffsetMs: () => offsetMs,
    random: () => 0,
    newLocalId: () => `local-${++nextId}`,
  });
  queue.subscribe((event) => events.push(event));
  return {
    clock, scheduler, storage, transport, queue, events,
    setOffset: (value: number) => {
      offsetMs = value;
    },
  };
}

/** File ouverte, émettrice et connectée : chaque capture part immédiatement. */
async function sendingSetup() {
  const context = setup();
  await context.queue.open();
  context.queue.setEmitter(true);
  await settle();
  context.queue.setSendingEnabled(true);
  await settle();
  return context;
}

function states(items: readonly ScanItem[]): string[] {
  return items.map((item) => item.state);
}

describe('CA13 - anti-rebond (RG17)', () => {
  it('même token avant 10 s ignoré, à 10 s pile capturé ; un autre token jamais filtré', async () => {
    const { queue, clock } = setup();
    await queue.open();

    expect((await queue.capture(TOKEN_T)).kind).toBe('captured');
    clock.current = START + 500;
    expect((await queue.capture(TOKEN_U)).kind).toBe('captured');
    clock.current = START + 9_999;
    expect(await queue.capture(TOKEN_T)).toEqual({ kind: 'duplicate', qrToken: TOKEN_T });
    expect(queue.snapshot().items.filter((item) => item.qrToken === TOKEN_T)).toHaveLength(1);
    clock.current = START + 10_000;
    expect((await queue.capture(TOKEN_T)).kind).toBe('captured');
    expect(queue.snapshot().items.filter((item) => item.qrToken === TOKEN_T)).toHaveLength(2);
  });

  it('CA12 - contenu non reconnu : rien n\'est ajouté à la file', async () => {
    const { queue, storage } = setup();
    await queue.open();
    expect(await queue.capture('https://exemple.fr/r/3f2c')).toEqual({ kind: 'invalid' });
    expect(await queue.capture('')).toEqual({ kind: 'invalid' });
    expect(queue.snapshot().items).toHaveLength(0);
    expect(storage.records.size).toBe(0);
  });
});

describe('RG19 / RG20 - capture horodatée et écrite avant tout envoi', () => {
  it('scannedAt = instant de l\'appareil + décalage, écrit EN_ATTENTE dans le stockage', async () => {
    const { queue, storage, clock, setOffset } = setup();
    await queue.open();
    setOffset(5000);
    clock.current = Date.UTC(2026, 9, 3, 10, 1, 0, 123);

    const result = await queue.capture(` ${TOKEN_T.toUpperCase()} `);

    expect(result.kind).toBe('captured');
    const item = queue.snapshot().items[0];
    expect(item.scannedAt).toBe('2026-10-03T10:01:05.123Z');
    expect(item.capturedAt).toBe(clock.current);
    expect(item.qrToken).toBe(TOKEN_T);
    expect(storage.stored(item.localId).state).toBe('EN_ATTENTE');
  });

  it('sans identifiants ou sans émetteur : capture possible, aucun envoi (RG14, RG21)', async () => {
    const { queue, transport } = setup();
    await queue.open();
    queue.setEmitter(true);
    await settle();
    await queue.capture(TOKEN_T);
    expect(transport.bodies).toHaveLength(0);
    expect(queue.snapshot().pendingCount).toBe(1);

    queue.setEmitter(false);
    await settle();
    queue.setSendingEnabled(true);
    await settle();
    expect(transport.bodies).toHaveLength(0);
  });

  it('CL17 - échec d\'écriture locale : une tentative d\'envoi directe unique, sans nouvel essai', async () => {
    const { queue, storage, transport, events } = await sendingSetup();
    storage.failWrites = true;

    const result = await queue.capture(TOKEN_T);

    expect(result.kind).toBe('storage-failed');
    expect(transport.bodies).toHaveLength(1);
    await transport.respond(NETWORK_ERROR);
    expect(events.filter((event) => event.type === 'rejected')).toHaveLength(1);
    expect(queue.snapshot().items).toHaveLength(0);
    expect(transport.pendingRequests).toBe(0);
  });

  it('CL17 - envoi direct accepté : événement accepté non persisté', async () => {
    const { queue, storage, transport, events } = await sendingSetup();
    storage.failWrites = true;
    await queue.capture(TOKEN_T);
    await transport.respond(scanAccepted(1, 'Alice', 1));
    expect(events).toContainEqual(expect.objectContaining({ type: 'accepted', persisted: false }));
  });

  it('échec du stockage pendant l\'envoi : signalé par un événement failure, jamais ignoré', async () => {
    const { queue, storage, transport, events } = await sendingSetup();
    await queue.capture(TOKEN_T);
    storage.failWrites = true;
    await transport.respond(scanAccepted(1, 'Alice', 1));
    expect(events).toContainEqual(expect.objectContaining({ type: 'failure' }));
  });
});

describe('CA14 - FIFO et blocage en tête (RG21, RG22, RG24)', () => {
  async function queueOfThree() {
    const context = setup();
    await context.queue.open();
    await context.queue.capture(TOKEN_T);
    await context.queue.capture(TOKEN_U);
    await context.queue.capture(TOKEN_V);
    context.queue.setEmitter(true);
    await settle();
    context.queue.setSendingEnabled(true);
    await settle();
    return context;
  }

  it('S1 en erreur réseau : S2 non envoyé ; S1 en 200 : S2 puis S3, une requête à la fois', async () => {
    const { transport, scheduler, queue } = await queueOfThree();
    expect(transport.sentTokens()).toEqual([TOKEN_T]);

    await transport.respond(NETWORK_ERROR);
    expect(transport.sentTokens()).toEqual([TOKEN_T]);
    expect(queue.snapshot().waitingForRetry).toBe(true);

    scheduler.advance(1000);
    await settle();
    expect(transport.sentTokens()).toEqual([TOKEN_T, TOKEN_T]);
    await transport.respond(scanAccepted(1, 'Alice', 1));
    expect(transport.sentTokens()).toEqual([TOKEN_T, TOKEN_T, TOKEN_U]);
    await transport.respond(scanAccepted(2, 'Bob', 1));
    expect(transport.sentTokens()).toEqual([TOKEN_T, TOKEN_T, TOKEN_U, TOKEN_V]);
    await transport.respond(scanAccepted(3, 'Eve', 1));

    expect(transport.maxInFlight).toBe(1);
    expect(states(queue.snapshot().items)).toEqual(['ACCEPTÉ', 'ACCEPTÉ', 'ACCEPTÉ']);
    expect(queue.snapshot().pendingCount).toBe(0);
  });

  it('variante : S2 reçoit 409 BUSINESS_CONFLICT -> S2 REJETÉ, puis S3 est envoyé', async () => {
    const { transport, queue } = await queueOfThree();
    await transport.respond(scanAccepted(1, 'Alice', 1));
    await transport.respond(problem(409, 'BUSINESS_CONFLICT', 'Scan tardif : aucun passage au yard 1'));
    expect(transport.sentTokens()).toEqual([TOKEN_T, TOKEN_U, TOKEN_V]);
    await transport.respond(scanAccepted(3, 'Eve', 1));

    const [s1, s2, s3] = queue.snapshot().items;
    expect([s1.state, s2.state, s3.state]).toEqual(['ACCEPTÉ', 'REJETÉ', 'ACCEPTÉ']);
    expect(s2.lastError).toEqual({ status: 409, code: 'BUSINESS_CONFLICT', detail: 'Scan tardif : aucun passage au yard 1' });
    expect(queue.snapshot().unseenRejectionCount).toBe(1);
  });

  it('variante : S1 reçoit 401 -> aucune requête pour S2 ni S3 avant connexion, puis S1 renvoyé en premier', async () => {
    const { transport, queue, scheduler, events } = await queueOfThree();
    await transport.respond(problem(401, 'UNAUTHENTICATED', 'Identifiants invalides'));

    expect(queue.snapshot().suspension).toBe('AUTH');
    expect(queue.snapshot().items[0].state).toBe('EN_ATTENTE');
    expect(events).toContainEqual(expect.objectContaining({ type: 'suspended', suspension: 'AUTH' }));
    scheduler.advance(120_000);
    await queue.process();
    queue.retryNow();
    await settle();
    expect(transport.sentTokens()).toEqual([TOKEN_T]);

    queue.setSendingEnabled(true);
    await settle();
    expect(transport.sentTokens()).toEqual([TOKEN_T, TOKEN_T]);
    expect(queue.snapshot().suspension).toBeNull();
  });

  it('403 : file suspendue (RÔLE), élément toujours EN_ATTENTE', async () => {
    const { transport, queue } = await queueOfThree();
    await transport.respond(problem(403, 'ACCESS_DENIED', 'accès refusé pour le rôle SCANNER'));
    expect(queue.snapshot().suspension).toBe('ROLE');
    expect(states(queue.snapshot().items)).toEqual(['EN_ATTENTE', 'EN_ATTENTE', 'EN_ATTENTE']);
    expect(transport.sentTokens()).toEqual([TOKEN_T]);
  });

  it('déconnexion pendant un envoi : l\'envoi en cours se termine, puis la file s\'arrête', async () => {
    const { transport, queue } = await queueOfThree();
    queue.setSendingEnabled(false);
    await settle();
    await transport.respond(scanAccepted(1, 'Alice', 1));
    expect(transport.sentTokens()).toEqual([TOKEN_T]);
    expect(queue.snapshot().sendingEnabled).toBe(false);
  });
});

describe('CA9 - état de l\'élément selon la réponse de E6 (RG24)', () => {
  it.each<[string, RawHttpResult, string, boolean]>([
    ['200', scanAccepted(1, 'Alice', 1), 'ACCEPTÉ', false],
    ['400 INVALID_INPUT', problem(400, 'INVALID_INPUT', 'scan dans le futur'), 'REJETÉ', false],
    ['404', problem(404, 'RESOURCE_NOT_FOUND', 'qrToken inconnu'), 'REJETÉ', false],
    ['409 BUSINESS_CONFLICT', problem(409, 'BUSINESS_CONFLICT', 'course terminée'), 'REJETÉ', false],
    ['415', problem(415, 'MALFORMED_REQUEST', 'type non supporté'), 'REJETÉ', false],
    ['401', problem(401, 'UNAUTHENTICATED', 'Identifiants invalides'), 'EN_ATTENTE', false],
    ['403', problem(403, 'ACCESS_DENIED', 'refusé'), 'EN_ATTENTE', false],
    ['409 DATA_INTEGRITY', problem(409, 'DATA_INTEGRITY', 'réessayer'), 'EN_ATTENTE', true],
    ['500', jsonResponse(500, { status: 500, code: 'INTERNAL_ERROR' }), 'EN_ATTENTE', true],
    ['502 HTML', { kind: 'response', status: 502, json: false, body: '<html>' }, 'EN_ATTENTE', true],
    ['erreur réseau', NETWORK_ERROR, 'EN_ATTENTE', true],
    ['délai dépassé', { kind: 'timeout' }, 'EN_ATTENTE', true],
  ])('%s -> %s', async (_label, response, expectedState, retryScheduled) => {
    const { queue, transport, storage } = await sendingSetup();
    await queue.capture(TOKEN_T);
    await transport.respond(response);
    const item = queue.snapshot().items[0];
    expect(item.state).toBe(expectedState);
    expect(storage.stored(item.localId).state).toBe(expectedState);
    expect(queue.snapshot().waitingForRetry).toBe(retryScheduled);
  });

  it('libellés d\'erreur transitoire conservés sur l\'élément', async () => {
    const { queue, transport, scheduler } = await sendingSetup();
    await queue.capture(TOKEN_T);
    await transport.respond(NETWORK_ERROR);
    expect(queue.snapshot().items[0].lastError?.detail).toBe('En attente de réseau');
    scheduler.advance(1000);
    await settle();
    await transport.respond({ kind: 'timeout' });
    expect(queue.snapshot().items[0].lastError?.detail).toBe('Serveur indisponible');
    scheduler.advance(2000);
    await settle();
    await transport.respond(problem(409, 'DATA_INTEGRITY', 'Conflit'));
    expect(queue.snapshot().items[0].lastError).toEqual({
      status: 409, code: 'DATA_INTEGRITY', detail: 'Conflit temporaire, nouvel essai',
    });
    scheduler.advance(4000);
    await settle();
    await transport.respond(jsonResponse(503, {}));
    expect(queue.snapshot().items[0].lastError?.detail).toBe('Serveur indisponible');
    expect(queue.snapshot().lastTransientFailureAt).not.toBeNull();
  });
});

describe('CA10 - nouvel essai, essai immédiat, aucun abandon (RG22)', () => {
  it('élément en attente de 32 s : l\'événement online déclenche l\'essai immédiatement', async () => {
    const { queue, transport, scheduler } = await sendingSetup();
    await queue.capture(TOKEN_T);
    const delays = [1000, 2000, 4000, 8000, 16000];
    await transport.respond(NETWORK_ERROR);
    for (const delay of delays) {
      scheduler.advance(delay);
      await settle();
      await transport.respond(NETWORK_ERROR);
    }
    expect(scheduler.pendingDelays()).toEqual([32_000]);
    expect(transport.bodies).toHaveLength(6);

    queue.retryNow();
    await settle();

    expect(transport.bodies).toHaveLength(7);
    expect(scheduler.pendingDelays()).toEqual([]);
  });

  it('après 20 échecs, l\'élément est toujours EN_ATTENTE, délai plafonné à 60 s', async () => {
    const { queue, transport, scheduler } = await sendingSetup();
    await queue.capture(TOKEN_T);
    for (let failure = 1; failure <= 20; failure += 1) {
      await transport.respond(jsonResponse(503, {}));
      const [delay] = scheduler.pendingDelays();
      scheduler.advance(delay);
      await settle();
    }
    expect(queue.snapshot().items[0].state).toBe('EN_COURS');
    await transport.respond(NETWORK_ERROR);
    expect(queue.snapshot().items[0].state).toBe('EN_ATTENTE');
    expect(queue.snapshot().items[0].attempts).toBe(21);
    expect(scheduler.pendingDelays()).toEqual([60_000]);
  });

  it('une nouvelle capture ne court-circuite pas une attente de backoff', async () => {
    const { queue, transport } = await sendingSetup();
    await queue.capture(TOKEN_T);
    await transport.respond(NETWORK_ERROR);
    await queue.capture(TOKEN_U);
    expect(transport.bodies).toHaveLength(1);
  });

  it('le compteur d\'échecs repart de 0 quand la tête de file change', async () => {
    const { queue, transport, scheduler } = await sendingSetup();
    await queue.capture(TOKEN_T);
    await queue.capture(TOKEN_U);
    await transport.respond(NETWORK_ERROR);
    scheduler.advance(1000);
    await settle();
    await transport.respond(NETWORK_ERROR);
    expect(scheduler.pendingDelays()).toEqual([2000]);
    scheduler.advance(2000);
    await settle();
    await transport.respond(scanAccepted(1, 'Alice', 1));
    await transport.respond(NETWORK_ERROR);
    expect(scheduler.pendingDelays()).toEqual([1000]);
  });
});

describe('CA15 - idempotence du corps (RG23)', () => {
  it('3 envois (2 erreurs réseau puis rejet simulé) et 1 renvoi manuel : 4 corps identiques', async () => {
    const { queue, transport, scheduler, setOffset } = await sendingSetup();
    setOffset(2000);
    await queue.capture(TOKEN_T);
    const captured = queue.snapshot().items[0];
    await transport.respond(NETWORK_ERROR);
    setOffset(9000);
    scheduler.advance(1000);
    await settle();
    await transport.respond(NETWORK_ERROR);
    scheduler.advance(2000);
    await settle();
    await transport.respond(problem(409, 'BUSINESS_CONFLICT', 'Scan tardif'));
    expect(queue.snapshot().items[0].state).toBe('REJETÉ');

    await queue.resend(captured.localId);
    await settle();
    await transport.respond(scanAccepted(1, 'Alice', 2));

    expect(transport.bodies).toHaveLength(4);
    expect(new Set(transport.bodies).size).toBe(1);
    expect(transport.bodies[0]).toBe(scanRequestBody(captured));
    expect(JSON.parse(transport.bodies[3])).toEqual({ qrToken: TOKEN_T, scannedAt: captured.scannedAt });
    expect(queue.snapshot().items[0].scannedAt).toBe(captured.scannedAt);
    expect(queue.snapshot().items[0].state).toBe('ACCEPTÉ');
  });
});

describe('RG25 - rejets : marquer comme vu, renvoyer en fin de file', () => {
  it('« Marquer comme vu » retire le rejet du compteur sans le supprimer', async () => {
    const { queue, transport, clock } = await sendingSetup();
    await queue.capture(TOKEN_T);
    await transport.respond(problem(404, 'RESOURCE_NOT_FOUND', 'inconnu'));
    const rejected = queue.snapshot().items[0];
    clock.advance(1000);

    await queue.markSeen(rejected.localId);

    expect(queue.snapshot().unseenRejectionCount).toBe(0);
    expect(queue.snapshot().items[0]).toMatchObject({ state: 'REJETÉ', seen: true, seenAt: clock.current });
    await queue.markSeen('inconnu');
  });

  it('« Renvoyer » place l\'élément après les autres éléments en attente', async () => {
    const { queue, transport, clock } = setup();
    await queue.open();
    queue.setEmitter(true);
    await settle();
    queue.setSendingEnabled(true);
    await settle();
    await queue.capture(TOKEN_T);
    await transport.respond(problem(409, 'BUSINESS_CONFLICT', 'Scan tardif'));
    queue.setSendingEnabled(false);
    await settle();
    clock.advance(1000);
    await queue.capture(TOKEN_U);
    const rejected = queue.snapshot().items[0];

    await queue.resend(rejected.localId);
    await settle();
    queue.setSendingEnabled(true);
    await settle();

    expect(transport.sentTokens()).toEqual([TOKEN_T, TOKEN_U]);
    await transport.respond(scanAccepted(2, 'Bob', 1));
    expect(transport.sentTokens()).toEqual([TOKEN_T, TOKEN_U, TOKEN_T]);
    await queue.resend(queue.snapshot().items[0].localId);
    await settle();
  });
});

describe('CA16 - reprise après interruption et migration du format (RG20, RG46)', () => {
  function stored(localId: string, state: string, capturedAt: number, queuePosition: number): ScanItem {
    return {
      version: 2, localId, qrToken: TOKEN_T, scannedAt: new Date(capturedAt).toISOString(), capturedAt, queuePosition,
      state: state as ScanItem['state'], attempts: 1, nextAttemptAt: null, lastError: null, response: null,
      acceptedAt: null, seen: false, seenAt: null,
    };
  }

  it('un EN_COURS et deux EN_ATTENTE -> trois EN_ATTENTE dans l\'ordre d\'origine', async () => {
    const { queue, storage } = setup();
    storage.records.set('b', stored('b', 'EN_ATTENTE', START + 2, 2));
    storage.records.set('a', stored('a', 'EN_COURS', START + 1, 1));
    storage.records.set('c', stored('c', 'EN_ATTENTE', START + 3, 3));

    await queue.open();

    const items = queue.snapshot().items;
    expect(items.map((item) => item.localId)).toEqual(['a', 'b', 'c']);
    expect(states(items)).toEqual(['EN_ATTENTE', 'EN_ATTENTE', 'EN_ATTENTE']);
    expect(storage.stored('a').state).toBe('EN_ATTENTE');
  });

  it('format de version précédente : migré sans perte, dans l\'ordre de capture', async () => {
    const { queue, storage } = setup();
    storage.records.set('v1-b', {
      version: 1, localId: 'v1-b', qrToken: TOKEN_U, scannedAt: '2026-10-03T10:00:02.000Z', capturedAt: START + 2000,
      state: 'REJETÉ', attempts: 2, error: { status: 409, code: 'BUSINESS_CONFLICT', detail: 'Scan tardif' },
    });
    storage.records.set('v1-a', {
      version: 1, localId: 'v1-a', qrToken: TOKEN_T, scannedAt: '2026-10-03T10:00:01.000Z', capturedAt: START + 1000,
      state: 'ACCEPTÉ', attempts: 1, acceptedAt: START + 1500,
      response: { passageId: 1, runnerId: 1, bib: 1, runnerName: 'Alice', runnerStatus: 'ACTIVE', yardNumber: 1 },
    });
    storage.records.set('v1-c', {
      version: 1, localId: 'v1-c', qrToken: TOKEN_V, scannedAt: '2026-10-03T10:00:03.000Z', capturedAt: START + 3000,
      state: 'EN_ATTENTE', error: 'illisible',
    });

    await queue.open();

    const items = queue.snapshot().items;
    expect(items.map((item) => item.localId)).toEqual(['v1-a', 'v1-b', 'v1-c']);
    expect(items[0]).toMatchObject({ version: 2, state: 'ACCEPTÉ', acceptedAt: START + 1500, seen: false });
    expect(items[0].response?.runnerName).toBe('Alice');
    expect(items[1].lastError).toEqual({ status: 409, code: 'BUSINESS_CONFLICT', detail: 'Scan tardif' });
    expect(items[2]).toMatchObject({ attempts: 0, lastError: null, response: null, acceptedAt: null });
    expect((storage.records.get('v1-a') as ScanItem).version).toBe(2);
    expect(queue.snapshot().unreadableCount).toBe(0);
  });

  it('élément illisible : conservé dans le stockage et signalé, jamais supprimé', async () => {
    const { queue, storage } = setup();
    storage.records.set('x', { version: 99, localId: 'x' });
    storage.records.set('y', 'texte');
    storage.records.set('z', { version: 3, localId: 'z', qrToken: TOKEN_T, scannedAt: 's', capturedAt: 1, state: 'EN_ATTENTE' });
    await queue.open();
    await queue.purgeExpired();
    expect(queue.snapshot().unreadableCount).toBe(3);
    expect(storage.records.size).toBe(3);
  });
});

describe('CA17 - rétention locale (RG27)', () => {
  it('purge : ACCEPTÉ après 24 h, REJETÉ vu après 7 jours, EN_ATTENTE jamais', async () => {
    const { queue, transport, clock, storage } = await sendingSetup();
    await queue.capture(TOKEN_T);
    await transport.respond(scanAccepted(1, 'Alice', 1));
    await queue.capture(TOKEN_U);
    await transport.respond(problem(404, 'RESOURCE_NOT_FOUND', 'inconnu'));
    queue.setSendingEnabled(false);
    await settle();
    await queue.capture(TOKEN_V);
    const rejected = queue.snapshot().items[1];
    await queue.markSeen(rejected.localId);

    clock.advance(24 * HOUR - 60_000);
    await queue.purgeExpired();
    expect(queue.snapshot().items).toHaveLength(3);

    clock.advance(2 * 60_000);
    await queue.purgeExpired();
    expect(queue.snapshot().items.map((item) => item.qrToken)).toEqual([TOKEN_U, TOKEN_V]);

    clock.advance(7 * DAY);
    await queue.purgeExpired();
    expect(queue.snapshot().items.map((item) => item.qrToken)).toEqual([TOKEN_V]);
    expect(storage.records.size).toBe(1);

    clock.advance(30 * DAY);
    await queue.open();
    expect(queue.snapshot().items.map((item) => item.state)).toEqual(['EN_ATTENTE']);
  });
});

describe('CA11 - horodatage fixé à la capture (RG4, RG19)', () => {
  it('décalage +5 s, capture à 10:01:00.123 ; nouvelle mesure +1 s : scannedAt et corps envoyés inchangés', async () => {
    const { queue, transport, scheduler, clock, setOffset } = await sendingSetup();
    setOffset(5000);
    clock.current = Date.UTC(2026, 9, 3, 10, 1, 0, 123);
    await queue.capture(TOKEN_T);
    await transport.respond(NETWORK_ERROR);

    setOffset(1000);
    scheduler.advance(1000);
    await settle();

    expect(queue.snapshot().items[0].scannedAt).toBe('2026-10-03T10:01:05.123Z');
    expect(transport.bodies.map((body) => JSON.parse(body).scannedAt))
      .toEqual(['2026-10-03T10:01:05.123Z', '2026-10-03T10:01:05.123Z']);
  });
});

describe('RG21 - plusieurs onglets : relecture du stockage', () => {
  it('refresh charge les captures d\'un autre onglet sans écraser l\'élément en cours d\'envoi', async () => {
    const { queue, transport, storage } = await sendingSetup();
    await queue.capture(TOKEN_T);
    const inFlight = queue.snapshot().items[0];
    storage.records.set(inFlight.localId, { ...inFlight, state: 'EN_ATTENTE' });
    storage.records.set('other-tab', {
      ...inFlight, localId: 'other-tab', qrToken: TOKEN_U, queuePosition: inFlight.queuePosition + 1,
      state: 'EN_ATTENTE', attempts: 0,
    });

    await queue.refresh();

    expect(queue.snapshot().items.map((item) => item.state)).toEqual(['EN_COURS', 'EN_ATTENTE']);
    await transport.respond(scanAccepted(1, 'Alice', 1));
    expect(transport.sentTokens()).toEqual([TOKEN_T, TOKEN_U]);
    expect(transport.maxInFlight).toBe(1);
  });

  it('désabonnement : plus aucun événement reçu', async () => {
    const { queue } = setup();
    const received: QueueEvent[] = [];
    const unsubscribe = queue.subscribe((event) => received.push(event));
    unsubscribe();
    await queue.open();
    await queue.capture(TOKEN_T);
    expect(received).toHaveLength(0);
  });
});
