import { DestroyRef, inject, signal, Signal } from '@angular/core';
import { Poller, PollOutcome } from '../core/polling';
import { browserScheduler } from '../infra/browser-scheduler';

/**
 * Instant de l'appareil rafraîchi chaque seconde, arrêté à la destruction de l'écran (RG31, RG33).
 * À appeler dans un contexte d'injection (initialisation d'un champ de composant).
 */
export function secondTicker(): Signal<number> {
  const now = signal(Date.now());
  const timer = window.setInterval(() => now.set(Date.now()), 1000);
  inject(DestroyRef).onDestroy(() => window.clearInterval(timer));
  return now;
}

/**
 * Polling d'un écran (RG29) : démarré immédiatement, suspendu quand la page est masquée, appel immédiat au
 * retour, arrêté en quittant l'écran.
 */
export function startPagePolling(poll: () => Promise<PollOutcome>, destroyRef: DestroyRef): Poller {
  const poller = new Poller(poll, browserScheduler);
  const onVisibilityChange = (): void => {
    if (document.visibilityState === 'hidden') {
      poller.pause();
    } else {
      void poller.resume();
    }
  };
  document.addEventListener('visibilitychange', onVisibilityChange);
  destroyRef.onDestroy(() => {
    poller.stop();
    document.removeEventListener('visibilitychange', onVisibilityChange);
  });
  void poller.start();
  return poller;
}
