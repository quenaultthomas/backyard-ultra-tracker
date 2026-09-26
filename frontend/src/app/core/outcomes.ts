import {
  BUSINESS_CONFLICT_CODE,
  ClassifiedResult,
  DATA_INTEGRITY_CODE,
  errorMessage,
  hasCode,
  isServerUnreachable,
  VALIDATION_FAILED_CODE,
} from './http-classification';

/**
 * Suites à donner aux réponses de l'inscription (RG13) et des actions admin (RG10, RG43).
 */

export const REGISTRATION_RETRY_DELAY_MS = 1_000;

export type RegistrationDecision =
  | 'CONFIRMED'
  | 'RETRY_AUTOMATICALLY'
  | 'FIELD_ERRORS'
  | 'CLOSED'
  | 'NOT_FOUND'
  | 'UNREACHABLE'
  | 'FAILED_WITH_RETRY'
  | 'FAILED';

/**
 * RG13 : un seul nouvel essai automatique sur 409 DATA_INTEGRITY ; aucun sur une erreur transitoire, car une
 * inscription n'est pas idempotente. `attempt` vaut 1 pour le premier envoi.
 */
export function registrationDecision(result: ClassifiedResult<unknown>, attempt: number): RegistrationDecision {
  if (result.responseClass === 'SUCCESS') {
    return 'CONFIRMED';
  }
  if (hasCode(result, DATA_INTEGRITY_CODE)) {
    return attempt === 1 ? 'RETRY_AUTOMATICALLY' : 'FAILED_WITH_RETRY';
  }
  if (hasCode(result, VALIDATION_FAILED_CODE)) {
    return 'FIELD_ERRORS';
  }
  if (hasCode(result, BUSINESS_CONFLICT_CODE)) {
    return 'CLOSED';
  }
  if (result.status === 404 || result.status === 400) {
    return 'NOT_FOUND';
  }
  return isServerUnreachable(result) ? 'UNREACHABLE' : 'FAILED';
}

export const OFFLINE_NO_DATA = 'Hors ligne : données indisponibles';

/** RG45 : échec d'un chargement ; hors ligne, « Hors ligne : données indisponibles ». */
export function loadFailureMessage(result: ClassifiedResult<unknown>, online: boolean): string {
  return !online && isServerUnreachable(result) ? OFFLINE_NO_DATA : errorMessage(result);
}

export const ADMIN_ACTION_UNREACHABLE =
  "Action impossible : serveur injoignable. Rien n'a été modifié côté application. Vérifiez l'état après reconnexion.";

/** RG43 : message d'échec d'une action admin, jamais rejouée automatiquement. */
export function adminFailureMessage(result: ClassifiedResult<unknown>): string {
  return isServerUnreachable(result) ? ADMIN_ACTION_UNREACHABLE : errorMessage(result);
}

/**
 * RG38, RG41, RG42 : après un refus du serveur (données périmées, ressource disparue…), la liste est rechargée
 * depuis l'API. Pas de rechargement sans réponse exploitable du serveur, ni sur 401/403.
 */
export function shouldReloadAfterFailure(result: ClassifiedResult<unknown>): boolean {
  return result.responseClass === 'DEFINITIVE' || hasCode(result, DATA_INTEGRITY_CODE);
}
