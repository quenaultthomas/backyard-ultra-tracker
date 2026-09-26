import { RaceStatus } from './api.types';
import { Scheduler, TimerHandle } from './scheduler';

/**
 * Polling du tableau de bord et du détail coureur (RG29, RG33) : appel suivant 2,5 s après la fin du précédent
 * pour une course RUNNING, 10 s sinon ; jamais deux appels simultanés ; suspendu page masquée, appel immédiat
 * au retour ; arrêt en quittant l'écran ou sur 404.
 */

export const RUNNING_POLL_DELAY_MS = 2_500;
export const IDLE_POLL_DELAY_MS = 10_000;
export const STALE_AFTER_MS = 10_000;

export interface PollOutcome {
  /** Statut de la course connu après l'appel (dernière valeur réussie), null si inconnu. */
  readonly raceStatus: RaceStatus | null;
  /** Vrai pour arrêter le polling (ressource introuvable). */
  readonly stop: boolean;
}

export function nextPollDelayMs(raceStatus: RaceStatus | null): number {
  return raceStatus === 'RUNNING' ? RUNNING_POLL_DELAY_MS : IDLE_POLL_DELAY_MS;
}

/** Âge en secondes de la dernière réponse réussie s'il dépasse 10 s (bandeau de fraîcheur), sinon null. */
export function staleForSeconds(lastSuccessAt: number | null, now: number): number | null {
  if (lastSuccessAt === null || now - lastSuccessAt <= STALE_AFTER_MS) {
    return null;
  }
  return Math.floor((now - lastSuccessAt) / 1000);
}

export class Poller {
  private timer: TimerHandle | null = null;
  private running = false;
  private paused = false;
  private stopped = false;
  private lastStatus: RaceStatus | null = null;

  constructor(private readonly poll: () => Promise<PollOutcome>, private readonly scheduler: Scheduler) {}

  start(): Promise<void> {
    this.stopped = false;
    this.paused = false;
    return this.run();
  }

  /** Page masquée : aucun appel jusqu'au retour. */
  pause(): void {
    this.paused = true;
    this.cancelTimer();
  }

  /** Retour au premier plan : appel immédiat, puis rythme normal. */
  resume(): Promise<void> {
    if (this.stopped || !this.paused) {
      return Promise.resolve();
    }
    this.paused = false;
    this.cancelTimer();
    return this.run();
  }

  stop(): void {
    this.stopped = true;
    this.cancelTimer();
  }

  isStopped(): boolean {
    return this.stopped;
  }

  private async run(): Promise<void> {
    if (this.running || this.stopped || this.paused) {
      return;
    }
    this.running = true;
    try {
      const outcome = await this.poll();
      this.lastStatus = outcome.raceStatus ?? this.lastStatus;
      if (outcome.stop) {
        this.stopped = true;
      }
    } finally {
      this.running = false;
      this.scheduleNext();
    }
  }

  private scheduleNext(): void {
    if (this.stopped || this.paused) {
      return;
    }
    this.timer = this.scheduler.schedule(() => {
      this.timer = null;
      void this.run();
    }, nextPollDelayMs(this.lastStatus));
  }

  private cancelTimer(): void {
    if (this.timer !== null) {
      this.scheduler.cancel(this.timer);
      this.timer = null;
    }
  }
}
