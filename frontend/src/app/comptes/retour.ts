/** Un seul `/` suivi d'un caractère autre que `/` ou `\` : chemin interne (anti redirection ouverte). */
const CHEMIN_INTERNE = /^\/[^/\\]/;

/** Destination après connexion : le paramètre `retour` s'il est un chemin interne, sinon `/`. */
export function destinationApresConnexion(retour: string | null): string {
  return retour !== null && CHEMIN_INTERNE.test(retour) ? retour : '/';
}
