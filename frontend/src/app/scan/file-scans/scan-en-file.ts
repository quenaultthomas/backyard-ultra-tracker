import { tirerIdentifiantScan } from '../identifiant-scan';
import { RefusScan } from '../refus-scan';

/** Session de la page courante : la mesure monotone ne vaut qu'à l'intérieur d'une même session. */
const SESSION_PAGE = tirerIdentifiantScan();

/**
 * `EN_ENVOI` : première tentative en cours (non comptée « en attente ») ;
 * `EN_ATTENTE` : gardé sur l'appareil après une panne, hors ligne ou derrière un scan plus ancien.
 */
export type EtatScanEnFile = 'EN_ENVOI' | 'EN_ATTENTE';

/**
 * Scan gardé dans la file locale tant qu'il n'a pas reçu de réponse définitive du serveur.
 * Le jeton n'existe que dans cette entrée : il disparaît avec elle.
 */
export interface ScanEnFile {
  /** Clé de l'entrée, envoyée telle quelle à chaque tentative (idempotence de l'API). */
  identifiantScan: string;
  compteId: string;
  courseId: string;
  jetonQr: string;
  /** Ordre de création : envoi du plus ancien au plus récent. */
  sequence: number;
  /** `Date.now()` à la création : sert seulement à mesurer un écart, jamais envoyé. */
  creeLeMural: number;
  /** `performance.now()` à la création, valable dans `sessionPage` seulement. */
  creeLeMonotone: number;
  sessionPage: string;
  etat: EtatScanEnFile;
  tentatives: number;
}

/**
 * Scan en attente refusé définitivement : gardé sur l'appareil, sans jeton,
 * jusqu'à « Compris ». Champs du refus tels que fournis par l'API (jamais déduits).
 */
export interface ScanAVerifier {
  identifiantScan: string;
  compteId: string;
  courseId: string;
  sequence: number;
  etat: 'A_VERIFIER';
  /** `code` du `ProblemDetail` ; absent pour un 4xx sans corps. */
  code?: string;
  motif: string;
  detail: string;
  dossard?: number;
  pseudo?: string;
  numeroBoucle?: number;
}

/** Entrée de la base locale : scan à envoyer, ou scan à vérifier. */
export type EntreeFile = ScanEnFile | ScanAVerifier;

export function estAVerifier(entree: EntreeFile): entree is ScanAVerifier {
  return entree.etat === 'A_VERIFIER';
}

/** Remplace l'entrée par sa version « à vérifier » : le jeton et les mesures disparaissent. */
export function mettreAVerifier(scan: ScanEnFile, refus: RefusScan): ScanAVerifier {
  const { identifiantScan, compteId, courseId, sequence } = scan;
  return {
    identifiantScan,
    compteId,
    courseId,
    sequence,
    etat: 'A_VERIFIER',
    motif: refus.motif,
    detail: refus.detail,
    ...(refus.code !== undefined && { code: refus.code }),
    ...(refus.coureur && { dossard: refus.coureur.dossard, pseudo: refus.coureur.pseudo }),
    ...(refus.numeroBoucle !== undefined && { numeroBoucle: refus.numeroBoucle }),
  };
}

let derniereSequence = 0;

export function creerScanEnFile(
  compteId: string,
  courseId: string,
  jetonQr: string,
  etat: EtatScanEnFile,
): ScanEnFile {
  const maintenant = Date.now();
  derniereSequence = Math.max(derniereSequence + 1, maintenant);
  return {
    identifiantScan: tirerIdentifiantScan(),
    compteId,
    courseId,
    jetonQr,
    sequence: derniereSequence,
    creeLeMural: maintenant,
    creeLeMonotone: performance.now(),
    sessionPage: SESSION_PAGE,
    etat,
    tentatives: 0,
  };
}

/**
 * Âge du scan au moment de l'envoi : le plus grand de l'écart monotone (même session de page)
 * et de l'écart d'horloge murale, pour couvrir la mise en veille et le rechargement.
 */
export function ageDuScan(scan: ScanEnFile): number {
  const ecartMonotone = estDeCetteSessionDePage(scan) ? performance.now() - scan.creeLeMonotone : 0;
  const ecartMural = Date.now() - scan.creeLeMural;
  return Math.max(0, Math.round(Math.max(ecartMonotone, ecartMural)));
}

/** Faux pour une entrée créée avant un rechargement, ou par un autre onglet. */
export function estDeCetteSessionDePage(scan: ScanEnFile): boolean {
  return scan.sessionPage === SESSION_PAGE;
}
