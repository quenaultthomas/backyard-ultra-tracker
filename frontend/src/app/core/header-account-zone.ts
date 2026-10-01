/**
 * Zone « compte » de l'en-tête (RG4 inc. 7), fonction pure. Seule la connexion coureur compte : la connexion
 * staff n'est volontairement pas un paramètre (aucun identifiant ni rôle du staff dans l'en-tête).
 */
export interface HeaderAccountZone {
  /** Lien « Se connecter » vers `/connexion`. */
  readonly loginLink: boolean;
  /** Lien « Créer un compte » vers `/inscription`. */
  readonly createAccountLink: boolean;
  /** « Connecté : {pseudo} » si un coureur est connecté, sinon null. */
  readonly connectedLabel: string | null;
}

/** Écrans qui portent déjà un lien ou un formulaire de connexion : pas de doublon dans l'en-tête. */
const SCREENS_WITH_LOGIN = ['/scan', '/connexion', '/compte/connexion'];
const ACCOUNT_CREATION_SCREEN = '/inscription';
const RACE_REGISTRATION_PREFIX = '/inscription/';

/** `path` est l'URL courante (requête et fragment ignorés) ; `runnerPseudo` le pseudo coureur connecté ou null. */
export function headerAccountZone(path: string, runnerPseudo: string | null): HeaderAccountZone {
  if (runnerPseudo !== null) {
    return { loginLink: false, createAccountLink: false, connectedLabel: `Connecté : ${runnerPseudo}` };
  }
  const screen = screenOf(path);
  return {
    loginLink: !SCREENS_WITH_LOGIN.includes(screen),
    createAccountLink: screen !== ACCOUNT_CREATION_SCREEN,
    connectedLabel: null,
  };
}

/**
 * Retour proposé par « Créer un compte » : la page d'inscription à une course (`/inscription/{raceId}`), où le
 * compte créé s'inscrit ensuite par E20 (CA13 inc. 7) ; null ailleurs (arrivée sur `/compte`).
 */
export function createAccountReturn(path: string): string | null {
  const screen = screenOf(path);
  return screen.startsWith(RACE_REGISTRATION_PREFIX) ? screen : null;
}

/** Chemin sans requête, sans fragment et sans barre oblique finale (sauf la racine). */
function screenOf(path: string): string {
  const withoutSuffix = path.split(/[?#]/, 1)[0] ?? '';
  return withoutSuffix.length > 1 && withoutSuffix.endsWith('/') ? withoutSuffix.slice(0, -1) : withoutSuffix;
}
