import {
  BUSINESS_CONFLICT_CODE,
  ClassifiedResult,
  DATA_INTEGRITY_CODE,
  errorMessage,
  hasCode,
  isServerUnreachable,
  isTooManyAttempts,
  TOO_MANY_ATTEMPTS,
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
  | 'TOO_MANY_ATTEMPTS'
  | 'FAILED';

/**
 * RG13 : un seul nouvel essai automatique sur 409 DATA_INTEGRITY ; aucun sur une erreur transitoire, car une
 * inscription n'est pas idempotente. `attempt` vaut 1 pour le premier envoi.
 */
export function registrationDecision(result: ClassifiedResult<unknown>, attempt: number): RegistrationDecision {
  if (result.responseClass === 'SUCCESS') {
    return 'CONFIRMED';
  }
  if (isTooManyAttempts(result)) {
    return 'TOO_MANY_ATTEMPTS';
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

export const INVALID_CREDENTIALS = 'Identifiants invalides';
export const RUNNER_LOGIN_UNREACHABLE = 'Connexion impossible : serveur injoignable';

/**
 * RG21 inc. 5 : message de la connexion coureur par E21, ou null si elle a réussi. 401 : identifiants invalides ;
 * 429 : message de RG23 ; absence de réponse exploitable : serveur injoignable ; sinon le detail du serveur.
 */
export function runnerLoginFailureMessage(result: ClassifiedResult<unknown>): string | null {
  if (result.responseClass === 'SUCCESS') {
    return null;
  }
  if (isTooManyAttempts(result)) {
    return TOO_MANY_ATTEMPTS;
  }
  if (result.responseClass === 'AUTH') {
    return INVALID_CREDENTIALS;
  }
  return isServerUnreachable(result) ? RUNNER_LOGIN_UNREACHABLE : errorMessage(result);
}

/** RG17 inc. 5 : texte de confirmation de la suppression d'un compte coureur par l'admin. */
export function accountDeletionConfirmation(pseudo: string, registrationCount: number): string {
  return `Supprimer le compte ${pseudo} ? Ses ${registrationCount} inscriptions et leurs passages sont conservés, `
    + 'sans compte. Action irréversible.';
}

/**
 * RG17 inc. 5 (correction D1) : après un 409 sur E3, le lien « J'ai déjà un compte » n'est proposé que si la course
 * relue accepte encore les inscriptions (SETUP) et qu'aucun coureur n'est connecté. Course non relue : pas de lien.
 */
export function showAccountLinkAfterConflict(rereadRace: { readonly registrationOpen: boolean } | null,
                                             runnerConnected: boolean): boolean {
  return rereadRace !== null && rereadRace.registrationOpen && !runnerConnected;
}
