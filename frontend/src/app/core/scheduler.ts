/** Minuterie injectée : `setTimeout` du navigateur en production, simulée en test. */
export interface Scheduler {
  schedule(task: () => void, delayMs: number): TimerHandle;
  cancel(handle: TimerHandle): void;
}

export interface TimerHandle {
  readonly id: number;
}
