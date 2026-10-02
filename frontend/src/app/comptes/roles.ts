import { Role } from './compte';

/** Rôles donnant accès à l'espace d'administration (contrôle serveur : `/api/administration/**`). */
export type RoleAdministrateur = Extract<Role, 'ADMIN_MASTER' | 'ADMIN'>;

export function estAdministrateur(role: Role): role is RoleAdministrateur {
  return role === 'ADMIN_MASTER' || role === 'ADMIN';
}
