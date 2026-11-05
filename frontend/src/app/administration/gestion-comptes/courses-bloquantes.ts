import { ProblemDetail } from '../../partage/probleme';

/** Course qui motive un refus 409 sur un Compte (propriété `courses` du `ProblemDetail`). */
export interface CourseBloquante {
  id: string;
  nom: string;
}

/** Corps d'un 409 qui liste les Courses à l'origine du refus. */
export interface ProblemeCoursesBloquantes extends ProblemDetail {
  courses?: CourseBloquante[];
}

/** Noms des Courses renvoyées par l'API, dans son ordre ; vide si `courses` est absent ou invalide. */
export function nomsCoursesBloquantes(probleme: ProblemDetail): string[] {
  const { courses } = probleme as ProblemeCoursesBloquantes;
  return Array.isArray(courses)
    ? courses.map((course) => course?.nom).filter((nom) => typeof nom === 'string')
    : [];
}

/** Noms entre guillemets français, séparés par des virgules : « A », « B ». */
export function listeNomsCourses(noms: readonly string[]): string {
  return noms.map((nom) => `« ${nom} »`).join(', ');
}
