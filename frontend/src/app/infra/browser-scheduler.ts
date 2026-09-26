import { Scheduler, TimerHandle } from '../core/scheduler';

/** Minuterie du navigateur pour la logique pure (file de scans, polling). */
export const browserScheduler: Scheduler = {
  schedule(task: () => void, delayMs: number): TimerHandle {
    return { id: window.setTimeout(task, delayMs) };
  },
  cancel(handle: TimerHandle): void {
    window.clearTimeout(handle.id);
  },
};
