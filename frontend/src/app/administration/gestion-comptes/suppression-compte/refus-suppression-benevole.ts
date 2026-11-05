import { ProblemDetail } from '../../../partage/probleme';
import { listeNomsCourses, nomsCoursesBloquantes } from '../courses-bloquantes';

export const CODE_BENEVOLE_AFFECTE_COURSE_EN_COURS = 'BENEVOLE_AFFECTE_COURSE_EN_COURS';

/** Message du refus, construit à partir des noms des Courses bloquantes renvoyées par l'API. */
export function refusBenevoleAffecteCourseEnCours(probleme: ProblemDetail): { generale: string } {
  const noms = nomsCoursesBloquantes(probleme);
  if (noms.length === 0) {
    return { generale: 'Impossible : ce bénévole est affecté à une course en cours.' };
  }
  const liste = listeNomsCourses(noms);
  return {
    generale:
      noms.length === 1
        ? `Impossible : ce bénévole est affecté à la course en cours ${liste}. ` +
          'Retirez-le de cette course avant de le supprimer.'
        : `Impossible : ce bénévole est affecté aux courses en cours ${liste}. ` +
          'Retirez-le de ces courses avant de le supprimer.',
  };
}
