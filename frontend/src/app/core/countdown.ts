import { formatClockDuration, parseInstant } from './formats';

/**
 * Compte à rebours du yard courant (RG31) : restant = currentYardEndsAt − (instant de l'appareil + décalage).
 * Le numéro de yard n'est jamais incrémenté localement (RG1) : seule l'API le fournit.
 */

export const NEW_YARD_LABEL = 'Nouveau yard…';

export interface YardCountdown {
  /** « m:ss », ou « h:mm:ss » au-delà d'une heure ; « 0:00 » une fois la cloche passée. */
  readonly remaining: string;
  /** Vrai quand la cloche est passée et que la prochaine réponse E4 est attendue. */
  readonly bellRang: boolean;
}

export function yardCountdown(currentYardEndsAt: string, deviceNow: number, offsetMs: number): YardCountdown {
  const remainingMs = parseInstant(currentYardEndsAt) - (deviceNow + offsetMs);
  if (remainingMs <= 0) {
    return { remaining: '0:00', bellRang: true };
  }
  return { remaining: formatClockDuration(Math.ceil(remainingMs / 1000)), bellRang: false };
}
