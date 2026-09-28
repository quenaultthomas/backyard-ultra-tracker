import { describe, expect, it } from 'vitest';
import { RawHttpResult } from './http-classification';
import { ScanQueue } from './scan-queue';
import {
  FakeClock,
  FakeScheduler,
  InMemoryQueueStorage,
  scanAccepted,
  ScriptedTransport,
  settle,
  TOKEN_T,
  TOKEN_U,
} from './testing/fakes.spec-support';

/**
 * CA45 (spec inc. 4 amendée le 2026-09-27, BUG-3) : deux instances de la file partagent le même stockage simulé.
 * E est émettrice, envoi autorisé, serveur simulé en ligne ; N n'est pas émettrice. La notification d'un ajout
 * entre contextes (BroadcastChannel en production) est, côté logique pure, la relecture `refresh()` de E.
 */

const START = Date.UTC(2026, 9, 3, 10, 0, 0, 0);
const NETWORK_ERROR: RawHttpResult = { kind: 'network-error' };
const SECOND = 1000;

function twoContexts() {
  const clock = new FakeClock(START);
  const scheduler = new FakeScheduler(clock);
  const storage = new InMemoryQueueStorage();
  let nextId = 0;
  const newQueue = (transport: ScriptedTransport) => new ScanQueue({
    storage,
    transport,
    scheduler,
    now: clock.now,
    clockOffsetMs: () => 0,
    random: () => 0,
    newLocalId: () => `local-${++nextId}`,
  });
  const emitterTransport = new ScriptedTransport();
  const otherTransport = new ScriptedTransport();
  return {
    clock, scheduler, storage, emitterTransport, otherTransport,
    emitter: newQueue(emitterTransport),
    other: newQueue(otherTransport),
  };
}

/** E émettrice et autorisée à envoyer ; N ouverte, connectée elle aussi (cas le plus exposé), non émettrice. */
async function openedContexts() {
  const context = twoContexts();
  await context.emitter.open();
  await context.other.open();
  context.emitter.setEmitter(true);
  context.emitter.setSendingEnabled(true);
  context.other.setSendingEnabled(true);
  await settle();
  return context;
}

describe('CA45 - traitement d\'un ajout venu d\'un autre contexte (RG21, RG22)', () => {
  it('ajout de S1 par N puis notification seule : E émet exactement une requête E6, corps de la capture, S1 ACCEPTÉ',
    async () => {
      const { emitter, other, emitterTransport, otherTransport, storage } = await openedContexts();

      const capture = await other.capture(TOKEN_T);
      expect(capture.kind).toBe('captured');
      const s1 = other.snapshot().items[0];
      await settle();
      expect(emitterTransport.bodies).toHaveLength(0);

      await emitter.refresh();
      await settle();

      expect(emitterTransport.bodies).toHaveLength(1);
      expect(JSON.parse(emitterTransport.bodies[0])).toEqual({ qrToken: TOKEN_T, scannedAt: s1.scannedAt });
      expect(Object.keys(JSON.parse(emitterTransport.bodies[0]) as object)).toEqual(['qrToken', 'scannedAt']);

      await emitterTransport.respond(scanAccepted(1, 'Alice', 1));
      await settle();

      expect(emitterTransport.bodies).toHaveLength(1);
      expect(storage.stored(s1.localId).state).toBe('ACCEPTÉ');
      expect(emitter.snapshot().items.map((item) => item.state)).toEqual(['ACCEPTÉ']);
      expect(otherTransport.bodies).toHaveLength(0);
    });

  it('RG21 - après la notification de E, N affiche l\'état de la même file (S1 ACCEPTÉ, 0 en attente)', async () => {
    const { emitter, other, emitterTransport } = await openedContexts();
    await other.capture(TOKEN_T);
    await emitter.refresh();
    await emitterTransport.respond(scanAccepted(1, 'Alice', 1));

    await other.refresh();

    expect(other.snapshot().items.map((item) => item.state)).toEqual(['ACCEPTÉ']);
    expect(other.snapshot().pendingCount).toBe(0);
  });

  it('variante backoff de 32 s sur S0 : aucune requête avant la fin de l\'attente, puis S0 puis S1', async () => {
    const { emitter, other, scheduler, emitterTransport, otherTransport } = await openedContexts();
    await emitter.capture(TOKEN_T);
    const failuresBefore32s = [1, 2, 4, 8, 16];
    await emitterTransport.respond(NETWORK_ERROR);
    for (const delaySeconds of failuresBefore32s) {
      scheduler.advance(delaySeconds * SECOND);
      await emitterTransport.respond(NETWORK_ERROR);
    }
    expect(emitterTransport.bodies).toHaveLength(6);
    expect(scheduler.pendingDelays()).toEqual([32 * SECOND]);

    await other.capture(TOKEN_U);
    await emitter.refresh();
    await settle();
    expect(emitterTransport.bodies).toHaveLength(6);

    scheduler.advance(32 * SECOND - 1);
    await settle();
    expect(emitterTransport.bodies).toHaveLength(6);

    scheduler.advance(1);
    await settle();
    expect(emitterTransport.bodies).toHaveLength(7);
    expect(emitterTransport.sentTokens()[6]).toBe(TOKEN_T);
    await emitterTransport.respond(scanAccepted(1, 'Alice', 1));

    expect(emitterTransport.bodies).toHaveLength(8);
    expect(emitterTransport.sentTokens()[7]).toBe(TOKEN_U);
    await emitterTransport.respond(scanAccepted(2, 'Bob', 1));

    expect(emitter.snapshot().items.map((item) => [item.qrToken, item.state])).toEqual([
      [TOKEN_T, 'ACCEPTÉ'],
      [TOKEN_U, 'ACCEPTÉ'],
    ]);
    expect(emitterTransport.maxInFlight).toBe(1);
    expect(otherTransport.bodies).toHaveLength(0);
  });

  it('N n\'émet aucune requête E6, même connectée, même quand elle capture elle-même plusieurs éléments', async () => {
    const { emitter, other, clock, emitterTransport, otherTransport } = await openedContexts();

    await other.capture(TOKEN_T);
    clock.advance(SECOND);
    await other.capture(TOKEN_U);
    other.retryNow();
    await other.refresh();
    await settle();

    expect(otherTransport.bodies).toHaveLength(0);
    await emitter.refresh();
    await emitterTransport.respond(scanAccepted(1, 'Alice', 1));
    await emitterTransport.respond(scanAccepted(2, 'Bob', 1));
    expect(emitterTransport.sentTokens()).toEqual([TOKEN_T, TOKEN_U]);
    expect(otherTransport.bodies).toHaveLength(0);
  });

  it('notification reçue pendant un envoi en cours : aucune seconde requête simultanée ni envoi en double (CL12)',
    async () => {
      const { emitter, other, emitterTransport } = await openedContexts();
      await emitter.capture(TOKEN_T);
      await settle();
      expect(emitterTransport.pendingRequests).toBe(1);

      await other.capture(TOKEN_U);
      await emitter.refresh();
      await settle();
      expect(emitterTransport.bodies).toHaveLength(1);

      await emitterTransport.respond(scanAccepted(1, 'Alice', 1));
      await emitterTransport.respond(scanAccepted(2, 'Bob', 1));

      expect(emitterTransport.sentTokens()).toEqual([TOKEN_T, TOKEN_U]);
      expect(emitterTransport.maxInFlight).toBe(1);
    });
});
