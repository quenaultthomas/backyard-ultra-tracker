import { Role } from './compte';
import { estAdministrateur, estBenevole } from './roles';

/** Un seul `/` suivi d'un caractère autre que `/` ou `\` : chemin interne (anti redirection ouverte). */
const CHEMIN_INTERNE = /^\/[^/\\]/;

/**
 * Destination après connexion : le paramètre `retour` s'il est un chemin interne, sinon
 * l'espace d'administration pour un admin, l'espace bénévole pour un bénévole, sinon `/`.
 */
export function destinationApresConnexion(retour: string | null, role: Role | undefined): string {
  if (retour !== null && CHEMIN_INTERNE.test(retour)) {
    return retour;
  }
  if (role !== undefined && estAdministrateur(role)) {
    return '/administration';
  }
  return role !== undefined && estBenevole(role) ? '/benevole' : '/';
}
