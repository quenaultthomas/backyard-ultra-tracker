import { ErreursSpecifiques } from '../../../comptes/erreurs-compte';
import { ProblemDetail } from '../../../partage/probleme';
import { listeNomsCourses, nomsCoursesBloquantes } from '../courses-bloquantes';

const SANS_COURSE = 'Impossible : ce coureur est inscrit à une course.';

/** 409 `COUREUR_INSCRIT_COURSE_EN_PREPARATION` : Courses non démarrées, d'après `courses`. */
function refusCourseEnPreparation(probleme: ProblemDetail): { generale: string } {
  const noms = nomsCoursesBloquantes(probleme);
  if (noms.length === 0) {
    return { generale: SANS_COURSE };
  }
  const liste = listeNomsCourses(noms);
  const inscription =
    noms.length === 1
      ? `inscrit à la course ${liste} qui n'a pas démarré`
      : `inscrit aux courses ${liste} qui n'ont pas démarré`;
  return {
    generale: `Impossible : ce coureur est ${inscription}. Il doit d'abord se désinscrire.`,
  };
}

/** 409 `COUREUR_INSCRIT_COURSE_EN_COURS` : Courses en cours, d'après `courses`. */
function refusCourseEnCours(probleme: ProblemDetail): { generale: string } {
  const noms = nomsCoursesBloquantes(probleme);
  if (noms.length === 0) {
    return { generale: SANS_COURSE };
  }
  const liste = listeNomsCourses(noms);
  return {
    generale:
      noms.length === 1
        ? `Impossible : ce coureur participe à la course en cours ${liste}. ` +
          'Réessayez à la fin de la course.'
        : `Impossible : ce coureur participe aux courses en cours ${liste}. ` +
          'Réessayez à la fin des courses.',
  };
}

/** Refus du passage d'un coureur en bénévole (le serveur décide, le front met en mots). */
export const ERREURS_PASSAGE_BENEVOLE: ErreursSpecifiques<never> = {
  COMPTE_INTROUVABLE: { generale: "Ce coureur n'existe plus ou n'est plus un coureur." },
  COUREUR_INSCRIT_COURSE_EN_PREPARATION: refusCourseEnPreparation,
  COUREUR_INSCRIT_COURSE_EN_COURS: refusCourseEnCours,
};
