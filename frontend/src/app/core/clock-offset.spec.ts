import { describe, expect, it } from 'vitest';
import {
  clockStatus,
  clockStatusLabel,
  correctedNow,
  measureClockOffset,
  offsetOf,
  readClockOffsetMeasure,
} from './clock-offset';
import { toIsoMillis } from './formats';

const at = (time: string): number => Date.parse(`2026-10-03T${time}Z`);

describe('CA11 - décalage d\'horloge et horodatage (RG4, RG19)', () => {
  it('t_envoi 10:00:00.000, t_réception 10:00:00.400, serverTime 10:00:05.200Z -> décalage +5,000 s', () => {
    const measure = measureClockOffset(at('10:00:00.000'), at('10:00:00.400'), '2026-10-03T10:00:05.200Z');
    expect(measure.offsetMs).toBe(5000);
    expect(measure.measuredAt).toBe(at('10:00:00.400'));
  });

  it('capture à 10:01:00.123 appareil -> scannedAt « …T10:01:05.123Z »', () => {
    const measure = { offsetMs: 5000, measuredAt: at('10:00:00.400') };
    expect(toIsoMillis(correctedNow(at('10:01:00.123'), measure))).toBe('2026-10-03T10:01:05.123Z');
  });

  it('sans mesure : décalage 0 et « Horloge non vérifiée »', () => {
    expect(offsetOf(null)).toBe(0);
    expect(correctedNow(1234, null)).toBe(1234);
    expect(clockStatus(null)).toEqual({ kind: 'unverified' });
    expect(clockStatusLabel(clockStatus(null))).toBe('Horloge non vérifiée');
  });

  it('|décalage| = 5,001 s : avertissement ; 5,000 s : pas d\'avertissement', () => {
    expect(clockStatus({ offsetMs: 5001, measuredAt: 0 }).kind).toBe('skewed');
    expect(clockStatus({ offsetMs: -5001, measuredAt: 0 }).kind).toBe('skewed');
    expect(clockStatus({ offsetMs: 5000, measuredAt: 0 }).kind).toBe('verified');
    expect(clockStatus({ offsetMs: -5000, measuredAt: 0 }).kind).toBe('verified');
  });

  it('libellés : décalage en secondes arrondies, sans signe', () => {
    expect(clockStatusLabel({ kind: 'skewed', offsetMs: -60_400 }))
      .toBe("Horloge de l'appareil décalée de 60 s (corrigée automatiquement)");
    expect(clockStatusLabel({ kind: 'verified', offsetMs: 200 })).toBe('Horloge vérifiée');
  });

  it('relecture d\'une mesure conservée : valide, ou null si illisible', () => {
    expect(readClockOffsetMeasure({ offsetMs: 12, measuredAt: 3 })).toEqual({ offsetMs: 12, measuredAt: 3 });
    expect(readClockOffsetMeasure(null)).toBeNull();
    expect(readClockOffsetMeasure({ offsetMs: 'x', measuredAt: 3 })).toBeNull();
    expect(readClockOffsetMeasure({ offsetMs: Number.NaN, measuredAt: 3 })).toBeNull();
  });
});
