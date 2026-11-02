import { ProblemDetail } from '../../../partage/probleme';

/** Course `EN_COURS` qui bloque la suppression d'un bénévole (propriété `courses` du 409). */
export interface CourseEnCoursBloquante {
  id: string;
  nom: string;
}

/** Corps du 409 `BENEVOLE_AFFECTE_COURSE_EN_COURS`. */
export interface ProblemeBenevoleAffecteCourseEnCours extends ProblemDetail {
  courses?: CourseEnCoursBloquante[];
}

export const CODE_BENEVOLE_AFFECTE_COURSE_EN_COURS = 'BENEVOLE_AFFECTE_COURSE_EN_COURS';

/** Message du refus, construit à partir des noms des Courses bloquantes renvoyées par l'API. */
export function refusBenevoleAffecteCourseEnCours(probleme: ProblemDetail): { generale: string } {
  const { courses } = probleme as ProblemeBenevoleAffecteCourseEnCours;
  const noms = Array.isArray(courses)
    ? courses.map((course) => course?.nom).filter((nom) => typeof nom === 'string')
    : [];
  if (noms.length === 0) {
    return { generale: 'Impossible : ce bénévole est affecté à une course en cours.' };
  }
  const liste = noms.map((nom) => `« ${nom} »`).join(', ');
  return {
    generale:
      noms.length === 1
        ? `Impossible : ce bénévole est affecté à la course en cours ${liste}. ` +
          'Retirez-le de cette course avant de le supprimer.'
        : `Impossible : ce bénévole est affecté aux courses en cours ${liste}. ` +
          'Retirez-le de ces courses avant de le supprimer.',
  };
}
