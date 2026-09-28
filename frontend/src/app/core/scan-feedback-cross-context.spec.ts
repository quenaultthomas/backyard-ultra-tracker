import { describe, expect, it } from 'vitest';
import { Device, PwaContext, vibrationPulses } from './testing/device.spec-support';
import { problem, scanAccepted, settle, TOKEN_T, TOKEN_U, TOKEN_V } from './testing/fakes.spec-support';

/**
 * CA46 (spec inc. 4, arbitrage OBS-T2 du 2026-09-27, RG50 amendée, RG21, CL12) : le retour d'un résultat serveur
 * de moins de 5 s (bandeau, texte, son, vibration) est donné par le contexte qui a fait la capture, même si l'envoi
 * a été fait par un autre contexte émetteur. Les autres contextes, émetteur compris, ne jouent rien pour ce
 * résultat. Au-delà de 5 s, ou si le contexte de capture est fermé, aucun contexte ne joue de son ni de vibration.
 *
 * Montage : deux instances de la file sur le même stockage simulé, chacune avec son lecteur de retour instrumenté
 * (voir `PwaContext`). E est émettrice, N capture. Horloge simulée.
 */

const START = Date.UTC(2026, 9, 3, 10, 0, 0, 0);
const SECOND = 1000;
const LIVE_WINDOW_END = START + 5 * SECOND;

const ONE_HIGH_BEEP = { count: 1, pitch: 'aigu' };
const TWO_HIGH_BEEPS = { count: 2, pitch: 'aigu' };
const THREE_LOW_BEEPS = { count: 3, pitch: 'grave' };

/** E émettrice, envoi autorisé, serveur simulé en ligne ; N connectée, non émettrice, fait les captures. */
async function emitterAndCapturer() {
  const device = new Device({ start: START });
  const emitter = await device.openContext('E');
  const capturer = await device.openContext('N');
  emitter.queue.setEmitter(true);
  await settle();
  return { device, emitter, capturer };
}

/** N capture à `t` ; E, averti de l'ajout, envoie l'élément. */
async function captureOnN(capturer: PwaContext, emitter: PwaContext, token: string) {
  const result = await capturer.capture(token);
  expect(result.kind).toBe('captured');
  await settle();
  expect(emitter.transport.pendingRequests).toBe(1);
  return capturer.queue.snapshot().items.find((item) => item.qrToken === token)!;
}

function expectCaptureFeedbackOnly(context: PwaContext) {
  expect(context.shown).toHaveLength(1);
  expect(context.beeps()).toEqual([ONE_HIGH_BEEP]);
  expect(context.vibrations()).toEqual([[50]]);
}

