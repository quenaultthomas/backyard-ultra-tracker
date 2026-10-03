/** Statut d'une Course, tel que renvoyé par l'API. */
export type StatutCourse = 'EN_PREPARATION' | 'EN_COURS' | 'TERMINEE';

export const LIBELLES_STATUT_COURSE: Readonly<Record<StatutCourse, string>> = {
  EN_PREPARATION: 'En préparation',
  EN_COURS: 'En cours',
  TERMINEE: 'Terminée',
};

/** Corps de `POST /api/administration/courses` ; `date` au format `aaaa-mm-jj`. */
export interface DeclarerCourseRequete {
  nom: string;
  date: string;
  distanceBoucleMetres: number;
  dureeBoucleMinutes: number;
  denivelePositifBoucleMetres: number;
  nombreMaxParticipants: number;
  nombreMaxBoucles: number;
}

/** Corps de `PUT /api/administration/courses/{id}` : strictement le schéma de la déclaration. */
export type ModifierCourseRequete = DeclarerCourseRequete;

/**
 * Course renvoyée par `POST` (201), `PUT` (200), `GET /api/administration/courses` et
 * `PUT /api/administration/courses/{id}/logo` (200).
 */
export interface CourseReponse {
  id: string;
  nom: string;
  /** Jour de la Course, `aaaa-mm-jj`, sans heure ni fuseau. */
  date: string;
  statut: StatutCourse;
  distanceBoucleMetres: number;
  dureeBoucleMinutes: number;
  denivelePositifBoucleMetres: number;
  nombreMaxParticipants: number;
  nombreMaxBoucles: number;
  /** Adresse publique du logo (`/api/courses/<id>/logo?v=…`), `null` sans logo. */
  logoUrl: string | null;
}

/** Fiche renvoyée par `GET /api/administration/courses/{id}` et `PUT .../{id}/benevoles` (200). */
export interface FicheCourseReponse extends CourseReponse {
  /** Identifiants des Comptes `BENEVOLE` affectés, triés par l'API, `[]` si aucun. */
  benevoleIds: string[];
}

/** Corps de `PUT /api/administration/courses/{id}/benevoles` : ensemble complet souhaité. */
export interface AffecterBenevolesRequete {
  benevoleIds: string[];
}
