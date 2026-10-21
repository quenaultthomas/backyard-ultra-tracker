/** Course de la liste publique (`GET /api/courses`) : liste fermée de champs, sans statut (la rubrique le porte). */
export interface CoursePubliqueReponse {
  id: string;
  nom: string;
  /** Jour de la Course, `aaaa-mm-jj`. */
  date: string;
  /** Adresse publique versionnée du logo, `null` sans logo. */
  logoUrl: string | null;
}

/** Courses publiques regroupées par statut, chaque rubrique triée par l'API ; `[]` si vide. */
export interface CoursesPubliquesReponse {
  enCours: CoursePubliqueReponse[];
  aVenir: CoursePubliqueReponse[];
  terminees: CoursePubliqueReponse[];
}
