import { requiresAuthorization, requiresRunnerAuthorization } from './api-paths';
import { ResponseClass } from './http-classification';

/**
 * Envoi et séparation des identifiants (RG8 inc. 4, RG21 inc. 5) : deux emplacements distincts.
 * - identifiants ADMIN/SCANNER : uniquement vers `/api/scan/**` et `/api/admin/**` ;
 * - identifiants coureur : uniquement vers `/api/account/**` ;
 * - aucun en-tête vers `/api/public/**` ni vers une autre origine.
 * Un 401 n'efface que les identifiants de l'emplacement concerné.
 */

export type CredentialScope = 'STAFF' | 'RUNNER';

/** Sources des valeurs `Authorization` de chaque emplacement ; null si personne n'y est connecté. */
export interface CredentialSources {
  staffAuthorization(): Promise<string | null>;
  runnerAuthorization(): Promise<string | null>;
}

/** Suite à donner à une réponse reçue avec les identifiants d'un emplacement. */
export type CredentialEvent = 'STAFF_UNAUTHORIZED' | 'RUNNER_UNAUTHORIZED' | 'RUNNER_ACTIVITY';

export function credentialScope(path: string): CredentialScope | null {
  if (requiresRunnerAuthorization(path)) {
    return 'RUNNER';
  }
  return requiresAuthorization(path) ? 'STAFF' : null;
}

/** Valeur `Authorization` à joindre à une requête vers ce chemin, ou null. */
export async function authorizationFor(path: string, sources: CredentialSources): Promise<string | null> {
  switch (credentialScope(path)) {
    case 'RUNNER':
      return sources.runnerAuthorization();
    case 'STAFF':
      return sources.staffAuthorization();
    default:
      return null;
  }
}

/**
 * Événement d'une réponse reçue avec les identifiants de l'emplacement du chemin : 401 (effacement de cet
 * emplacement seulement), ou 2xx d'un appel coureur (activité, RG21 : l'expiration glisse). Aucun sinon.
 */
export function credentialEvent(path: string, responseClass: ResponseClass): CredentialEvent | null {
  const scope = credentialScope(path);
  if (scope === 'RUNNER') {
    if (responseClass === 'AUTH') {
      return 'RUNNER_UNAUTHORIZED';
    }
    return responseClass === 'SUCCESS' ? 'RUNNER_ACTIVITY' : null;
  }
  return scope === 'STAFF' && responseClass === 'AUTH' ? 'STAFF_UNAUTHORIZED' : null;
}
