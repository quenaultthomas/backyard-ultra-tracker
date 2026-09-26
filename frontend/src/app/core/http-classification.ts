import { ApiProblem, FieldError } from './api.types';

/**
 * Classification commune des réponses de l'API (RG3, RG24), utilisée par tous les écrans.
 * Fonctions pures : l'accès réseau est fait ailleurs (infra), qui produit un {@link RawHttpResult}.
 */

export type ResponseClass = 'SUCCESS' | 'AUTH' | 'ROLE' | 'TRANSIENT' | 'DEFINITIVE';

/** Résultat brut d'un appel HTTP, avant classification. */
export type RawHttpResult =
  | { readonly kind: 'response'; readonly status: number; readonly json: boolean; readonly body: unknown }
  | { readonly kind: 'network-error' }
  | { readonly kind: 'timeout' };

/** Cause technique d'un échec sans réponse exploitable. */
export type TransportFailure = 'network' | 'timeout' | 'non-json';

export interface ClassifiedResult<T> {
  readonly responseClass: ResponseClass;
  readonly status: number | null;
  readonly body: T | null;
  readonly problem: ApiProblem | null;
  readonly failure: TransportFailure | null;
}

export const DATA_INTEGRITY_CODE = 'DATA_INTEGRITY';
export const BUSINESS_CONFLICT_CODE = 'BUSINESS_CONFLICT';
export const VALIDATION_FAILED_CODE = 'VALIDATION_FAILED';

/** Délais maximaux par écran (RG3). */
export const SCAN_TIMEOUT_MS = 10_000;
export const BOARD_TIMEOUT_MS = 5_000;
export const ADMIN_TIMEOUT_MS = 15_000;

export const SERVER_UNREACHABLE = 'Serveur injoignable';

/** Classe d'une réponse HTTP reçue (RG3). Une réponse non JSON est toujours TRANSITOIRE. */
export function classifyResponse(status: number, json: boolean, code: string | null): ResponseClass {
  if (!json) {
    return 'TRANSIENT';
  }
  if (status >= 200 && status < 300) {
    return 'SUCCESS';
  }
  if (status === 401) {
    return 'AUTH';
  }
  if (status === 403) {
    return 'ROLE';
  }
  if (status === 409 && code === DATA_INTEGRITY_CODE) {
    return 'TRANSIENT';
  }
  if (status >= 400 && status < 500) {
    return 'DEFINITIVE';
  }
  return 'TRANSIENT';
}

/** Classification complète d'un résultat brut, avec lecture du ProblemDetail éventuel. */
export function classify<T>(raw: RawHttpResult): ClassifiedResult<T> {
  if (raw.kind === 'network-error') {
    return failed('network');
  }
  if (raw.kind === 'timeout') {
    return failed('timeout');
  }
  const problem = raw.json && !isSuccess(raw.status) ? readProblem(raw.status, raw.body) : null;
  const responseClass = classifyResponse(raw.status, raw.json, problem?.code ?? null);
  return {
    responseClass,
    status: raw.status,
    body: responseClass === 'SUCCESS' ? (raw.body as T) : null,
    problem,
    failure: raw.json ? null : 'non-json',
  };
}

/** Lecture tolérante d'un corps ProblemDetail (RG5 inc. 3). */
export function readProblem(status: number, body: unknown): ApiProblem {
  const record = isRecord(body) ? body : {};
  return {
    status,
    code: typeof record['code'] === 'string' ? record['code'] : null,
    detail: typeof record['detail'] === 'string' ? record['detail'] : null,
    errors: readFieldErrors(record['errors']),
  };
}

/**
 * Message d'erreur affichable (RG3) : le {@code detail} du serveur tel quel, ou « Serveur injoignable » pour
 * une absence de réponse ou une réponse non JSON. Jamais de trace technique.
 */
export function errorMessage(result: ClassifiedResult<unknown>): string {
  if (result.failure !== null) {
    return SERVER_UNREACHABLE;
  }
  if (result.problem?.detail) {
    return result.problem.detail;
  }
  return result.status !== null && result.status >= 500 ? SERVER_UNREACHABLE : `Erreur ${result.status ?? ''}`.trim();
}

/** Erreurs de validation du serveur indexées par champ (RG3, 400 VALIDATION_FAILED). */
export function fieldErrors(result: ClassifiedResult<unknown>): ReadonlyMap<string, string> {
  const errors = new Map<string, string>();
  for (const error of result.problem?.errors ?? []) {
    const previous = errors.get(error.field);
    errors.set(error.field, previous ? `${previous} ; ${error.message}` : error.message);
  }
  return errors;
}

export function hasCode(result: ClassifiedResult<unknown>, code: string): boolean {
  return result.problem?.code === code;
}

/** Échec sans réponse exploitable du serveur : réseau, délai, non JSON, ou 5xx. */
export function isServerUnreachable(result: ClassifiedResult<unknown>): boolean {
  return result.failure !== null || (result.status !== null && result.status >= 500);
}

function failed<T>(failure: TransportFailure): ClassifiedResult<T> {
  return { responseClass: 'TRANSIENT', status: null, body: null, problem: null, failure };
}

function isSuccess(status: number): boolean {
  return status >= 200 && status < 300;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function readFieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter(isRecord)
    .filter((entry) => typeof entry['field'] === 'string' && typeof entry['message'] === 'string')
    .map((entry) => ({ field: entry['field'] as string, message: entry['message'] as string }));
}
