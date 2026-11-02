/** Action d'une ligne de Compte qui demande une confirmation en ligne. */
export type ActionCompte = 'activation' | 'suppression';

/** Confirmation ouverte dans la liste : une seule à la fois, toutes actions confondues. */
export interface ConfirmationOuverte {
  readonly id: string;
  readonly action: ActionCompte;
}

/** Issue d'une action confirmée sur un Compte (désactivation, réactivation, suppression). */
export interface ResultatActionCompte {
  readonly succes?: string;
  readonly erreur?: string;
  /** La liste est à relire (faux pour un jeton CSRF expiré). */
  readonly recharger: boolean;
  /** Le Compte a disparu de la liste : le focus revient au bouton d'ouverture du formulaire. */
  readonly compteRetire?: boolean;
}
