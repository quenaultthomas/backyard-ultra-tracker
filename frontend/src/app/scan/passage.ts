import { ProblemDetail } from '../partage/probleme';

/** Corps de `POST /api/benevole/courses/{courseId}/passages` (scan en direct : sans âge du scan). */
export interface PassageRequete {
  /** Jeton tel que saisi : le nettoyage des espaces est fait par l'API. */
  jetonQr: string;
  /** UUID v4 tiré par saisie, conservé pour une nouvelle tentative du même jeton. */
  identifiantScan: string;
}

/** Passage renvoyé en 201 (créé) ou 200 (rejeu du même `identifiantScan`). */
export interface PassageReponse {
  passageId: string;
  inscriptionId: string;
  dossard: number;
  /** Pseudo du coureur, ou « Coureur anonyme » / « Compte inconnu » : affiché en texte. */
  pseudo: string;
  numeroBoucle: number;
  /** Instant ISO-8601 UTC avec millisecondes. */
  scanneLe: string;
  origine: 'SCAN';
}

/** `ProblemDetail` d'un refus de scan, avec les extensions présentes sur certains 409. */
export interface ErreurScan extends ProblemDetail {
  dossard?: number;
  pseudo?: string;
  numeroBoucle?: number;
  statut?: 'ABANDON' | 'VAINQUEUR';
  /** `PASSAGE_TROP_RAPPROCHE` : délai minimal entre deux Passages, en secondes. */
  delaiMinimalSecondes?: number;
  /** `PASSAGE_TROP_RAPPROCHE` : secondes à attendre avant un nouveau Passage (au moins 1). */
  secondesRestantes?: number;
}