describe('CA46 - retour du scan donné par le contexte de capture (RG50 amendée, RG21, CL12)', () => {
  it('acceptation rapide : N affiche le bandeau vert, 2 bips et 2 × 50 ms en plus du retour de capture ; E ne joue rien',
    async () => {
      const { device, emitter, capturer } = await emitterAndCapturer();
      await captureOnN(capturer, emitter, TOKEN_T);

      device.scheduler.advance(SECOND);
      await emitter.transport.respond(scanAccepted(1, 'Alice', 3));
      await settle();

      expect(device.clock.now()).toBeLessThan(LIVE_WINDOW_END);
      expect(capturer.banner).toMatchObject({ tone: 'success', text: 'Dossard 1 — Alice — yard 3' });
      expect(capturer.beeps()).toEqual([ONE_HIGH_BEEP, TWO_HIGH_BEEPS]);
      expect(capturer.vibrations()).toHaveLength(2);
      expect(capturer.vibrations()[0]).toEqual([50]);
      expect(vibrationPulses(capturer.vibrations()[1])).toEqual([50, 50]);
      expect(emitter.shown).toEqual([]);
      expect(device.network.allBodies).toHaveLength(1);
      expect(capturer.transport.bodies).toHaveLength(0);
    });

  it('rejet rapide 409 BUSINESS_CONFLICT « Scan tardif » : N en rouge avec le detail et la consigne, 3 bips graves, '
    + '[200, 100, 200] ; E ne joue rien', async () => {
    const { device, emitter, capturer } = await emitterAndCapturer();
    await captureOnN(capturer, emitter, TOKEN_T);

    device.scheduler.advance(SECOND);
    await emitter.transport.respond(problem(409, 'BUSINESS_CONFLICT', 'Scan tardif'));
    await settle();

    expect(device.clock.now()).toBeLessThan(LIVE_WINDOW_END);
    expect(capturer.banner?.tone).toBe('error');
    expect(capturer.banner?.text).toContain('Scan tardif');
    expect(capturer.banner?.text).toContain("À signaler à l'organisateur");
    expect(capturer.beeps()).toEqual([ONE_HIGH_BEEP, THREE_LOW_BEEPS]);
    expect(capturer.vibrations()).toEqual([[50], [200, 100, 200]]);
    expect(emitter.shown).toEqual([]);
    expect(device.network.allBodies).toHaveLength(1);
  });

  it('résultat différé (acceptation reçue à t + 6 s) : ni N ni E ne jouent de son ou de vibration, S2 ACCEPTÉ '
    + 'dans l\'historique de N', async () => {
    const { device, emitter, capturer } = await emitterAndCapturer();
    const s2 = await captureOnN(capturer, emitter, TOKEN_U);

    device.scheduler.advance(6 * SECOND);
    await emitter.transport.respond(scanAccepted(2, 'Bob', 1));
    await settle();

    expectCaptureFeedbackOnly(capturer);
    expect(emitter.shown).toEqual([]);
    expect(capturer.queue.snapshot().items.find((item) => item.localId === s2.localId)?.state).toBe('ACCEPTÉ');
    expect(device.network.allBodies).toHaveLength(1);
  });

  it('contexte de capture fermé avant la réponse (reçue à t + 1 s) : E ne joue ni son ni vibration, S3 ACCEPTÉ',
    async () => {
      const { device, emitter, capturer } = await emitterAndCapturer();
      const s3 = await captureOnN(capturer, emitter, TOKEN_V);

      capturer.close();
      device.scheduler.advance(SECOND);
      await emitter.transport.respond(scanAccepted(3, 'Chloé', 1));
      await settle();

      expect(emitter.shown).toEqual([]);
      expectCaptureFeedbackOnly(capturer);
      expect(device.storage.stored(s3.localId).state).toBe('ACCEPTÉ');
      expect(device.network.allBodies).toHaveLength(1);
    });

  describe('non-régression : l\'émetteur E capture lui-même (sans N), comportement de CA42 inchangé', () => {
    async function emitterAlone() {
      const device = new Device({ start: START });
      const emitter = await device.openContext('E');
      emitter.queue.setEmitter(true);
      await settle();
      return { device, emitter };
    }

    it('capture acceptée en ligne : 1 bip et 50 ms à la capture, puis bandeau vert, 2 bips et 2 × 50 ms', async () => {
      const { device, emitter } = await emitterAlone();
      await emitter.capture(TOKEN_T);

      device.scheduler.advance(SECOND);
      await emitter.transport.respond(scanAccepted(1, 'Alice', 3));

      expect(emitter.banner).toMatchObject({ tone: 'success', text: 'Dossard 1 — Alice — yard 3' });
      expect(emitter.beeps()).toEqual([ONE_HIGH_BEEP, TWO_HIGH_BEEPS]);
      expect(emitter.vibrations()[0]).toEqual([50]);
      expect(vibrationPulses(emitter.vibrations()[1])).toEqual([50, 50]);
      expect(device.network.allBodies).toHaveLength(1);
    });

    it('« QR non reconnu » : bandeau rouge, 3 bips graves, [200, 100, 200], aucune requête E6', async () => {
      const { device, emitter } = await emitterAlone();
      await emitter.capture('https://exemple.org/pas-un-token');

      expect(emitter.banner).toMatchObject({ tone: 'error', text: 'QR non reconnu' });
      expect(emitter.beeps()).toEqual([THREE_LOW_BEEPS]);
      expect(emitter.vibrations()).toEqual([[200, 100, 200]]);
      expect(device.network.allBodies).toHaveLength(0);
    });

    it('acceptation reçue plus de 5 s après la capture : aucun son ni vibration à l\'acceptation', async () => {
      const { device, emitter } = await emitterAlone();
      await emitter.capture(TOKEN_T);

      device.scheduler.advance(6 * SECOND);
      await emitter.transport.respond(scanAccepted(1, 'Alice', 3));

      expectCaptureFeedbackOnly(emitter);
      expect(emitter.queue.snapshot().items[0].state).toBe('ACCEPTÉ');
      expect(device.network.allBodies).toHaveLength(1);
    });
  });
});
