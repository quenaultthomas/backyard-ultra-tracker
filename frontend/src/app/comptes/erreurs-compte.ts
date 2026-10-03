import {
  MESSAGE_PAGE_EXPIREE,
  MESSAGE_SERVICE_INDISPONIBLE,
  ProblemDetail,
  lireProbleme,
  messageDuChamp,
} from '../partage/probleme';

/** Messages d'erreur d'un formulaire, par champ de l'API (`C`) ou en message général. */
export type ErreursFormulaire<C extends string> = Partial<Record<C, string>> & ErreursSansChamp;

/** Erreurs communes à tous les formulaires, sans rattachement à un champ. */
interface ErreursSansChamp {
  generale?: string;
  /** Le jeton CSRF est refusé : l'écran doit en redemander un. */
  jetonExpire?: boolean;
  /** Une session existe déjà : l'écran resynchronise l'état puis redirige, sans message. */
  dejaConnecte?: boolean;
}

/** Une erreur sans champ vaut pour tout formulaire, quels que soient ses champs `C`. */
function sansChamp<C extends string>(erreurs: ErreursSansChamp): ErreursFormulaire<C> {
  return erreurs as ErreursFormulaire<C>;
}

type ChampCompte = 'pseudo' | 'motDePasse';
const CHAMPS_COMPTE: readonly ChampCompte[] = ['pseudo', 'motDePasse'];

/** Messages d'erreur des formulaires de compte (création, connexion), par emplacement. */
export type ErreursFormulaireCompte = ErreursFormulaire<ChampCompte>;

/** Messages associés à un code propre à un écran, fixes ou calculés à partir du problème. */
export type ErreursSpecifiques<C extends string = ChampCompte> = Readonly<
  Record<string, ErreursFormulaire<C> | ((probleme: ProblemDetail) => ErreursFormulaire<C>)>
>;

/**
 * Message de blocage temporaire (429 `TENTATIVES_EXCESSIVES`) commençant par `prefixe` : délai
 * en minutes arrondi au supérieur, ou repli si l'API ne fournit pas de délai exploitable.
 */
export function erreurTentativesExcessives(
  prefixe: string,
): (probleme: ProblemDetail) => { generale: string } {
  return ({ reessayerDansSecondes }) => {
    if (
      typeof reessayerDansSecondes !== 'number' ||
      !Number.isFinite(reessayerDansSecondes) ||
      reessayerDansSecondes <= 0
    ) {
      return { generale: `${prefixe} Réessayez plus tard.` };
    }
    const minutes = Math.ceil(reessayerDansSecondes / 60);
    return {
      generale: `${prefixe} Réessayez dans ${minutes} ${minutes === 1 ? 'minute' : 'minutes'}.`,
    };
  };
}

/** {@link interpreterErreurFormulaire} pour les formulaires à pseudo et mot de passe. */
export function interpreterErreurCompte(
  erreur: unknown,
  specifiques: ErreursSpecifiques,
): ErreursFormulaireCompte {
  return interpreterErreurFormulaire(erreur, specifiques, CHAMPS_COMPTE);
}

/**
 * Traduit une erreur de l'API en messages à afficher, sans détail technique. `specifiques`
 * associe aux codes propres à un écran les messages à afficher ; `champs` liste les champs
 * dont les erreurs de validation s'affichent sous le champ.
 */
export function interpreterErreurFormulaire<C extends string>(
  erreur: unknown,
  specifiques: ErreursSpecifiques<C>,
  champs: readonly C[],
): ErreursFormulaire<C> {
  const probleme = lireProbleme(erreur);
  if (probleme?.code === undefined) {
    return sansChamp({ generale: MESSAGE_SERVICE_INDISPONIBLE });
  }
  if (Object.hasOwn(specifiques, probleme.code)) {
    const specifique = specifiques[probleme.code];
    return typeof specifique === 'function' ? specifique(probleme) : specifique;
  }
  switch (probleme.code) {
    case 'VALIDATION_ECHOUEE':
      return erreursDeValidation(probleme, champs);
    case 'CSRF_INVALIDE':
      return sansChamp({ generale: MESSAGE_PAGE_EXPIREE, jetonExpire: true });
    case 'DEJA_CONNECTE':
      return sansChamp({ dejaConnecte: true });
    default:
      return sansChamp({ generale: MESSAGE_SERVICE_INDISPONIBLE });
  }
}

/** Message sous chaque champ connu ; message général si aucun champ connu n'est en erreur. */
function erreursDeValidation<C extends string>(
  probleme: ProblemDetail,
  champs: readonly C[],
): ErreursFormulaire<C> {
  const erreurs: Partial<Record<C, string>> = {};
  for (const champ of champs) {
    const message = messageDuChamp(probleme, champ);
    if (message !== undefined) {
      erreurs[champ] = message;
    }
  }
  return Object.keys(erreurs).length > 0
    ? erreurs
    : sansChamp({ generale: MESSAGE_SERVICE_INDISPONIBLE });
}
