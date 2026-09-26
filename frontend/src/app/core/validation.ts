/**
 * Validation de format des saisies, miroir des contraintes Bean Validation de l'API (RG1) : le serveur reste
 * l'autorité. Chaque fonction renvoie le message d'erreur, ou null si la valeur est acceptable.
 */

export const MAX_TEXT_LENGTH = 255;
const MAX_JAVA_INT = 2_147_483_647;

/** Non vide après suppression des espaces de début et de fin, 255 caractères au plus. */
export function requiredText(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return 'Champ obligatoire';
  }
  return trimmed.length > MAX_TEXT_LENGTH ? `${MAX_TEXT_LENGTH} caractères maximum` : null;
}

export function positiveInteger(value: string): string | null {
  const parsed = parseInteger(value);
  return parsed === null || parsed <= 0 ? 'Nombre entier strictement positif attendu' : null;
}

export function nonNegativeInteger(value: string): string | null {
  const parsed = parseInteger(value);
  return parsed === null || parsed < 0 ? 'Nombre entier positif ou nul attendu' : null;
}

/** Date au format ISO « aaaa-mm-jj » (valeur d'un champ date). */
export function isoDate(value: string): string | null {
  const trimmed = value.trim();
  return /^\d{4}-\d{2}-\d{2}$/.test(trimmed) && !Number.isNaN(Date.parse(trimmed)) ? null : 'Date attendue';
}

/** Entier décimal dans les bornes d'un entier Java ; null sinon. */
export function parseInteger(value: string): number | null {
  const trimmed = value.trim();
  if (!/^-?\d+$/.test(trimmed)) {
    return null;
  }
  const parsed = Number(trimmed);
  return Math.abs(parsed) <= MAX_JAVA_INT ? parsed : null;
}
