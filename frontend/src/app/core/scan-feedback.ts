import { BUSINESS_CONFLICT_CODE } from './http-classification';
import { runnerStatusShortLabel } from './formats';
import { CaptureResult, QueueEvent, QueueSnapshot } from './scan-queue';
import { ScanItem } from './scan-item';

/**
 * Retour multicanal du scan (RG50, RG24, RG26) : couleur, icône et texte, son, vibration, région aria-live.
 * Un résultat serveur arrivé plus de 5 s après sa capture ne déclenche ni son ni vibration et ne remplace pas
 * le dernier résultat affiché (PO16) : il ne met à jour que l'historique et les compteurs.
 */

export const LIVE_FEEDBACK_WINDOW_MS = 5_000;
export const UNREACHABLE_WINDOW_MS = 60_000;

export type FeedbackTone = 'info' | 'success' | 'error' | 'discreet';
export type FeedbackSound = 'capture' | 'success' | 'error';

export interface ScanFeedback {
  readonly tone: FeedbackTone;
  readonly icon: string;
  readonly text: string;
  readonly sound: FeedbackSound | null;
  readonly vibration: readonly number[] | null;
  /** Politesse de la région aria-live : `assertive` pour les rejets. */
  readonly live: 'polite' | 'assertive';
}

export const CAPTURE_VIBRATION: readonly number[] = [50];
export const SUCCESS_VIBRATION: readonly number[] = [50, 50, 50];
export const ERROR_VIBRATION: readonly number[] = [200, 100, 200];

export const QR_NOT_RECOGNIZED = 'QR non reconnu';
export const ALREADY_CAPTURED = 'déjà enregistré';
export const LOCAL_STORAGE_FAILED = "Échec de l'enregistrement local : notez le dossard";

/** Retour immédiat d'une capture (caméra ou saisie). */
export function captureFeedback(result: CaptureResult, willSendNow: boolean): ScanFeedback {
  switch (result.kind) {
    case 'invalid':
      return errorFeedback(QR_NOT_RECOGNIZED);
    case 'duplicate':
      return { tone: 'discreet', icon: '↺', text: ALREADY_CAPTURED, sound: null, vibration: null, live: 'polite' };
    case 'storage-failed':
      return errorFeedback(LOCAL_STORAGE_FAILED);
    case 'captured':
      return {
        tone: 'info',
        icon: '●',
        text: willSendNow ? 'Enregistré — envoi…' : 'Enregistré — en attente de réseau',
        sound: 'capture',
        vibration: CAPTURE_VIBRATION,
        live: 'polite',
      };
  }
}

/**
 * Retour d'une réponse du serveur ; null si l'événement ne change pas le dernier résultat affiché
 * (résultat différé de plus de 5 s, nouvel essai, simple changement d'état).
 */
export function serverFeedback(event: QueueEvent, now: number): ScanFeedback | null {
  if (event.type === 'suspended') {
    return event.suspension === 'ROLE'
      ? errorFeedbackSilent(`${event.item.lastError?.detail ?? 'Accès refusé'} — Connectez-vous avec le compte scanner`)
      : errorFeedbackSilent('Session expirée ou identifiants modifiés : reconnectez-vous');
  }
  if (event.type !== 'accepted' && event.type !== 'rejected') {
    return null;
  }
  if (now - event.item.capturedAt > LIVE_FEEDBACK_WINDOW_MS) {
    return null;
  }
  return event.type === 'accepted' ? acceptedFeedback(event.item) : errorFeedback(rejectionText(event.item));
}

/** « Dossard {bib} — {runnerName} — yard {yardNumber} », avec le statut si le coureur n'est pas en course. */
export function acceptedText(item: ScanItem): string {
  const response = item.response;
  if (response === null) {
    return 'Accepté';
  }
  const base = `Dossard ${response.bib} — ${response.runnerName} — yard ${response.yardNumber}`;
  return response.runnerStatus === 'ACTIVE' ? base : `${base} — ${runnerStatusShortLabel(response.runnerStatus)}`;
}

/** Texte d'un rejet (RG24) : `detail` du serveur et consigne selon le cas. */
export function rejectionText(item: ScanItem): string {
  const error = item.lastError;
  if (error === null) {
    return 'Rejeté';
  }
  const hint = rejectionHint(error.status, error.code);
  return hint === null ? error.detail : `${error.detail} — ${hint}`;
}

export function rejectionHint(status: number | null, code: string | null): string | null {
  if (status === 409 && code === BUSINESS_CONFLICT_CODE) {
    return "À signaler à l'organisateur";
  }
  if (status === 400 || status === 404 || status === null) {
    return null;
  }
  return 'Erreur technique';
}

export type NetworkIndicator = 'En ligne' | 'Hors ligne' | 'Serveur injoignable';

/** État réseau de l'écran de scan (RG26). */
export function networkIndicator(online: boolean, snapshot: Pick<QueueSnapshot, 'lastTransientFailureAt'>,
                                 now: number): NetworkIndicator {
  if (!online) {
    return 'Hors ligne';
  }
  const lastFailure = snapshot.lastTransientFailureAt;
  return lastFailure !== null && now - lastFailure < UNREACHABLE_WINDOW_MS ? 'Serveur injoignable' : 'En ligne';
}

/** Dossard et nom connus d'un token, d'après un scan accepté du même token sur cet appareil (RG25). */
export function knownRunner(qrToken: string, items: readonly ScanItem[]): { bib: number; name: string } | null {
  const accepted = items.find((item) => item.qrToken === qrToken && item.response !== null);
  return accepted?.response ? { bib: accepted.response.bib, name: accepted.response.runnerName } : null;
}

/** Libellé d'état d'un élément dans l'historique. */
export function itemStateLabel(item: ScanItem): string {
  switch (item.state) {
    case 'EN_ATTENTE':
      return 'en attente';
    case 'EN_COURS':
      return 'envoi en cours';
    case 'ACCEPTÉ':
      return 'accepté';
    case 'REJETÉ':
      return 'rejeté';
  }
}

function acceptedFeedback(item: ScanItem): ScanFeedback {
  return {
    tone: 'success', icon: '✔', text: acceptedText(item), sound: 'success', vibration: SUCCESS_VIBRATION,
    live: 'polite',
  };
}

function errorFeedback(text: string): ScanFeedback {
  return { tone: 'error', icon: '✖', text, sound: 'error', vibration: ERROR_VIBRATION, live: 'assertive' };
}

function errorFeedbackSilent(text: string): ScanFeedback {
  return { tone: 'error', icon: '✖', text, sound: null, vibration: null, live: 'assertive' };
}
