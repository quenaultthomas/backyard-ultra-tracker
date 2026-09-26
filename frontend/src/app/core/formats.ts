import { DnfReason, PassageSource, RaceStatus, RunnerStatus } from './api.types';

/**
 * Formats d'affichage en français (RG2). Formatage seulement : les valeurs viennent de l'API (RG1).
 */

export const MISSING_VALUE = '—';

/** Distance en mètres → km, arrondi HALF_UP à 2 décimales, virgule décimale : 6705 → « 6,71 km ». */
export function formatDistance(distanceMeters: number): string {
  const hundredthsOfKm = Math.floor((distanceMeters + 5) / 10);
  return `${Math.floor(hundredthsOfKm / 100)},${pad2(hundredthsOfKm % 100)} km`;
}

/** D+ : 100 → « 100 m D+ ». */
export function formatElevation(elevationMeters: number): string {
  return `${elevationMeters} m D+`;
}

/** Allure en s/km → « m:ss /km » ; null → « — ». */
export function formatPace(secondsPerKm: number | null): string {
  if (secondsPerKm === null) {
    return MISSING_VALUE;
  }
  return `${Math.floor(secondsPerKm / 60)}:${pad2(secondsPerKm % 60)} /km`;
}

/** Temps de boucle en ms, tronqué à la seconde : « m:ss » sous une heure, sinon « h:mm:ss » ; null → « — ». */
export function formatLoopTime(loopTimeMillis: number | null): string {
  if (loopTimeMillis === null) {
    return MISSING_VALUE;
  }
  return formatClockDuration(Math.floor(loopTimeMillis / 1000));
}

/** Durée en secondes : « m:ss » sous une heure, sinon « h:mm:ss ». */
export function formatClockDuration(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0 ? `${hours}:${pad2(minutes)}:${pad2(seconds)}` : `${minutes}:${pad2(seconds)}`;
}

/** Durée de boucle d'une course, toujours « h:mm:ss » : 3600 → « 1:00:00 », 30 → « 0:00:30 ». */
export function formatLoopDuration(loopDurationSeconds: number): string {
  const hours = Math.floor(loopDurationSeconds / 3600);
  const minutes = Math.floor((loopDurationSeconds % 3600) / 60);
  return `${hours}:${pad2(minutes)}:${pad2(loopDurationSeconds % 60)}`;
}

/** Instant ISO-8601 → heure locale de l'appareil « HH:mm:ss » ; null → « — ». */
export function formatTimeOfDay(instant: string | number | null): string {
  if (instant === null) {
    return MISSING_VALUE;
  }
  const date = new Date(typeof instant === 'number' ? instant : parseInstant(instant));
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

/** Date ISO « yyyy-MM-dd » → « dd/MM/yyyy ». */
export function formatRaceDate(raceDate: string): string {
  const [year, month, day] = raceDate.split('-');
  return `${day}/${month}/${year}`;
}

const RACE_STATUS_LABELS: Readonly<Record<RaceStatus, string>> = {
  SETUP: 'Non démarrée',
  RUNNING: 'En cours',
  FINISHED: 'Terminée',
};

const RUNNER_STATUS_LABELS: Readonly<Record<RunnerStatus, string>> = {
  ACTIVE: 'En course',
  WINNER: 'Vainqueur',
  DNF: 'DNF',
};

const DNF_REASON_LABELS: Readonly<Record<DnfReason, string>> = {
  VOLUNTARY: 'abandon volontaire',
  TIMEOUT: 'hors délai',
  MANUAL: "décision de l'organisateur",
  OTHER: 'autre',
};

const PASSAGE_SOURCE_LABELS: Readonly<Record<PassageSource, string>> = {
  SCAN: 'scan',
  MANUAL: 'corrigé',
};

export function raceStatusLabel(status: RaceStatus): string {
  return RACE_STATUS_LABELS[status];
}

/** Statut court d'un coureur, sans raison ni yard. */
export function runnerStatusShortLabel(status: RunnerStatus): string {
  return RUNNER_STATUS_LABELS[status];
}

export function dnfReasonLabel(reason: DnfReason): string {
  return DNF_REASON_LABELS[reason];
}

/** Statut complet : « En course », « Vainqueur », « DNF hors délai au yard 3 ». */
export function runnerStatusLabel(status: RunnerStatus, reason: DnfReason | null, dnfYard: number | null): string {
  if (status !== 'DNF') {
    return RUNNER_STATUS_LABELS[status];
  }
  const parts = ['DNF'];
  if (reason !== null) {
    parts.push(DNF_REASON_LABELS[reason]);
  }
  if (dnfYard !== null) {
    parts.push(`au yard ${dnfYard}`);
  }
  return parts.join(' ');
}

export function passageSourceLabel(source: PassageSource): string {
  return PASSAGE_SOURCE_LABELS[source];
}

/**
 * Instant ISO-8601 → millisecondes epoch. Accepte une fraction de seconde de 0 à 9 chiffres (sérialisation
 * d'un {@code Instant} Java), tronquée à la milliseconde.
 */
export function parseInstant(instant: string): number {
  const normalized = instant.replace(/\.(\d{1,9})(?=Z|[+-]\d{2}:?\d{2}$)/, (_match, fraction: string) =>
    `.${fraction.padEnd(3, '0').substring(0, 3)}`,
  );
  const epochMillis = Date.parse(normalized);
  if (Number.isNaN(epochMillis)) {
    throw new RangeError(`Instant illisible : ${instant}`);
  }
  return epochMillis;
}

/** Millisecondes epoch → ISO-8601 UTC à la milliseconde « yyyy-MM-ddTHH:mm:ss.SSSZ » (RG19). */
export function toIsoMillis(epochMillis: number): string {
  return new Date(epochMillis).toISOString();
}

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}
