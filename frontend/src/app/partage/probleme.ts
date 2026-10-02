import { HttpErrorResponse } from '@angular/common/http';

/** Erreur de validation d'un champ, renvoyée dans `erreurs` d'un 400 `VALIDATION_ECHOUEE`. */
export interface ErreurChamp {
  champ: string;
  code: string;
  message: string;
}

/** Corps `application/problem+json` des réponses d'erreur de l'API. */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  code?: string;
  erreurs?: ErreurChamp[];
}

/** Extrait le `ProblemDetail` d'une erreur HTTP, ou `null` si le corps n'en est pas un. */
export function lireProbleme(erreur: unknown): ProblemDetail | null {
  if (!(erreur instanceof HttpErrorResponse)) {
    return null;
  }
  const corps: unknown = erreur.error;
  return typeof corps === 'object' && corps !== null && 'status' in corps
    ? (corps as ProblemDetail)
    : null;
}
