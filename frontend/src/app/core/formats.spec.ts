import { describe, expect, it } from 'vitest';
import {
  formatClockDuration,
  formatDistance,
  formatElevation,
  formatLoopDuration,
  formatLoopTime,
  formatPace,
  formatRaceDate,
  formatTimeOfDay,
  parseInstant,
  passageSourceLabel,
  raceStatusLabel,
  runnerStatusLabel,
  runnerStatusShortLabel,
  toIsoMillis,
} from './formats';

describe('CA8 - formats d\'affichage (RG2)', () => {
  it.each([
    [0, '0,00 km'],
    [1000, '1,00 km'],
    [6706, '6,71 km'],
    [13412, '13,41 km'],
    [6705, '6,71 km'],
    [6704, '6,70 km'],
    [5, '0,01 km'],
    [4, '0,00 km'],
    [100000, '100,00 km'],
  ])('distance %i m -> %s (HALF_UP, virgule)', (meters, expected) => {
    expect(formatDistance(meters)).toBe(expected);
  });

  it.each([
    [425, '7:05 /km'],
    [403, '6:43 /km'],
    [5, '0:05 /km'],
    [null, '—'],
  ])('allure %s -> %s', (seconds, expected) => {
    expect(formatPace(seconds)).toBe(expected);
  });

  it.each([
    [2_700_000, '45:00'],
    [3_725_000, '1:02:05'],
    [999, '0:00'],
    [null, '—'],
  ])('temps de boucle %s ms -> %s (tronqué à la seconde)', (millis, expected) => {
    expect(formatLoopTime(millis)).toBe(expected);
  });

  it.each([
    [3600, '1:00:00'],
    [30, '0:00:30'],
    [5400, '1:30:00'],
  ])('durée de boucle %i s -> %s', (seconds, expected) => {
    expect(formatLoopDuration(seconds)).toBe(expected);
  });

  it('durée d\'horloge : m:ss sous une heure, h:mm:ss au-delà', () => {
    expect(formatClockDuration(59)).toBe('0:59');
    expect(formatClockDuration(3599)).toBe('59:59');
    expect(formatClockDuration(3600)).toBe('1:00:00');
  });

  it('D+ : 100 -> « 100 m D+ »', () => {
    expect(formatElevation(100)).toBe('100 m D+');
    expect(formatElevation(0)).toBe('0 m D+');
  });

  it('statut coureur : DNF / TIMEOUT / 3 -> « DNF hors délai au yard 3 », WINNER -> « Vainqueur »', () => {
    expect(runnerStatusLabel('DNF', 'TIMEOUT', 3)).toBe('DNF hors délai au yard 3');
    expect(runnerStatusLabel('WINNER', null, null)).toBe('Vainqueur');
    expect(runnerStatusLabel('ACTIVE', null, null)).toBe('En course');
    expect(runnerStatusLabel('DNF', 'VOLUNTARY', 1)).toBe('DNF abandon volontaire au yard 1');
    expect(runnerStatusLabel('DNF', 'MANUAL', 2)).toBe("DNF décision de l'organisateur au yard 2");
    expect(runnerStatusLabel('DNF', 'OTHER', 4)).toBe('DNF autre au yard 4');
    expect(runnerStatusLabel('DNF', null, null)).toBe('DNF');
    expect(runnerStatusShortLabel('DNF')).toBe('DNF');
  });

  it('statut course et source de passage', () => {
    expect(raceStatusLabel('SETUP')).toBe('Non démarrée');
    expect(raceStatusLabel('RUNNING')).toBe('En cours');
    expect(raceStatusLabel('FINISHED')).toBe('Terminée');
    expect(passageSourceLabel('SCAN')).toBe('scan');
    expect(passageSourceLabel('MANUAL')).toBe('corrigé');
  });

  it('date de course : aaaa-mm-jj -> jj/mm/aaaa', () => {
    expect(formatRaceDate('2026-10-03')).toBe('03/10/2026');
  });

  it('instant -> HH:mm:ss dans l\'heure locale de l\'appareil ; null -> « — »', () => {
    const local = new Date(2026, 9, 3, 9, 5, 7, 450);
    expect(formatTimeOfDay(local.toISOString())).toBe('09:05:07');
    expect(formatTimeOfDay(local.getTime())).toBe('09:05:07');
    expect(formatTimeOfDay(null)).toBe('—');
  });

  it('instant ISO : fraction de 0 à 9 chiffres tronquée à la milliseconde, erreur explicite sinon', () => {
    expect(parseInstant('2026-10-03T10:20:00Z')).toBe(Date.UTC(2026, 9, 3, 10, 20, 0));
    expect(parseInstant('2026-10-03T10:20:00.5Z')).toBe(Date.UTC(2026, 9, 3, 10, 20, 0, 500));
    expect(parseInstant('2026-10-03T10:20:00.123456789Z')).toBe(Date.UTC(2026, 9, 3, 10, 20, 0, 123));
    expect(() => parseInstant('pas une date')).toThrow('Instant illisible');
  });

  it('sérialisation ISO-8601 UTC à la milliseconde (RG19)', () => {
    expect(toIsoMillis(Date.UTC(2026, 9, 3, 10, 1, 5, 123))).toBe('2026-10-03T10:01:05.123Z');
    expect(toIsoMillis(Date.UTC(2026, 9, 3, 10, 1, 5, 0))).toBe('2026-10-03T10:01:05.000Z');
  });
});
