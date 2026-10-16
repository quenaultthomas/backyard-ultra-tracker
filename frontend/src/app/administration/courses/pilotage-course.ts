import { StatutInscription } from '../../inscriptions/course-ouverte';
import { StatutCourse } from './course';

/** Ligne de `PilotageCourse.inscritsEnCourse` : jamais de jeton QR ni d'identifiant de Compte. */
export interface InscritEnCourse {
  inscriptionId: string;
  dossard: number;
  /** Pseudo tel que renvoyé par l'API (« Coureur anonyme », « Compte inconnu ») : affiché en texte. */
  pseudo: string;
  /** Toujours `EN_COURSE`. */
  statut: StatutInscription;
  /** Nombre de Passages de l'Inscription (`SCAN` et `CORRECTION`), toujours renseigné. */
  nombreBouclesTerminees: number;
  /** Passage sur la Boucle courante, selon le serveur ; `null` si `boucleCourante` est `null`. */
  boucleCouranteTerminee: boolean | null;
}

/** Motif d'abandon d'une Inscription (langage ubiquitaire). */
export type MotifAbandon = 'VOLONTAIRE' | 'HORS_DELAI' | 'MANUEL' | 'AUTRE';

/** Libellés des motifs d'abandon (affichage seulement). */
export const LIBELLES_MOTIF_ABANDON: Readonly<Record<MotifAbandon, string>> = {
  HORS_DELAI: 'Hors délai',
  VOLONTAIRE: 'Volontaire',
  MANUEL: 'Manuel',
  AUTRE: 'Autre',
};

/** Motifs acceptés par l'abandon manuel (`HORS_DELAI` est réservé à l'abandon automatique). */
export type MotifAbandonManuel = Exclude<MotifAbandon, 'HORS_DELAI'>;

/** Choix proposés par le panneau de confirmation, dans l'ordre d'affichage. */
export const MOTIFS_ABANDON_MANUEL: readonly MotifAbandonManuel[] = [
  'VOLONTAIRE',
  'MANUEL',
  'AUTRE',
];

/** Corps de `POST /api/administration/courses/{id}/inscriptions/{inscriptionId}/abandon`. */
export interface AbandonManuelRequete {
  motif: MotifAbandonManuel;
}

/** Réponse 200 de l'abandon manuel : ni jeton QR, ni identifiant de Compte, ni pseudo. */
export interface AbandonManuel {
  inscriptionId: string;
  dossard: number;
  /** Toujours `ABANDON`. */
  statut: StatutInscription;
  motifAbandon: MotifAbandonManuel;
  boucleAbandon: number;
  /** `TERMINEE` quand plus aucun coureur n'est en course (décidé par le serveur). */
  statutCourse: Exclude<StatutCourse, 'EN_PREPARATION'>;
}

/** Ligne de `PilotageCourse.inscritsEnAbandon` : jamais de jeton QR ni d'identifiant de Compte. */
export interface InscritEnAbandon {
  inscriptionId: string;
  dossard: number;
  pseudo: string;
  /** Toujours `ABANDON`. */
  statut: StatutInscription;
  /** Nombre de Passages de l'Inscription (`SCAN` et `CORRECTION`). */
  nombreBouclesTerminees: number;
  /** `null` pour un abandon sans Boucle enregistrée (affiché « — »). */
  boucleAbandon: number | null;
  /** `null` pour un abandon sans motif enregistré (affiché « — »). */
  motifAbandon: MotifAbandon | null;
}

/** Ligne de `PilotageCourse.inscritsVainqueurs` : jamais de jeton QR ni d'identifiant de Compte. */
export interface InscritVainqueur {
  inscriptionId: string;
  dossard: number;
  pseudo: string;
  /** Toujours `VAINQUEUR`. */
  statut: StatutInscription;
  /** Nombre de Passages de l'Inscription (`SCAN` et `CORRECTION`). */
  nombreBouclesTerminees: number;
}

/**
 * Instantané de `GET /api/administration/courses/{id}/pilotage`, calculé par le serveur.
 * Les champs de chronométrage sont `null` pour une Course `TERMINEE` ou sans heure de départ.
 */
export interface PilotageCourse {
  courseId: string;
  nom: string;
  logoUrl: string | null;
  /** `EN_PREPARATION` n'est jamais renvoyé (409 `COURSE_NON_DEMARREE`). */
  statut: Exclude<StatutCourse, 'EN_PREPARATION'>;
  /** Instant du serveur (ISO-8601 UTC, millisecondes) ; jamais comparé à l'heure du navigateur. */
  heureServeur: string;
  /** Départ de la Boucle 1 (ISO-8601 UTC, à la seconde), `null` sans heure de départ. */
  demarreeLe: string | null;
  dureeBoucleMinutes: number;
  nombreMaxBoucles: number;
  boucleCourante: number | null;
  derniereBoucle: boolean;
  boucleMaxTerminee: boolean;
  debutBoucleCourante: string | null;
  finBoucleCourante: string | null;
  tempsAvantFinBoucleMillisecondes: number | null;
  dureeEcouleeMillisecondes: number | null;
  /** Toutes les Inscriptions de la Course, tous statuts. */
  nombreInscrits: number;
  /** Inscrits `EN_COURSE` ayant bouclé la Boucle courante ; `null` si `boucleCourante` est `null`. */
  nombreAyantBoucle: number | null;
  /** Inscrits `EN_COURSE` encore en boucle ; `null` si `boucleCourante` est `null`. */
  nombreEnBoucle: number | null;
  /** Inscriptions `EN_COURSE`, triées par dossard par l'API. */
  inscritsEnCourse: InscritEnCourse[];
  /** Nombre d'Inscriptions `ABANDON` (longueur de `inscritsEnAbandon`). */
  nombreAbandons: number;
  /** Inscriptions `ABANDON`, triées par dossard par l'API. */
  inscritsEnAbandon: InscritEnAbandon[];
  /** Nombre d'Inscriptions `VAINQUEUR` (longueur de `inscritsVainqueurs`), 0 hors fin de course. */
  nombreVainqueurs: number;
  /** Inscriptions `VAINQUEUR`, triées par dossard par l'API ; jamais déduites par le front. */
  inscritsVainqueurs: InscritVainqueur[];
}

/** Pastille d'un inscrit pour la Boucle courante : libellé en texte, classe de la charte. */
export interface PastilleBouclage {
  libelle: string;
  classe: string;
}

/** Table d'affichage de `boucleCouranteTerminee` (valeur du serveur, jamais recalculée). */
export const PASTILLES_BOUCLAGE: Readonly<Record<'termine' | 'enBoucle', PastilleBouclage>> = {
  termine: { libelle: 'A bouclé', classe: 'pastille-statut pastille-statut--a-boucle' },
  enBoucle: { libelle: 'En boucle', classe: 'pastille-statut pastille-statut--en-boucle' },
};
