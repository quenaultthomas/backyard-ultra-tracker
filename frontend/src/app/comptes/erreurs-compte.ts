import {
  MESSAGE_PAGE_EXPIREE,
  MESSAGE_SERVICE_INDISPONIBLE,
  lireProbleme,
  messageDuChamp,
} from '../partage/probleme';

/** Messages d'erreur des formulaires de compte (création, connexion), par emplacement. */
export interface ErreursFormulaireCompte {
  pseudo?: string;
  motDePasse?: string;
  generale?: string;
  /** Le jeton CSRF est refusé : l'écran doit en redemander un. */
  jetonExpire?: boolean;
  /** Une session existe déjà : l'écran resynchronise l'état puis redirige, sans message. */
  dejaConnecte?: boolean;
}

/**
 * Traduit une erreur de l'API en messages à afficher, sans détail technique. `specifiques`
 * associe aux codes propres à un écran les messages à afficher.
 */
export function interpreterErreurCompte(
  erreur: unknown,
  specifiques: Readonly<Record<string, ErreursFormulaireCompte>>,
): ErreursFormulaireCompte {
  const probleme = lireProbleme(erreur);
  if (probleme?.code === undefined) {
    return { generale: MESSAGE_SERVICE_INDISPONIBLE };
  }
  if (Object.hasOwn(specifiques, probleme.code)) {
    return specifiques[probleme.code];
  }
  switch (probleme.code) {
    case 'VALIDATION_ECHOUEE': {
      const erreurs = {
        pseudo: messageDuChamp(probleme, 'pseudo'),
        motDePasse: messageDuChamp(probleme, 'motDePasse'),
      };
      return erreurs.pseudo || erreurs.motDePasse
        ? erreurs
        : { generale: MESSAGE_SERVICE_INDISPONIBLE };
    }
    case 'CSRF_INVALIDE':
      return { generale: MESSAGE_PAGE_EXPIREE, jetonExpire: true };
    case 'DEJA_CONNECTE':
      return { dejaConnecte: true };
    default:
      return { generale: MESSAGE_SERVICE_INDISPONIBLE };
  }
}
