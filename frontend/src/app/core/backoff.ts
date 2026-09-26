/**
 * Nouvel essai avec backoff exponentiel plafonné (RG22) : délai(n) = min(2^(n−1), 60) s, multiplié par un
 * facteur aléatoire uniforme dans [1,0 ; 1,2]. Aucun nombre maximal d'essais.
 */

export const MAX_BACKOFF_SECONDS = 60;
export const BACKOFF_JITTER = 0.2;

/** Délai de base en secondes après le n-ième échec consécutif (n >= 1) : 1, 2, 4, 8, 16, 32, 60, 60… */
export function baseBackoffSeconds(consecutiveFailures: number): number {
  const exponent = Math.max(consecutiveFailures, 1) - 1;
  return Math.min(2 ** exponent, MAX_BACKOFF_SECONDS);
}

/** Délai effectif en millisecondes ; `random` renvoie une valeur uniforme dans [0 ; 1]. */
export function backoffDelayMs(consecutiveFailures: number, random: () => number): number {
  const factor = 1 + BACKOFF_JITTER * Math.min(Math.max(random(), 0), 1);
  return Math.round(baseBackoffSeconds(consecutiveFailures) * 1000 * factor);
}
