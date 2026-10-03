import { ErreursSpecifiques } from '../../comptes/erreurs-compte';
import { lireProbleme } from '../../partage/probleme';
import { ChampCourse } from './champs-course';

/** Adresse de l'écran de gestion des Courses (retour après connexion). */
export const ECRAN_GESTION_COURSES = '/administration/courses';

export const MESSAGE_COURSE_INTROUVABLE = "Cette course n'existe plus.";

/** Clé de l'état de navigation portant un message d'erreur à afficher sur la gestion des Courses. */
export const ETAT_MESSAGE_ERREUR = 'messageErreur';

/**
 * Refus d'une modification de la Course ou de son logo qui imposent de recharger la liste
 * (la Course n'est plus modifiable ou n'existe plus).
 */
export const ERREURS_COURSE_NON_EDITABLE: ErreursSpecifiques<ChampCourse> = {
  COURSE_NON_MODIFIABLE: ({ detail }) => ({ generale: detail }),
  COURSE_INTROUVABLE: { generale: MESSAGE_COURSE_INTROUVABLE },
};

/** `true` si l'API refuse l'action parce que la Course n'est plus modifiable ou n'existe plus. */
export function estCourseNonEditable(erreur: unknown): boolean {
  const code = lireProbleme(erreur)?.code;
  return code !== undefined && Object.hasOwn(ERREURS_COURSE_NON_EDITABLE, code);
}
