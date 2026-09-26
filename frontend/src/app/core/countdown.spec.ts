import { describe, expect, it } from 'vitest';
import { NEW_YARD_LABEL, yardCountdown } from './countdown';

const device = (time: string): number => Date.parse(`2026-10-03T${time}Z`);

describe('CA19 - compte à rebours (RG31)', () => {
  it('currentYardEndsAt 11:00:00Z, décalage +5 s, appareil 10:59:30 -> restant 0:25', () => {
    expect(yardCountdown('2026-10-03T11:00:00Z', device('10:59:30'), 5000))
      .toEqual({ remaining: '0:25', bellRang: false });
  });

  it('appareil 10:59:55 -> 0:00 et « Nouveau yard… »', () => {
    expect(yardCountdown('2026-10-03T11:00:00Z', device('10:59:55'), 5000))
      .toEqual({ remaining: '0:00', bellRang: true });
    expect(NEW_YARD_LABEL).toBe('Nouveau yard…');
  });

  it('cloche dépassée : toujours 0:00', () => {
    expect(yardCountdown('2026-10-03T11:00:00Z', device('11:00:10'), 0).remaining).toBe('0:00');
  });

  it('au-delà d\'une heure : h:mm:ss ; fraction de seconde arrondie au supérieur', () => {
    expect(yardCountdown('2026-10-03T12:00:00Z', device('10:59:59'), 0).remaining).toBe('1:00:01');
    expect(yardCountdown('2026-10-03T11:00:00Z', device('10:59:59.600'), 0).remaining).toBe('0:01');
  });
});
