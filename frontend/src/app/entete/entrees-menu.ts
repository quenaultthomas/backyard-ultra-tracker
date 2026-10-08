import { Role } from '../comptes/compte';

/** Entrée de navigation du menu burger. */
export interface EntreeLien {
  readonly type: 'lien';
  readonly libelle: string;
  readonly testid: string;
  readonly route: string;
}

/** Commande du menu burger : son activation émet son `testid`. */
export interface EntreeAction {
  readonly type: 'action';
  readonly libelle: string;
  readonly testid: string;
}

export type EntreeMenu = EntreeLien | EntreeAction;

export const ACTION_DECONNEXION = 'bouton-deconnexion';

function lien(libelle: string, testid: string, route: string): EntreeLien {
  return { type: 'lien', libelle, testid, route };
}

const ENTREES_VISITEUR: readonly EntreeMenu[] = [
  lien('Se connecter', 'menu-lien-se-connecter', '/connexion'),
  lien('Créer un compte', 'menu-lien-creer-compte', '/creer-compte'),
];

const ADMINISTRATION = lien('Administration', 'menu-lien-administration', '/administration');

/** Entrées propres à chaque rôle (aide d'affichage : les gardes et l'API restent la référence). */
const ENTREES_DU_ROLE: Readonly<Record<Role, readonly EntreeMenu[]>> = {
  ADMIN_MASTER: [ADMINISTRATION],
  ADMIN: [ADMINISTRATION],
  BENEVOLE: [lien('Espace bénévole', 'menu-lien-espace-benevole', '/benevole')],
  COUREUR: [
    lien('Espace coureur', 'menu-lien-espace-coureur', '/coureur'),
    lien('Mes inscriptions', 'menu-lien-mes-inscriptions', '/coureur/inscriptions'),
  ],
};

const ENTREES_COMMUNES: readonly EntreeMenu[] = [
  lien('Mon compte', 'menu-lien-mon-compte', '/mon-compte'),
  { type: 'action', libelle: 'Se déconnecter', testid: ACTION_DECONNEXION },
];

/** Entrées du menu burger, dans l'ordre d'affichage ; `null` : visiteur non connecté. */
export function entreesMenu(role: Role | null): readonly EntreeMenu[] {
  return role === null ? ENTREES_VISITEUR : [...ENTREES_DU_ROLE[role], ...ENTREES_COMMUNES];
}
