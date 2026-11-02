import { Observable } from 'rxjs';

import { CompteReponse } from '../../comptes/compte';
import { ErreursSpecifiques } from '../../comptes/erreurs-compte';
import { AdministrationApiService } from '../administration-api.service';
import { CreerAdminRequete, CreerBenevoleRequete } from '../requetes-creation-compte';
import {
  CODE_BENEVOLE_AFFECTE_COURSE_EN_COURS,
  refusBenevoleAffecteCourseEnCours,
} from './suppression-compte/refus-suppression-benevole';

/** Suppression définitive d'un Compte depuis sa ligne : appel API, libellés et refus propres au rôle. */
export interface SuppressionComptesGeres {
  readonly confirmation: (pseudo: string) => string;
  readonly succes: (pseudo: string) => string;
  /** Messages des refus propres au rôle (le 404 `COMPTE_INTROUVABLE` est commun). */
  readonly erreurs: ErreursSpecifiques<never>;
  readonly supprimer: (api: AdministrationApiService, id: string) => Observable<void>;
}

/** Type de Compte géré par l'écran de gestion : appels API, libellés et préfixes `data-testid`. */
export interface ComptesGeres {
  /** Route de l'écran, pour le retour après connexion. */
  readonly ecran: string;
  /** Préfixe des identifiants du formulaire et des lignes (`admin`, `benevole`). */
  readonly prefixe: string;
  /** Préfixe des identifiants du titre et de la liste (`admins`, `benevoles`). */
  readonly prefixePluriel: string;
  readonly titre: string;
  readonly titreListe: string;
  readonly listeVide: string;
  /** Aide de l'état vide, qui désigne le bouton d'ouverture du formulaire. */
  readonly aideListeVide: string;
  readonly erreurChargement: string;
  /** Libellé constant du bouton qui ouvre le formulaire de création. */
  readonly boutonOuvrir: string;
  readonly titreCreation: string;
  readonly boutonCreer: string;
  readonly succes: (pseudo: string) => string;
  readonly lister: (api: AdministrationApiService) => Observable<CompteReponse[]>;
  readonly creer: (
    api: AdministrationApiService,
    requete: CreerAdminRequete & CreerBenevoleRequete,
  ) => Observable<CompteReponse>;
  /** Texte de la confirmation de désactivation, propre au rôle (conséquences annoncées). */
  readonly confirmationDesactivation: (pseudo: string) => string;
  readonly desactiver: (api: AdministrationApiService, id: string) => Observable<void>;
  readonly reactiver: (api: AdministrationApiService, id: string) => Observable<void>;
  /** Absente : aucun bouton « Supprimer » sur les lignes. */
  readonly suppression?: SuppressionComptesGeres;
}

export const ADMINS: ComptesGeres = {
  ecran: '/administration/admins',
  prefixe: 'admin',
  prefixePluriel: 'admins',
  titre: 'Gestion des administrateurs',
  titreListe: 'Administrateurs',
  listeVide: 'Aucun administrateur pour le moment.',
  aideListeVide: 'Créez-en un avec le bouton « Nouvel admin ».',
  erreurChargement: 'Impossible de charger la liste des administrateurs. Réessayez plus tard.',
  boutonOuvrir: 'Nouvel admin',
  titreCreation: 'Créer un administrateur',
  boutonCreer: "Créer l'administrateur",
  succes: (pseudo) => `L'administrateur ${pseudo} a été créé.`,
  lister: (api) => api.listerAdmins(),
  creer: (api, requete) => api.creerAdmin(requete),
  confirmationDesactivation: (pseudo) =>
    `Désactiver l'administrateur ${pseudo} ? Il ne pourra plus se connecter. Ses sessions seront fermées.`,
  desactiver: (api, id) => api.desactiverAdmin(id),
  reactiver: (api, id) => api.reactiverAdmin(id),
  suppression: {
    confirmation: (pseudo) =>
      `Supprimer définitivement l'administrateur ${pseudo} ? Son compte sera supprimé, son ` +
      `pseudo pourra être réutilisé et ses sessions seront fermées. Cette action est irréversible.`,
    succes: (pseudo) => `L'administrateur ${pseudo} a été supprimé.`,
    erreurs: {
      DERNIER_ADMIN_ACTIF: { generale: "Impossible : c'est le dernier administrateur actif." },
      ADMIN_MASTER_NON_SUPPRIMABLE: {
        generale: "Impossible : l'administrateur principal ne peut pas être supprimé.",
      },
    },
    supprimer: (api, id) => api.supprimerAdmin(id),
  },
};

export const BENEVOLES: ComptesGeres = {
  ecran: '/administration/benevoles',
  prefixe: 'benevole',
  prefixePluriel: 'benevoles',
  titre: 'Gestion des bénévoles',
  titreListe: 'Bénévoles',
  listeVide: 'Aucun bénévole pour le moment.',
  aideListeVide: 'Créez-en un avec le bouton « Nouveau bénévole ».',
  erreurChargement: 'Impossible de charger la liste des bénévoles. Réessayez plus tard.',
  boutonOuvrir: 'Nouveau bénévole',
  titreCreation: 'Créer un bénévole',
  boutonCreer: 'Créer le bénévole',
  succes: (pseudo) => `Le bénévole ${pseudo} a été créé.`,
  lister: (api) => api.listerBenevoles(),
  creer: (api, requete) => api.creerBenevole(requete),
  confirmationDesactivation: (pseudo) =>
    `Désactiver le bénévole ${pseudo} ? Il ne pourra plus se connecter et sera retiré des ` +
    `courses qui n'ont pas démarré. Ses passages et ses affectations aux courses démarrées ` +
    `sont conservés.`,
  desactiver: (api, id) => api.desactiverBenevole(id),
  reactiver: (api, id) => api.reactiverBenevole(id),
  suppression: {
    confirmation: (pseudo) =>
      `Supprimer définitivement le bénévole ${pseudo} ? Son compte sera supprimé et son pseudo ` +
      `pourra être réutilisé. Il sera retiré de toutes ses courses, y compris terminées. Les ` +
      `passages qu'il a scannés sont conservés. Cette action est irréversible.`,
    succes: (pseudo) => `Le bénévole ${pseudo} a été supprimé.`,
    erreurs: { [CODE_BENEVOLE_AFFECTE_COURSE_EN_COURS]: refusBenevoleAffecteCourseEnCours },
    supprimer: (api, id) => api.supprimerBenevole(id),
  },
};
