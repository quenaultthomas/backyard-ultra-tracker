import { describe, expect, it } from 'vitest';
import { canEmit, EmitterRole } from './emitter-role';
import { RawHttpResult } from './http-classification';
import { Device, FakeSharedLock, PwaContext } from './testing/device.spec-support';
import { scanAccepted, settle, TOKEN_T } from './testing/fakes.spec-support';

/**
 * CA45, variante « transfert de rôle » (spec inc. 4, arbitrage OBS-T1 du 2026-09-27, RG22 amendée) :
 * l'état de backoff appartient au contexte émetteur et ne se transmet pas. La prise du rôle d'émetteur est un
 * déclencheur d'essai immédiat, le compteur d'échecs du nouvel émetteur part de 0, et les invariants de RG21
 * restent exigés (une seule requête E6 en cours par appareil, corps identique à chaque envoi, RG23).
 */

const START = Date.UTC(2026, 9, 3, 10, 0, 0, 0);
const NETWORK_ERROR: RawHttpResult = { kind: 'network-error' };
const SECOND = 1000;
/** Échecs suivis des attentes de 1, 2, 4, 8 et 16 s : le 6e échec ouvre l'attente de 32 s. */
const FAILURES_BEFORE_32_S = 5;

/** E émettrice ; N ouverte, connectée, non émettrice. */
async function emitterAndOther(random: () => number = () => 0) {
  const device = new Device({ start: START, random });
  const emitter = await device.openContext('E');
  const other = await device.openContext('N');
  emitter.queue.setEmitter(true);
  await settle();
  return { device, emitter, other };
}

/** E capture S0 puis subit 6 échecs TRANSITOIRES consécutifs : backoff de 32 s en cours. */
async function sixTransientFailuresOnHead(device: Device, emitter: PwaContext) {
  await emitter.capture(TOKEN_T);
  const s0 = emitter.queue.snapshot().items[0];
  await emitter.transport.respond(NETWORK_ERROR);
  for (let failure = 1; failure <= FAILURES_BEFORE_32_S; failure++) {
    const [pendingRetry] = device.scheduler.pendingDelays();
    device.scheduler.advance(pendingRetry);
    await emitter.transport.respond(NETWORK_ERROR);
  }
  expect(emitter.transport.bodies).toHaveLength(6);
  const [backoffInProgress] = device.scheduler.pendingDelays();
  expect(backoffInProgress).toBeGreaterThanOrEqual(32 * SECOND);
  expect(backoffInProgress).toBeLessThanOrEqual(1.2 * 32 * SECOND);
  return s0;
}

/** Fermeture simulée de E : il rend le rôle une fois inactif, puis N le prend. */
async function transferRole(emitter: PwaContext, other: PwaContext) {
  emitter.queue.setEmitter(false);
  await emitter.queue.whenIdle();
  emitter.close();
  other.queue.setEmitter(true);
  await settle();
}

