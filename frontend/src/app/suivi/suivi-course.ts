import { StatutCourse } from '../administration/courses/course';

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
  tempsAvantFinBoucleMillisecondes: number | null;
  dureeEcouleeMillisecondes: number | null;
}
