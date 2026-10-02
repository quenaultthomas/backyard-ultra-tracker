import { lireProbleme } from '../../partage/probleme';

/** Messages d'erreur affichés par l'écran « Créer un compte », par emplacement. */
export interface ErreursCreerCompte {
  pseudo?: string;
  motDePasse?: string;
  generale?: string;
  /** Le jeton CSRF est refusé : l'écran doit en redemander un. */
  jetonExpire?: boolean;
}

const MESSAGE_PAGE_EXPIREE = 'La page a expiré, veuillez réessayer.';
const MESSAGE_SERVICE_INDISPONIBLE = 'Service indisponible, veuillez réessayer plus tard.';
const MESSAGE_PSEUDO_DEJA_UTILISE = 'Ce pseudo est déjà utilisé.';

/** Traduit une erreur de `POST /api/comptes` en messages à afficher, sans détail technique. */
export function interpreterErreurCreation(erreur: unknown): ErreursCreerCompte {
  const probleme = lireProbleme(erreur);
  switch (probleme?.code) {
    case 'VALIDATION_ECHOUEE': {
      const messageDe = (champ: string) =>
        probleme.erreurs?.find((erreurChamp) => erreurChamp.champ === champ)?.message;
      const erreurs = { pseudo: messageDe('pseudo'), motDePasse: messageDe('motDePasse') };
      return erreurs.pseudo || erreurs.motDePasse
        ? erreurs
        : { generale: MESSAGE_SERVICE_INDISPONIBLE };
    }
    case 'PSEUDO_DEJA_UTILISE':
      return { pseudo: MESSAGE_PSEUDO_DEJA_UTILISE };
    case 'CSRF_INVALIDE':
      return { generale: MESSAGE_PAGE_EXPIREE, jetonExpire: true };
    default:
      return { generale: MESSAGE_SERVICE_INDISPONIBLE };
  }
}
