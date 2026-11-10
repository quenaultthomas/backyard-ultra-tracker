import { Role } from './compte';
import { estAdministrateur, estBenevole } from './roles';

/** Un seul `/` suivi d'un caractère autre que `/` ou `\` : chemin interne (anti redirection ouverte). */
const CHEMIN_INTERNE = /^\/[^/\\]/;

/** Écrans réservés aux anonymes : y revenir après connexion n'a pas de sens (traité comme absent). */
const ECRAN_DE_CONNEXION = /^\/(?:connexion|creer-compte)\/?(?:[?#]|$)/;

/** Accueil d'un rôle, toujours autorisé à ce rôle : aucune boucle de redirection possible. */
export function accueilDuRole(role: Role | undefined): string {
  if (role !== undefined && estAdministrateur(role)) {
    return '/administration';
  }
  return role !== undefined && estBenevole(role) ? '/benevole' : '/';
}

/**
 * Destination après connexion : le paramètre `retour` s'il est un chemin interne autre que la
 * connexion ou la création de compte, sinon l'accueil du rôle. Un `retour` interdit au rôle est
 * remplacé par l'accueil du rôle par la garde de rôles (`NavigationApresConnexionService`).
 */
export function destinationApresConnexion(retour: string | null, role: Role | undefined): string {
  if (retour !== null && CHEMIN_INTERNE.test(retour) && !ECRAN_DE_CONNEXION.test(retour)) {
    return retour;
  }
  return accueilDuRole(role);
}
