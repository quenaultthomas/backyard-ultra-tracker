import { parseInstant } from './formats';

/**
 * Décalage d'horloge entre l'appareil et le serveur (RG4) : mesuré à chaque réponse portant `serverTime`
 * (E4, E19), la dernière mesure remplace la précédente. Sert à l'horodatage des scans (RG19) et au compte à
 * rebours (RG31).
 */

export interface ClockOffsetMeasure {
  /** serverTime − (t_envoi + t_réception) / 2, en millisecondes. */
  readonly offsetMs: number;
  /** Instant de l'appareil auquel la mesure a été faite. */
  readonly measuredAt: number;
}

/** Au-delà de ce décalage (strictement), l'écran de scan avertit (RG4). */
export const CLOCK_SKEW_WARNING_MS = 5_000;

export type ClockStatus =
  | { readonly kind: 'unverified' }
  | { readonly kind: 'verified'; readonly offsetMs: number }
  | { readonly kind: 'skewed'; readonly offsetMs: number };

export function measureClockOffset(sentAt: number, receivedAt: number, serverTime: string): ClockOffsetMeasure {
  return { offsetMs: parseInstant(serverTime) - (sentAt + receivedAt) / 2, measuredAt: receivedAt };
}

/** Décalage à appliquer : 0 sans aucune mesure. */
export function offsetOf(measure: ClockOffsetMeasure | null): number {
  return measure?.offsetMs ?? 0;
}

/** Instant de l'appareil corrigé du décalage mesuré. */
export function correctedNow(deviceNow: number, measure: ClockOffsetMeasure | null): number {
  return deviceNow + offsetOf(measure);
}

export function clockStatus(measure: ClockOffsetMeasure | null): ClockStatus {
  if (measure === null) {
    return { kind: 'unverified' };
  }
  return Math.abs(measure.offsetMs) > CLOCK_SKEW_WARNING_MS
    ? { kind: 'skewed', offsetMs: measure.offsetMs }
    : { kind: 'verified', offsetMs: measure.offsetMs };
}

export function clockStatusLabel(status: ClockStatus): string {
  switch (status.kind) {
    case 'unverified':
      return 'Horloge non vérifiée';
    case 'skewed':
      return `Horloge de l'appareil décalée de ${Math.round(Math.abs(status.offsetMs) / 1000)} s (corrigée automatiquement)`;
    case 'verified':
      return 'Horloge vérifiée';
  }
}

/** Relecture tolérante d'une mesure conservée localement ; null si absente ou illisible. */
export function readClockOffsetMeasure(value: unknown): ClockOffsetMeasure | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const record = value as Record<string, unknown>;
  const offsetMs = record['offsetMs'];
  const measuredAt = record['measuredAt'];
  return typeof offsetMs === 'number' && Number.isFinite(offsetMs) && typeof measuredAt === 'number'
    ? { offsetMs, measuredAt }
    : null;
}
