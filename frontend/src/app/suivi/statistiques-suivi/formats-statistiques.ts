/** Formats d'affichage des statistiques du suivi (présentation seulement, aucun calcul de cumul). */

/** Kilomètres à deux décimales, virgule décimale : 13400 m donne « 13,40 km ». */
export function formaterKilometres(metres: number): string {
  const centiemes = Math.round(metres / 10);
  const decimales = String(centiemes % 100).padStart(2, '0');
  return `${Math.floor(centiemes / 100)},${decimales} km`;
}

/** Dénivelé positif : 70 donne « 70 m D+ ». */
export function formaterDenivele(metres: number): string {
  return `${metres} m D+`;
}

/** Allure `m:ss /km`, minutes sans plafond : 433 s/km donne « 7:13 /km » ; `null` : « Non disponible ». */
export function formaterAllure(secondesParKilometre: number | null): string {
  if (secondesParKilometre === null) {
    return 'Non disponible';
  }
  const secondes = String(secondesParKilometre % 60).padStart(2, '0');
  return `${Math.floor(secondesParKilometre / 60)}:${secondes} /km`;
}
