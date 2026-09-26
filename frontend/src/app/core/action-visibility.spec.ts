import { describe, expect, it } from 'vitest';
import { raceActions, runnerActions } from './action-visibility';

describe('CA20 - table de visibilité des actions (RG38)', () => {
  it.each([
    ['SETUP', ['EDIT_ALL_FIELDS', 'DELETE', 'START', 'PRINT_QR']],
    ['RUNNING', ['EDIT_NAME_AND_DATE', 'PRINT_QR']],
    ['FINISHED', ['EDIT_NAME_AND_DATE', 'PRINT_QR']],
  ] as const)('course %s : %j', (status, expected) => {
    expect([...raceActions(status)].sort()).toEqual([...expected].sort());
  });

  it.each([
    ['SETUP', 'ACTIVE', ['EDIT_BIB_AND_NAME', 'DELETE', 'SHOW_QR']],
    ['SETUP', 'DNF', []],
    ['SETUP', 'WINNER', []],
    ['RUNNING', 'ACTIVE', ['EDIT_NAME', 'SHOW_QR', 'DECLARE_DNF']],
    ['RUNNING', 'DNF', ['EDIT_NAME', 'SHOW_QR', 'REINTEGRATE']],
    ['RUNNING', 'WINNER', []],
    ['FINISHED', 'ACTIVE', ['EDIT_NAME', 'SHOW_QR']],
    ['FINISHED', 'DNF', ['EDIT_NAME', 'SHOW_QR']],
    ['FINISHED', 'WINNER', ['EDIT_NAME', 'SHOW_QR']],
  ] as const)('course %s × coureur %s : %j', (raceStatus, runnerStatus, expected) => {
    expect([...runnerActions(raceStatus, runnerStatus)].sort()).toEqual([...expected].sort());
  });

  it('DNF manuel uniquement pour un coureur ACTIVE d\'une course RUNNING, réintégration pour un DNF', () => {
    const statuses = ['SETUP', 'RUNNING', 'FINISHED'] as const;
    const runners = ['ACTIVE', 'DNF', 'WINNER'] as const;
    const dnf = statuses.flatMap((race) => runners.filter((runner) => runnerActions(race, runner).has('DECLARE_DNF'))
      .map((runner) => `${race}/${runner}`));
    const reintegrate = statuses.flatMap((race) => runners
      .filter((runner) => runnerActions(race, runner).has('REINTEGRATE')).map((runner) => `${race}/${runner}`));
    expect(dnf).toEqual(['RUNNING/ACTIVE']);
    expect(reintegrate).toEqual(['RUNNING/DNF']);
  });
});
