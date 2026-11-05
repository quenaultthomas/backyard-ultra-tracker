/**
 * Action qui demande une confirmation en ligne : sur une ligne de Compte, ou `promotion` sur un
 * résultat de la recherche de coureurs (`id` du coureur trouvé).
 */
export type ActionCompte = 'activation' | 'suppression' | 'promotion';

/** Confirmation ouverte sur la page : une seule à la fois, toutes actions confondues. */
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

/** Issue d'un changement de rôle confirmé depuis la recherche de coureurs. */
export interface ResultatChangementRole extends ResultatActionCompte {
  /** Les résultats ont changé (succès, coureur introuvable) : la recherche est relancée. */
  readonly relancerRecherche: boolean;
}
