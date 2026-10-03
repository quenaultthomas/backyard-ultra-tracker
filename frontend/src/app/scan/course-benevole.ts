import { StatutCourse } from '../administration/courses/course';

/** Course renvoyée par `GET /api/benevole/courses` : une Course à laquelle le bénévole est affecté. */
export interface CourseBenevoleReponse {
  id: string;
  nom: string;
  /** Jour de la Course, `aaaa-mm-jj`. */
  date: string;
  statut: StatutCourse;
  /** Adresse publique du logo, `null` sans logo. */
  logoUrl: string | null;
}
