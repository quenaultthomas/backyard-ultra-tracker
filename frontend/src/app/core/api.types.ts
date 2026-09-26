/**
 * Types des réponses et requêtes de l'API (E1 à E19, docs/specs/increment3.md et increment4.md).
 * Types seuls : aucune logique. Toutes les valeurs dérivées (tours, distance, D+, allure, temps de boucle,
 * yard courant, fin de yard, badge « corrigé ») viennent de l'API et ne sont jamais recalculées (RG1).
 */

export type RaceStatus = 'SETUP' | 'RUNNING' | 'FINISHED';
export type RunnerStatus = 'ACTIVE' | 'DNF' | 'WINNER';
export type DnfReason = 'VOLUNTARY' | 'TIMEOUT' | 'MANUAL' | 'OTHER';
export type PassageSource = 'SCAN' | 'MANUAL';
export type Role = 'ADMIN' | 'SCANNER';

/** E1, E2, E8, E9, E7/E10/E12 (réponse). */
export interface RaceResponse {
  readonly id: number;
  readonly name: string;
  readonly raceDate: string;
  readonly status: RaceStatus;
  readonly startedAt: string | null;
  readonly loopDistance: number;
  readonly loopDuration: number;
  readonly loopElevation: number;
  readonly registrationOpen: boolean;
}

/** E7, E10. */
export interface RaceRequest {
  readonly name: string;
  readonly raceDate: string;
  readonly loopDistance: number;
  readonly loopDuration: number;
  readonly loopElevation: number;
}

/** Ligne de E4. */
export interface RunnerBoardEntry {
  readonly runnerId: number;
  readonly bib: number;
  readonly name: string;
  readonly status: RunnerStatus;
  readonly dnfReason: DnfReason | null;
  readonly dnfYard: number | null;
  readonly completedLoops: number;
  readonly distanceMeters: number;
  readonly elevationMeters: number;
  readonly averagePaceSecondsPerKm: number | null;
  readonly corrected: boolean;
}

/** E4. */
export interface RaceBoardResponse {
  readonly race: RaceResponse;
  readonly serverTime: string;
  readonly currentYard: number;
  readonly currentYardEndsAt: string | null;
  readonly runners: readonly RunnerBoardEntry[];
}

export interface PassageResponse {
  readonly yardNumber: number;
  readonly source: PassageSource;
  readonly scannedAt: string | null;
  readonly loopTimeMillis: number | null;
  readonly corrected: boolean;
}

/** E5. */
export interface RunnerDetailResponse extends RunnerBoardEntry {
  readonly raceId: number;
  readonly passages: readonly PassageResponse[];
}

/** E3. */
export interface RegistrationResponse {
  readonly runnerId: number;
  readonly raceId: number;
  readonly bib: number;
  readonly name: string;
  readonly qrToken: string;
}

/** E13, E14, E15, E17. */
export interface AdminRunnerResponse {
  readonly id: number;
  readonly raceId: number;
  readonly bib: number;
  readonly name: string;
  readonly qrToken: string;
  readonly status: RunnerStatus;
  readonly dnfReason: DnfReason | null;
  readonly dnfYard: number | null;
}

/** E18. */
export interface ReintegrationResponse {
  readonly runner: AdminRunnerResponse;
  readonly recreatedPassages: readonly PassageResponse[];
}

/** E6. */
export interface ScanResponse {
  readonly passageId: number;
  readonly runnerId: number;
  readonly bib: number;
  readonly runnerName: string;
  readonly runnerStatus: RunnerStatus;
  readonly yardNumber: number;
  readonly source: PassageSource;
  readonly scannedAt: string;
}

/** E19. */
export interface SessionResponse {
  readonly username: string;
  readonly role: Role;
  readonly serverTime: string;
}

export interface FieldError {
  readonly field: string;
  readonly message: string;
}

/** Corps d'erreur ProblemDetail (RG5 inc. 3). */
export interface ApiProblem {
  readonly status: number;
  readonly code: string | null;
  readonly detail: string | null;
  readonly errors: readonly FieldError[];
}
