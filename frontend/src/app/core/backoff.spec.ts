import { describe, expect, it } from 'vitest';
import { backoffDelayMs, baseBackoffSeconds } from './backoff';

describe('CA10 - backoff (RG22)', () => {
  it('délais de base pour n = 1 à 8 échecs : 1, 2, 4, 8, 16, 32, 60, 60 s', () => {
    expect([1, 2, 3, 4, 5, 6, 7, 8].map(baseBackoffSeconds)).toEqual([1, 2, 4, 8, 16, 32, 60, 60]);
    expect(baseBackoffSeconds(2000)).toBe(60);
  });

  it('sur 1 000 tirages, chaque délai effectif est entre la base et 1,2 fois la base', () => {
    for (let n = 1; n <= 8; n += 1) {
      const base = baseBackoffSeconds(n) * 1000;
      for (let draw = 0; draw < 1000; draw += 1) {
        const delay = backoffDelayMs(n, Math.random);
        expect(delay).toBeGreaterThanOrEqual(base);
        expect(delay).toBeLessThanOrEqual(base * 1.2);
      }
    }
  });

  it('bornes du facteur aléatoire : 1,0 et 1,2 exactement, tirage hors bornes ramené dans [0 ; 1]', () => {
    expect(backoffDelayMs(3, () => 0)).toBe(4000);
    expect(backoffDelayMs(3, () => 1)).toBe(4800);
    expect(backoffDelayMs(3, () => 7)).toBe(4800);
    expect(backoffDelayMs(3, () => -1)).toBe(4000);
  });
});