describe('CA45 - variante « transfert de rôle » (RG22 amendée, OBS-T1)', () => {
  it('N envoie S0 immédiatement, sans attendre la fin des 32 s, avec le corps enregistré à la capture (RG23)',
    async () => {
      const { device, emitter, other } = await emitterAndOther();
      const s0 = await sixTransientFailuresOnHead(device, emitter);
      const instantOfTransfer = device.clock.now();

      await transferRole(emitter, other);

      expect(device.clock.now()).toBe(instantOfTransfer);
      expect(other.transport.bodies).toHaveLength(1);
      expect(other.transport.bodies[0]).toBe(emitter.transport.bodies[0]);
      expect(JSON.parse(other.transport.bodies[0])).toEqual({ qrToken: TOKEN_T, scannedAt: s0.scannedAt });
      expect(new Set(device.network.allBodies)).toEqual(new Set([emitter.transport.bodies[0]]));
    });

  it('E n\'émet plus aucune requête : ni à la fin de son ancien backoff de 32 s, ni sur un essai immédiat',
    async () => {
      const { device, emitter, other } = await emitterAndOther();
      await sixTransientFailuresOnHead(device, emitter);

      await transferRole(emitter, other);
      await other.transport.respond(scanAccepted(1, 'Alice', 1));
      emitter.queue.retryNow();
      await emitter.queue.refresh();
      device.scheduler.advance(120 * SECOND);
      await settle();

      expect(emitter.transport.bodies).toHaveLength(6);
      expect(other.transport.bodies).toHaveLength(1);
      expect(device.storage.stored(other.queue.snapshot().items[0].localId).state).toBe('ACCEPTÉ');
    });

  it.each([
    { random: 0, expectedDelayMs: 1000 },
    { random: 0.5, expectedDelayMs: 1100 },
    { random: 1, expectedDelayMs: 1200 },
  ])('échec TRANSITOIRE de l\'envoi de N : nouvel essai après délai(1) = $expectedDelayMs ms (compteur repartant de 0)',
    async ({ random, expectedDelayMs }) => {
      const { device, emitter, other } = await emitterAndOther(() => random);
      await sixTransientFailuresOnHead(device, emitter);
      await transferRole(emitter, other);

      await other.transport.respond(NETWORK_ERROR);
      device.scheduler.advance(expectedDelayMs - 1);
      await settle();
      expect(other.transport.bodies).toHaveLength(1);

      device.scheduler.advance(1);
      await settle();
      expect(other.transport.bodies).toHaveLength(2);
      expect(other.transport.bodies[1]).toBe(other.transport.bodies[0]);
      expect(emitter.transport.bodies).toHaveLength(6);
    });

  it('à aucun moment deux requêtes E6 ne sont en cours ensemble sur l\'appareil', async () => {
    const { device, emitter, other } = await emitterAndOther();
    await sixTransientFailuresOnHead(device, emitter);

    await transferRole(emitter, other);
    await other.transport.respond(NETWORK_ERROR);
    device.scheduler.advance(1200);
    await other.transport.respond(scanAccepted(1, 'Alice', 1));
    device.scheduler.advance(120 * SECOND);
    await settle();

    expect(device.network.maxInFlight).toBe(1);
    expect(device.network.allBodies).toHaveLength(8);
    expect(new Set(device.network.allBodies).size).toBe(1);
  });

  it('transfert par le verrou partagé (fermeture brutale de E) : N obtient le rôle et envoie S0 immédiatement',
    async () => {
      const { device, emitter, other } = await emitterAndOtherWithSharedLock();
      await sixTransientFailuresOnHead(device, emitter.context);
      expect(other.context.transport.bodies).toHaveLength(0);

      emitter.context.queue.setEmitter(false);
      emitter.context.close();
      emitter.lock.forceRelease();
      await settle();

      expect(other.context.transport.bodies).toHaveLength(1);
      expect(other.context.transport.bodies[0]).toBe(emitter.context.transport.bodies[0]);
      device.scheduler.advance(60 * SECOND);
      await settle();
      expect(emitter.context.transport.bodies).toHaveLength(6);
      expect(device.network.maxInFlight).toBe(1);
    });
});

/** E et N candidats au même verrou d'émetteur (Web Locks simulé) : E l'obtient en premier. */
async function emitterAndOtherWithSharedLock() {
  const device = new Device({ start: START });
  const lock = new FakeSharedLock();
  const withRole = async (name: string) => {
    const context = await device.openContext(name);
    const role = new EmitterRole({
      lock,
      queue: context.queue,
      canEmit: () => context.opened && canEmit(true, context.queue.snapshot().suspension),
      onFailure: (error: unknown) => context.failures.push(error),
    });
    role.update();
    await settle();
    return { context, role, lock };
  };
  const emitter = await withRole('E');
  const other = await withRole('N');
  return { device, emitter, other };
}
