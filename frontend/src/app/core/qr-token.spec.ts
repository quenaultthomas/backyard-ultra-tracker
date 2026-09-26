import { describe, expect, it } from 'vitest';
import { normalizeQrToken, tokenSuffix } from './qr-token';

describe('CA12 - validation locale du QR (RG16)', () => {
  it('UUID avec espaces et majuscules : accepté et normalisé', () => {
    expect(normalizeQrToken(' 3F2C9A4E-8B1D-4C7E-9F00-1A2B3C4D5E6F '))
      .toBe('3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f');
  });

  it.each([
    'https://exemple.fr/r/3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f',
    '',
    '3f2c9a4e8b1d4c7e9f001a2b3c4d5e6f',
    'bonjour',
    '3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6g',
  ])('« %s » : refusé', (content) => {
    expect(normalizeQrToken(content)).toBeNull();
  });

  it('4 derniers caractères du token', () => {
    expect(tokenSuffix('3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f')).toBe('5e6f');
  });
});
