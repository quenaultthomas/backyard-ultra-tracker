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

/** Course renvoyée par `POST` (201) et `GET /api/administration/courses`. */
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
}
