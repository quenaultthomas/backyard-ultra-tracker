/**
 * Validation des formulaires coureur (RG17 inc. 5), miroir de RG2 et RG3 de l'API : le serveur reste l'autorité.
 * Le front ne normalise pas le pseudo : il contrôle le format de la saisie privée de ses espaces de bord, puis
 * l'envoie telle quelle (RG2).
 */

const PSEUDO_FORMAT = /^[A-Za-z0-9_-]{3,30}$/;
export const PASSWORD_MIN_CHARACTERS = 8;
export const PASSWORD_MAX_UTF8_BYTES = 72;

export const PSEUDO_FORMAT_MESSAGE =
  '3 à 30 caractères parmi lettres non accentuées, chiffres, _ et -, sans espace';
export const PASSWORD_RULE_MESSAGE = `${PASSWORD_MIN_CHARACTERS} caractères minimum et `
  + `${PASSWORD_MAX_UTF8_BYTES} octets maximum`;
export const PASSWORD_MISMATCH_MESSAGE = 'Les deux mots de passe sont différents';

/** Erreurs de formulaire indexées par champ ; vide si le formulaire peut être envoyé. */
export type FormErrors = ReadonlyMap<string, string>;

/** RG2 : format de la saisie du pseudo. */
export function pseudoError(pseudo: string): string | null {
  return PSEUDO_FORMAT.test(pseudo.trim()) ? null : PSEUDO_FORMAT_MESSAGE;
}

/** RG3 : 8 caractères au moins, 72 octets UTF-8 au plus, sans nettoyage d'espaces. */
export function accountPasswordError(password: string): string | null {
  const characters = [...password].length;
  const bytes = new TextEncoder().encode(password).length;
  return characters >= PASSWORD_MIN_CHARACTERS && bytes <= PASSWORD_MAX_UTF8_BYTES ? null : PASSWORD_RULE_MESSAGE;
}

/** Formulaire d'inscription avec création de compte (E3) : pseudo, mot de passe et confirmation égale. */
export function registrationFormErrors(pseudo: string, password: string, confirmation: string): FormErrors {
  const errors = new Map<string, string>();
  addError(errors, 'pseudo', pseudoError(pseudo));
  addNewPasswordErrors(errors, 'password', password, confirmation);
  return errors;
}

/** Formulaire « Changer mon mot de passe » (E23) et saisie du mot de passe provisoire par l'admin (E22). */
export function passwordChangeErrors(newPassword: string, confirmation: string): FormErrors {
  const errors = new Map<string, string>();
  addNewPasswordErrors(errors, 'newPassword', newPassword, confirmation);
  return errors;
}

function addNewPasswordErrors(errors: Map<string, string>, field: string, password: string,
                              confirmation: string): void {
  addError(errors, field, accountPasswordError(password));
  addError(errors, 'confirmation', password === confirmation ? null : PASSWORD_MISMATCH_MESSAGE);
}

function addError(errors: Map<string, string>, field: string, message: string | null): void {
  if (message !== null) {
    errors.set(field, message);
  }
}
