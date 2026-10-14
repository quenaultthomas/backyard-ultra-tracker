import { ErreurScan } from './passage';

/** Refus définitif d'un scan, tel qu'affiché : le serveur décide, l'écran met en forme. */
export interface RefusScan {
  motif: string;
  /** `detail` de l'API, affiché tel quel. */
  detail: string;
  /** Présent seulement si l'API fournit le dossard et le pseudo. */
  coureur?: { dossard: number; pseudo: string };
  numeroBoucle?: number;
}

const MOTIF_PAR_DEFAUT = 'Scan refusé';

/** Libellé de motif par `code` (spec 4.6 RG8), jamais déduit du `detail`. */
const MOTIFS_PAR_CODE: Readonly<Record<string, string>> = {
  PASSAGE_DEJA_ENREGISTRE: 'Déjà passé',
  PASSAGE_TROP_RAPPROCHE: 'Scan trop rapproché',
  PASSAGE_PRECEDENT_MANQUANT: 'Boucle précédente manquante',
  TEMPS_DE_COURSE_ECOULE: 'Temps de course écoulé',
  COURSE_NON_DEMARREE: 'Course non démarrée',
  COURSE_TERMINEE: 'Course terminée',
  BENEVOLE_NON_AFFECTE: 'Course non affectée',
  JETON_INCONNU: 'Jeton inconnu',
};

const MOTIFS_PAR_STATUT: Readonly<Record<NonNullable<ErreurScan['statut']>, string>> = {
  ABANDON: 'Coureur en abandon',
  VAINQUEUR: 'Coureur déjà vainqueur',
};

export function lireRefusScan(probleme: ErreurScan): RefusScan {
  const { dossard, pseudo, numeroBoucle } = probleme;
  return {
    motif: motifDe(probleme),
    detail: probleme.detail,
    coureur: dossard !== undefined && pseudo !== undefined ? { dossard, pseudo } : undefined,
    numeroBoucle,
  };
}

function motifDe(probleme: ErreurScan): string {
  if (probleme.code === 'INSCRIPTION_NON_EN_COURSE' && probleme.statut) {
    return MOTIFS_PAR_STATUT[probleme.statut] ?? MOTIF_PAR_DEFAUT;
  }
  return (probleme.code && MOTIFS_PAR_CODE[probleme.code]) || MOTIF_PAR_DEFAUT;
}
