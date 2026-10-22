import { StatutCourse } from '../administration/courses/course';
import { MotifAbandon } from '../administration/courses/pilotage-course';

/** Ligne publique d'un coureur en course ou vainqueur (`CoureurSuivi`) : dossard et pseudo seulement. */
export interface CoureurSuivi {
  dossard: number;
  /** Pseudo tel que renvoyé par l'API (« Coureur anonyme », « Compte inconnu ») : affiché en texte. */
  pseudo: string;
}

/** Ligne publique d'un abandon (`AbandonSuivi`). */
export interface AbandonSuivi {
  dossard: number;
  pseudo: string;
  /** Boucle non terminée ; `null` pour un abandon enregistré sans Boucle. */
  boucleAbandon: number | null;
  /** `null` pour un abandon enregistré sans motif. */
  motifAbandon: MotifAbandon | null;
}

/**
 * Réponse publique de `GET /api/courses/{id}/suivi` (`SuiviCourseReponse`), calculée par le serveur.
 * Les champs de chronométrage sont `null` hors d'une Course `EN_COURS` avec heure de départ.
 */
export interface SuiviCourse {
  courseId: string;
  nom: string;
  /** Adresse publique versionnée du logo (`/api/courses/{id}/logo?v=…`), `null` sans logo. */
  logoUrl: string | null;
  statut: StatutCourse;
  /** Instant du serveur (ISO-8601 UTC, millisecondes) ; jamais comparé à l'heure du navigateur. */
  heureServeur: string;
  nombreMaxBoucles: number;
  /** Boucle en cours, plafonnée par le serveur à `nombreMaxBoucles`. */
  boucleCourante: number | null;
  derniereBoucle: boolean;
  boucleMaxTerminee: boolean;
  /** Boucle terminée dont la décision (abandon, fin de course) est en attente ; `null` sinon. */
  boucleEnAttenteDeDecision: number | null;
  tempsAvantFinBoucleMillisecondes: number | null;
  dureeEcouleeMillisecondes: number | null;
  /** Inscriptions `EN_COURSE`, triées par le serveur ; vide en `EN_PREPARATION` et `TERMINEE`. */
  coureursEnCourse: CoureurSuivi[];
  /** Inscriptions `ABANDON`, triées par le serveur ; vide en `EN_PREPARATION`. */
  abandons: AbandonSuivi[];
  /** Inscriptions `VAINQUEUR`, triées par le serveur ; vide sauf `TERMINEE` avec vainqueur. */
  vainqueurs: CoureurSuivi[];
  /** Paramètres de Boucle de la Course, renseignés pour tous les statuts. */
  distanceBoucleMetres: number;
  denivelePositifBoucleMetres: number;
  /** Statistiques de la Course calculées par le serveur, affichées telles quelles (jamais recalculées). */
  bouclesTerminees: number;
  distanceCumuleeMetres: number;
  denivelePositifCumuleMetres: number;
  /** `null` sans Passage scanné ou sans heure de départ. */
  allureSecondesParKilometre: number | null;
  /** Non `null` seulement pour une Course `TERMINEE`. */
  dureeFinaleMillisecondes: number | null;
}
