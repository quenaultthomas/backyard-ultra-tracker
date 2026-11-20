import { Role } from '../comptes/compte';

/** Entrée de l'accueil : une action claire, formulée comme l'intention de la personne. */
export interface EntreeAccueil {
  readonly libelle: string;
  readonly testid: string;
  readonly route: string;
  readonly principale?: boolean;
}

const MON_COMPTE: EntreeAccueil = {
  libelle: 'Gérer mon compte',
  testid: 'accueil-mon-compte',
  route: '/mon-compte',
};

const ENTREES_VISITEUR: readonly EntreeAccueil[] = [
  { libelle: 'Se connecter', testid: 'accueil-connexion', route: '/connexion', principale: true },
  { libelle: 'Créer un compte', testid: 'accueil-creer-compte', route: '/creer-compte' },
];

/** Entrées propres à chaque rôle (aide d'affichage : les gardes et l'API restent la référence). */
const ENTREES_DU_ROLE: Readonly<Record<Role, readonly EntreeAccueil[]>> = {
  COUREUR: [
    { libelle: 'Trouver une course', testid: 'accueil-trouver-course', route: '/coureur', principale: true },
    { libelle: 'Mes inscriptions', testid: 'accueil-mes-inscriptions', route: '/coureur/inscriptions' },
  ],
  BENEVOLE: [
    { libelle: 'Scanner les passages', testid: 'accueil-espace-benevole', route: '/benevole', principale: true },
  ],
  ADMIN: [
    { libelle: 'Gérer les courses', testid: 'accueil-gerer-courses', route: '/administration/courses', principale: true },
    { libelle: 'Gérer les bénévoles', testid: 'accueil-gerer-benevoles', route: '/administration/benevoles' },
  ],
  ADMIN_MASTER: [
    { libelle: 'Gérer les courses', testid: 'accueil-gerer-courses', route: '/administration/courses', principale: true },
    { libelle: 'Gérer les bénévoles', testid: 'accueil-gerer-benevoles', route: '/administration/benevoles' },
    { libelle: 'Gérer les admins', testid: 'accueil-gerer-admins', route: '/administration/admins' },
  ],
};

/** Entrées de l'accueil dans l'ordre d'affichage ; `null` : visiteur non connecté. */
export function entreesAccueil(role: Role | null): readonly EntreeAccueil[] {
  return role === null ? ENTREES_VISITEUR : [...ENTREES_DU_ROLE[role], MON_COMPTE];
}
