import { describe, expect, it } from 'vitest';
import { IDLE_POLL_DELAY_MS, nextPollDelayMs, PollOutcome, Poller, RUNNING_POLL_DELAY_MS, staleForSeconds } from './polling';
import { FakeClock, FakeScheduler, settle } from './testing/fakes.spec-support';
import { RaceStatus } from './api.types';

/** Appel simulé : chaque appel attend que le test le termine. */
class ControlledPoll {
  calls = 0;
  inFlight = 0;
  maxInFlight = 0;
  private finishers: ((outcome: PollOutcome) => void)[] = [];

  poll = (): Promise<PollOutcome> => {
    this.calls += 1;
    this.inFlight += 1;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    return new Promise((resolve) => this.finishers.push((outcome) => {
      this.inFlight -= 1;
      resolve(outcome);
    }));
  };

  async finish(raceStatus: RaceStatus | null, stop = false): Promise<void> {
    const finisher = this.finishers.shift();
    if (finisher === undefined) {
      throw new Error('aucun appel en cours');
    }
    finisher({ raceStatus, stop });
    await settle();
  }
}

function setup() {
  const clock = new FakeClock(0);
  const scheduler = new FakeScheduler(clock);
  const poll = new ControlledPoll();
  const poller = new Poller(poll.poll, scheduler);
  return { clock, scheduler, poll, poller };
}

describe('RG29 - rythme du polling', () => {
  it('2,5 s après la fin de l\'appel précédent en RUNNING, 10 s sinon', () => {
    expect(nextPollDelayMs('RUNNING')).toBe(RUNNING_POLL_DELAY_MS);
    expect(nextPollDelayMs('SETUP')).toBe(IDLE_POLL_DELAY_MS);
    expect(nextPollDelayMs('FINISHED')).toBe(IDLE_POLL_DELAY_MS);
    expect(nextPollDelayMs(null)).toBe(IDLE_POLL_DELAY_MS);
  });

  it('premier appel à l\'ouverture, suivant programmé après la fin du précédent, jamais deux simultanés', async () => {
    const { scheduler, poll, poller } = setup();
    void poller.start();
    expect(poll.calls).toBe(1);
    scheduler.advance(60_000);
    expect(poll.calls).toBe(1);

    await poll.finish('RUNNING');
    expect(scheduler.pendingDelays()).toEqual([2500]);
    scheduler.advance(2500);
    expect(poll.calls).toBe(2);
    await poll.finish('SETUP');
    expect(scheduler.pendingDelays()).toEqual([10_000]);
    expect(poll.maxInFlight).toBe(1);
  });

  it('échec : le rythme reste celui du dernier statut connu', async () => {
    const { scheduler, poll, poller } = setup();
    void poller.start();
    await poll.finish('RUNNING');
    scheduler.advance(2500);
    await poll.finish(null);
    expect(scheduler.pendingDelays()).toEqual([2500]);
  });

  it('page masquée : aucun appel ; retour : appel immédiat puis rythme normal', async () => {
    const { scheduler, poll, poller } = setup();
    void poller.start();
    await poll.finish('RUNNING');
    poller.pause();
    scheduler.advance(30_000);
    expect(poll.calls).toBe(1);

    void poller.resume();
    expect(poll.calls).toBe(2);
    await poll.finish('RUNNING');
    expect(scheduler.pendingDelays()).toEqual([2500]);
    await poller.resume();
    expect(poll.calls).toBe(2);
  });

  it('masquée pendant un appel : pas de reprogrammation', async () => {
    const { scheduler, poll, poller } = setup();
    void poller.start();
    poller.pause();
    await poll.finish('RUNNING');
    expect(scheduler.pendingDelays()).toEqual([]);
  });

  it('404 (stop) ou sortie de l\'écran : arrêt définitif', async () => {
    const { scheduler, poll, poller } = setup();
    void poller.start();
    await poll.finish('RUNNING', true);
    expect(poller.isStopped()).toBe(true);
    expect(scheduler.pendingDelays()).toEqual([]);

    const second = setup();
    void second.poller.start();
    await second.poll.finish('RUNNING');
    second.poller.stop();
    second.scheduler.advance(10_000);
    expect(second.poll.calls).toBe(1);
    await second.poller.resume();
    expect(second.poll.calls).toBe(1);
  });
});

describe('RG33 - fraîcheur', () => {
  it('bandeau au-delà de 10 s depuis la dernière réponse réussie', () => {
    expect(staleForSeconds(null, 50_000)).toBeNull();
    expect(staleForSeconds(0, 10_000)).toBeNull();
    expect(staleForSeconds(0, 10_001)).toBe(10);
    expect(staleForSeconds(0, 15_500)).toBe(15);
  });
});
