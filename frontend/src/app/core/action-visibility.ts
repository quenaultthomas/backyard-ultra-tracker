import { RaceStatus, RunnerStatus } from './api.types';

/**
 * Table unique des actions admin proposées selon le statut (RG38), définie ici seulement. Aide ergonomique :
 * le serveur reste l'autorité, un 409 éventuel est affiché puis suivi d'un rechargement.
 */

export type RaceAction = 'EDIT_ALL_FIELDS' | 'EDIT_NAME_AND_DATE' | 'DELETE' | 'START' | 'PRINT_QR';
export type RunnerAction = 'EDIT_BIB_AND_NAME' | 'EDIT_NAME' | 'DELETE' | 'SHOW_QR' | 'DECLARE_DNF' | 'REINTEGRATE';

interface StatusActions {
  readonly race: readonly RaceAction[];
  readonly runner: Readonly<Record<RunnerStatus, readonly RunnerAction[]>>;
}

const ACTIONS_BY_RACE_STATUS: Readonly<Record<RaceStatus, StatusActions>> = {
  SETUP: {
    race: ['EDIT_ALL_FIELDS', 'DELETE', 'START', 'PRINT_QR'],
    runner: {
      ACTIVE: ['EDIT_BIB_AND_NAME', 'DELETE', 'SHOW_QR'],
      DNF: [],
      WINNER: [],
    },
  },
  RUNNING: {
    race: ['EDIT_NAME_AND_DATE', 'PRINT_QR'],
    runner: {
      ACTIVE: ['EDIT_NAME', 'SHOW_QR', 'DECLARE_DNF'],
      DNF: ['EDIT_NAME', 'SHOW_QR', 'REINTEGRATE'],
      WINNER: [],
    },
  },
  FINISHED: {
    race: ['EDIT_NAME_AND_DATE', 'PRINT_QR'],
    runner: {
      ACTIVE: ['EDIT_NAME', 'SHOW_QR'],
      DNF: ['EDIT_NAME', 'SHOW_QR'],
      WINNER: ['EDIT_NAME', 'SHOW_QR'],
    },
  },
};

export function raceActions(raceStatus: RaceStatus): ReadonlySet<RaceAction> {
  return new Set(ACTIONS_BY_RACE_STATUS[raceStatus].race);
}

export function runnerActions(raceStatus: RaceStatus, runnerStatus: RunnerStatus): ReadonlySet<RunnerAction> {
  return new Set(ACTIONS_BY_RACE_STATUS[raceStatus].runner[runnerStatus]);
}
