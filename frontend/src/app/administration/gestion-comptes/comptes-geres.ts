import { Observable } from 'rxjs';

import { CompteReponse } from '../../comptes/compte';
import { AdministrationApiService } from '../administration-api.service';
import { CreerAdminRequete, CreerBenevoleRequete } from '../requetes-creation-compte';

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
  readonly erreurChargement: string;
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
}

export const ADMINS: ComptesGeres = {
  ecran: '/administration/admins',
  prefixe: 'admin',
  prefixePluriel: 'admins',
  titre: 'Gestion des administrateurs',
  titreListe: 'Administrateurs',
  listeVide: 'Aucun administrateur pour le moment.',
  erreurChargement: 'Impossible de charger la liste des administrateurs. Réessayez plus tard.',
  titreCreation: 'Créer un administrateur',
  boutonCreer: "Créer l'administrateur",
  succes: (pseudo) => `L'administrateur ${pseudo} a été créé.`,
  lister: (api) => api.listerAdmins(),
  creer: (api, requete) => api.creerAdmin(requete),
  confirmationDesactivation: (pseudo) =>
    `Désactiver l'administrateur ${pseudo} ? Il ne pourra plus se connecter. Ses sessions seront fermées.`,
  desactiver: (api, id) => api.desactiverAdmin(id),
  reactiver: (api, id) => api.reactiverAdmin(id),
};

export const BENEVOLES: ComptesGeres = {
  ecran: '/administration/benevoles',
  prefixe: 'benevole',
  prefixePluriel: 'benevoles',
  titre: 'Gestion des bénévoles',
  titreListe: 'Bénévoles',
  listeVide: 'Aucun bénévole pour le moment.',
  erreurChargement: 'Impossible de charger la liste des bénévoles. Réessayez plus tard.',
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
};
