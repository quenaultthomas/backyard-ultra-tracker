import { Role } from '../../comptes/compte';

/** Entrée du menu d'administration : un lien de navigation vers une section. */
export interface EntreeAdministration {
  readonly libelle: string;
  readonly route: string;
  readonly testid: string;
}

const COURSES: EntreeAdministration = {
  libelle: 'Courses',
  route: '/administration/courses',
  testid: 'menu-administration-courses',
};
const ADMINS: EntreeAdministration = {
  libelle: 'Admins',
  route: '/administration/admins',
  testid: 'menu-administration-admins',
};
const BENEVOLES: EntreeAdministration = {
  libelle: 'Bénévoles',
  route: '/administration/benevoles',
  testid: 'menu-administration-benevoles',
};

/** Entrées propres à chaque rôle (aide d'affichage : les gardes et l'API restent la référence). */
const ENTREES_DU_ROLE: Readonly<Record<Role, readonly EntreeAdministration[]>> = {
  ADMIN_MASTER: [COURSES, ADMINS, BENEVOLES],
  ADMIN: [COURSES, BENEVOLES],
  BENEVOLE: [],
  COUREUR: [],
};

/** Entrées du menu d'administration, dans l'ordre d'affichage ; `null` : session inconnue ou anonyme. */
export function entreesAdministration(role: Role | null): readonly EntreeAdministration[] {
  return role === null ? [] : ENTREES_DU_ROLE[role];
}
