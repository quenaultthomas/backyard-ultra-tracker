import { Role } from './compte';

/** Rôles donnant accès à l'espace d'administration (contrôle serveur : `/api/administration/**`). */
export type RoleAdministrateur = Extract<Role, 'ADMIN_MASTER' | 'ADMIN'>;

export function estAdministrateur(role: Role): role is RoleAdministrateur {
  return role === 'ADMIN_MASTER' || role === 'ADMIN';
}

/** Seul rôle autorisé à gérer les admins (contrôle serveur : `/api/administration/admins/**`). */
export function estAdminMaster(role: Role): boolean {
  return role === 'ADMIN_MASTER';
}

/** Seul rôle ayant accès à l'espace bénévole (aide d'affichage, aucune donnée exposée). */
export function estBenevole(role: Role): boolean {
  return role === 'BENEVOLE';
}

/** Seul rôle ayant accès à l'espace coureur (contrôle serveur : `/api/coureur/**`). */
export function estCoureur(role: Role): boolean {
  return role === 'COUREUR';
}

/** Libellé affiché d'un rôle. */
export const LIBELLES_ROLE: Readonly<Record<Role, string>> = {
  ADMIN_MASTER: 'Administrateur master',
  ADMIN: 'Administrateur',
  BENEVOLE: 'Bénévole',
  COUREUR: 'Coureur',
};
