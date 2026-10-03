import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Longueur minimale contrôlée par confort : la règle de référence est celle de l'API. */
export const LONGUEUR_MIN_MOT_DE_PASSE = 12;

/** Validateur de groupe : le champ `champ` et `confirmation` doivent être identiques. */
export function confirmationIdentiqueA(champ: string): ValidatorFn {
  return (groupe: AbstractControl): ValidationErrors | null => {
    const valeurs = groupe.value as Record<string, string>;
    return valeurs[champ] === valeurs['confirmation'] ? null : { confirmationDifferente: true };
  };
}

/** Validateur de groupe : `motDePasse` et `confirmation` doivent être identiques. */
export const confirmationIdentique = confirmationIdentiqueA('motDePasse');

/** Message de contrôle client du pseudo (champ requis). */
export function erreurClientPseudo(pseudo: AbstractControl): string | undefined {
  return pseudo.hasError('required') ? 'Le pseudo est obligatoire.' : undefined;
}

/** Message de contrôle client du mot de passe (requis, longueur minimale). */
export function erreurClientMotDePasse(
  motDePasse: AbstractControl,
  messageObligatoire = 'Le mot de passe est obligatoire.',
): string | undefined {
  if (motDePasse.hasError('required')) {
    return messageObligatoire;
  }
  return motDePasse.hasError('minlength')
    ? `Le mot de passe doit faire au moins ${LONGUEUR_MIN_MOT_DE_PASSE} caractères.`
    : undefined;
}

/** Message de contrôle client de la confirmation (validateur de groupe `confirmationIdentique*`). */
export function erreurClientConfirmation(groupe: AbstractControl): string | undefined {
  return groupe.hasError('confirmationDifferente')
    ? 'Les mots de passe ne correspondent pas.'
    : undefined;
}
