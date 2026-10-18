/** Formats d'affichage du chronométrage (présentation seulement, aucune règle de Boucle). */

/** Compte à rebours `HH:MM:SS`, arrondi à la seconde supérieure (`00:00:00` seulement à zéro). */
export function formaterCompteARebours(millisecondes: number): string {
  return formaterSecondes(Math.ceil(millisecondes / 1000));
}

/** Durée écoulée `HH:MM:SS`, arrondie à la seconde inférieure, heures sans limite (`27:05:09`). */
export function formaterDureeEcoulee(millisecondes: number): string {
  return formaterSecondes(Math.floor(millisecondes / 1000));
}

function formaterSecondes(total: number): string {
  const secondes = Math.max(0, total);
  const heures = Math.floor(secondes / 3600);
  const minutes = Math.floor((secondes % 3600) / 60);
  return [heures, minutes, secondes % 60].map((n) => String(n).padStart(2, '0')).join(':');
}
