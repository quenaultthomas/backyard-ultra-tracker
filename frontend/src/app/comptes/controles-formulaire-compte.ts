import { AbstractControl, ValidationErrors } from '@angular/forms';

/** Longueur minimale contrôlée par confort : la règle de référence est celle de l'API. */
export const LONGUEUR_MIN_MOT_DE_PASSE = 12;

/** Validateur de groupe : `motDePasse` et `confirmation` doivent être identiques. */
export function confirmationIdentique(groupe: AbstractControl): ValidationErrors | null {
  const { motDePasse, confirmation } = groupe.value as { motDePasse: string; confirmation: string };
  return motDePasse === confirmation ? null : { confirmationDifferente: true };
}

/** Message de contrôle client du pseudo (champ requis). */
export function erreurClientPseudo(pseudo: AbstractControl): string | undefined {
  return pseudo.hasError('required') ? 'Le pseudo est obligatoire.' : undefined;
}

/** Message de contrôle client du mot de passe (requis, longueur minimale). */
export function erreurClientMotDePasse(motDePasse: AbstractControl): string | undefined {
  if (motDePasse.hasError('required')) {
    return 'Le mot de passe est obligatoire.';
  }
  return motDePasse.hasError('minlength')
    ? `Le mot de passe doit faire au moins ${LONGUEUR_MIN_MOT_DE_PASSE} caractères.`
    : undefined;
}
