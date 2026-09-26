import { describe, expect, it } from 'vitest';
import { isRetentionExpired } from './retention';
import { newScanItem, ScanItem } from './scan-item';
import { TOKEN_T } from './testing/fakes.spec-support';

const J = Date.UTC(2026, 9, 3, 12, 0, 0);
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function item(overrides: Partial<ScanItem>): ScanItem {
  return { ...newScanItem('l1', TOKEN_T, '2026-10-03T12:00:00.000Z', J, 1), ...overrides };
}

describe('CA17 - rétention (RG27)', () => {
  it('ACCEPTÉ : présent à acceptation + 23 h 59, absent à + 24 h 01', () => {
    const accepted = item({ state: 'ACCEPTÉ', acceptedAt: J });
    expect(isRetentionExpired(accepted, J + 23 * HOUR + 59 * MINUTE)).toBe(false);
    expect(isRetentionExpired(accepted, J + 24 * HOUR + MINUTE)).toBe(true);
    expect(isRetentionExpired(item({ state: 'ACCEPTÉ', acceptedAt: null }), J + 30 * DAY)).toBe(false);
  });

  it('REJETÉ non vu : toujours présent à + 30 jours', () => {
    expect(isRetentionExpired(item({ state: 'REJETÉ' }), J + 30 * DAY)).toBe(false);
  });

  it('REJETÉ marqué vu à J : présent à J + 6 j 23 h, absent à J + 7 j 1 h', () => {
    const seen = item({ state: 'REJETÉ', seen: true, seenAt: J });
    expect(isRetentionExpired(seen, J + 6 * DAY + 23 * HOUR)).toBe(false);
    expect(isRetentionExpired(seen, J + 7 * DAY + HOUR)).toBe(true);
  });

  it('EN_ATTENTE et EN_COURS : présents à + 30 jours', () => {
    expect(isRetentionExpired(item({ state: 'EN_ATTENTE' }), J + 30 * DAY)).toBe(false);
    expect(isRetentionExpired(item({ state: 'EN_COURS' }), J + 30 * DAY)).toBe(false);
  });
});
