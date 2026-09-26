import { describe, expect, it } from 'vitest';
import { isoDate, nonNegativeInteger, parseInteger, positiveInteger, requiredText } from './validation';
import { API_PATHS, requiresAuthorization } from './api-paths';

describe('RG1 - validation de format, miroir des contraintes de l\'API', () => {
  it('texte obligatoire, 255 caractères au plus après suppression des espaces', () => {
    expect(requiredText('   ')).toBe('Champ obligatoire');
    expect(requiredText('')).toBe('Champ obligatoire');
    expect(requiredText(' Alice ')).toBeNull();
    expect(requiredText('a'.repeat(255))).toBeNull();
    expect(requiredText('a'.repeat(256))).toBe('255 caractères maximum');
  });

  it('entier > 0 et entier >= 0', () => {
    expect(positiveInteger('0')).not.toBeNull();
    expect(positiveInteger('1')).toBeNull();
    expect(positiveInteger('1.5')).not.toBeNull();
    expect(positiveInteger('abc')).not.toBeNull();
    expect(nonNegativeInteger('0')).toBeNull();
    expect(nonNegativeInteger('-1')).not.toBeNull();
    expect(parseInteger('2147483648')).toBeNull();
    expect(parseInteger(' -12 ')).toBe(-12);
  });

  it('date ISO', () => {
    expect(isoDate('2026-10-03')).toBeNull();
    expect(isoDate('')).toBe('Date attendue');
    expect(isoDate('2026-13-45')).toBe('Date attendue');
  });
});

describe('RG8 - en-tête Authorization', () => {
  it('uniquement vers /api/scan/** et /api/admin/**', () => {
    expect(requiresAuthorization(API_PATHS.scan)).toBe(true);
    expect(requiresAuthorization(API_PATHS.session)).toBe(true);
    expect(requiresAuthorization(API_PATHS.adminRaces)).toBe(true);
    expect(requiresAuthorization(API_PATHS.adminDnf(7))).toBe(true);
    expect(requiresAuthorization(API_PATHS.board(1))).toBe(false);
    expect(requiresAuthorization(API_PATHS.registrations(1))).toBe(false);
    expect(requiresAuthorization(API_PATHS.runner(1))).toBe(false);
    expect(requiresAuthorization('/api/scanner')).toBe(false);
    expect(requiresAuthorization('https://autre.example/api/admin/races')).toBe(false);
    expect(requiresAuthorization('//autre.example/api/admin/races')).toBe(false);
  });

  it('chemins encodés', () => {
    expect(API_PATHS.race('a/b')).toBe('/api/public/races/a%2Fb');
    expect(API_PATHS.adminReintegration(3)).toBe('/api/admin/runners/3/reintegration');
    expect(API_PATHS.adminStart(3)).toBe('/api/admin/races/3/start');
    expect(API_PATHS.adminRace(3)).toBe('/api/admin/races/3');
    expect(API_PATHS.adminRaceRunners(3)).toBe('/api/admin/races/3/runners');
    expect(API_PATHS.adminRunner(3)).toBe('/api/admin/runners/3');
  });
});
