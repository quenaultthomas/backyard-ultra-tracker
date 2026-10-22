import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';

import { lireProbleme } from '../../partage/probleme';
import { ErreurScan, PassageReponse } from '../passage';
import { RefusScan, lireRefusScan } from '../refus-scan';

/** Issue d'une tentative d'envoi, telle que la file la traite (spec 5.1a RG5). */
export type IssueEnvoi =
  | { type: 'succes'; passage: PassageReponse }
  | { type: 'refus'; refus: RefusScan }
  | { type: 'csrf-invalide' }
  | { type: 'session-expiree'; erreur: HttpErrorResponse }
  | { type: 'acces-refuse'; erreur: HttpErrorResponse }
  | { type: 'panne'; reessayerApresSecondes?: number };

const DETAIL_REFUS_GENERIQUE = 'Le serveur a refusé ce scan.';
const STATUTS_PANNE: readonly number[] = [
  HttpStatusCode.RequestTimeout,
  HttpStatusCode.TooManyRequests,
];

/** Classe une erreur d'envoi : tout ce qui n'est pas une réponse HTTP est une panne. */
export function classerErreur(erreur: unknown): IssueEnvoi {
  if (!(erreur instanceof HttpErrorResponse)) {
    return { type: 'panne' };
  }
  const statut = erreur.status;
  if (
    statut === 0 ||
    statut >= HttpStatusCode.InternalServerError ||
    STATUTS_PANNE.includes(statut)
  ) {
    return { type: 'panne', reessayerApresSecondes: lireRetryAfter(erreur) };
  }
  if (statut === HttpStatusCode.Unauthorized) {
    return { type: 'session-expiree', erreur };
  }
  const probleme = lireProbleme(erreur) as ErreurScan | null;
  if (statut === HttpStatusCode.Forbidden && probleme?.code === 'CSRF_INVALIDE') {
    return { type: 'csrf-invalide' };
  }
  if (statut === HttpStatusCode.Forbidden && probleme?.code !== 'BENEVOLE_NON_AFFECTE') {
    return { type: 'acces-refuse', erreur };
  }
  const detail = probleme?.detail || DETAIL_REFUS_GENERIQUE;
  return { type: 'refus', refus: lireRefusScan({ ...probleme, detail } as ErreurScan) };
}

/** `Retry-After` en secondes entières ; toute autre forme est ignorée. */
function lireRetryAfter(erreur: HttpErrorResponse): number | undefined {
  const valeur = erreur.headers?.get('Retry-After')?.trim();
  return valeur && /^\d+$/.test(valeur) ? Number(valeur) : undefined;
}

const ATTENTE_MAXIMALE_SECONDES = 30;
const RETRY_AFTER_MAXIMAL_SECONDES = 120;

/**
 * Attente avant la tentative suivante, pour la k-ième panne consécutive : min(2^k, 30) s à
 * ±20 %, ou `Retry-After` (plafonné à 120 s) s'il est plus long.
 */
export function attenteApresPanne(
  pannesConsecutives: number,
  reessayerApresSecondes?: number,
): number {
  const base = Math.min(2 ** pannesConsecutives, ATTENTE_MAXIMALE_SECONDES);
  const attente = base * (0.8 + Math.random() * 0.4);
  const imposee = Math.min(reessayerApresSecondes ?? 0, RETRY_AFTER_MAXIMAL_SECONDES);
  return Math.max(attente, imposee) * 1000;
}
