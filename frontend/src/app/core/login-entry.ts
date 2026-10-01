import { Role } from './api.types';
import { INVALID_CREDENTIALS } from './outcomes';

/**
 * Écran de connexion unique (RG1 à RG3 inc. 7) : logique pure du choix de l'entrée, de ses libellés et de
 * l'orientation après connexion. L'entrée n'est ni stockée ni envoyée au serveur : elle désigne seulement
 * l'endpoint de validation et l'emplacement d'identifiants (« Coureur » : E21, « Bénévole » : E19).
 */
export type LoginEntry = 'RUNNER' | 'STAFF';

/** Paramètres de l'écran : `retour` et `profil` (requête), `runnerRoute` vrai sur l'alias `/compte/connexion`. */
export interface LoginEntryParams {
  readonly retour?: string;
  readonly profil?: string;
  readonly runnerRoute?: boolean;
}

export interface LoginFieldLabels {
  readonly identifier: string;
  readonly remember: string;
}

/** Message d'échec unique, quel que soit le référentiel (RG2, D1-bis). */
export const LOGIN_FAILURE_MESSAGE = INVALID_CREDENTIALS;

/** Aide neutre affichée dans un élément distinct du message d'échec (D1-bis). */
export const LOGIN_FAILURE_HINT = 'Vérifiez le type de compte choisi';

const STAFF_PROFILE = 'benevole';
const STAFF_SCREENS = ['/scan', '/admin'];

const FIELD_LABELS: Readonly<Record<LoginEntry, LoginFieldLabels>> = {
  RUNNER: { identifier: 'Pseudo', remember: 'Rester connecté 24 h sur cet appareil' },
  STAFF: {
    identifier: "Nom d'utilisateur",
    remember: 'Rester connecté 24 h sur cet appareil (compte scanner uniquement)',
  },
};

/**
 * RG1 : « Bénévole » si `profil=benevole` ou si `retour` désigne un écran du staff (`/scan`, `/admin` et leurs
 * sous-chemins) ; « Coureur » sinon. RG3 : toujours « Coureur » sur `/compte/connexion`.
 */
export function defaultLoginEntry(params: LoginEntryParams): LoginEntry {
  if (params.runnerRoute === true) {
    return 'RUNNER';
  }
  if (params.profil === STAFF_PROFILE || isStaffScreen(params.retour)) {
    return 'STAFF';
  }
  return 'RUNNER';
}

/** RG1 : libellés figés du champ d'identifiant et de la case de mémorisation. */
export function loginFieldLabels(entry: LoginEntry): LoginFieldLabels {
  return FIELD_LABELS[entry];
}

/**
 * RG2 : le retour demandé s'il est interne et sûr ; sinon `/compte` pour un coureur, `/admin` pour un ADMIN et
 * `/scan` pour un SCANNER.
 */
export function loginDestination(entry: LoginEntry, retour: string | undefined, role?: Role): string {
  if (isSafeInternalPath(retour)) {
    return retour;
  }
  if (entry === 'RUNNER') {
    return '/compte';
  }
  return role === 'ADMIN' ? '/admin' : '/scan';
}

/** Chemin interne à l'application : commence par `/`, mais pas par `//` (autre origine). */
export function isSafeInternalPath(path: string | undefined): path is string {
  return path !== undefined && path.startsWith('/') && !path.startsWith('//');
}

function isStaffScreen(retour: string | undefined): boolean {
  return retour !== undefined
    && STAFF_SCREENS.some((screen) => retour === screen || retour.startsWith(`${screen}/`));
}
