/** Corps de `POST /api/comptes`. */
export interface CreerCompteRequete {
  pseudo: string;
  motDePasse: string;
}

/** Corps de `POST /api/connexion`. */
export interface ConnexionRequete {
  pseudo: string;
  motDePasse: string;
}

/** Corps de `PUT /api/comptes/moi/mot-de-passe` (réponse 204 sans corps). */
export interface ChangerMotDePasseRequete {
  motDePasseActuel: string;
  nouveauMotDePasse: string;
}

/** Corps de `POST /api/comptes/moi/suppression` (réponse 204 sans corps). */
export interface SupprimerCompteRequete {
  motDePasseActuel: string;
}

/** Compte renvoyé par `POST /api/comptes` (201), `POST /api/connexion` et `GET /api/comptes/moi`. */
export interface CompteReponse {
  id: string;
  pseudo: string;
  role: Role;
  /** Instant ISO-8601 UTC. */
  creeLe: string;
  /** Faux pour un Compte désactivé (listes d'administration) ; toujours vrai ailleurs. */
  actif: boolean;
}

/** Compte renvoyé par `GET /api/comptes/moi` : seule réponse portant la photo de profil. */
export interface CompteCourantReponse extends CompteReponse {
  /** URL relative publique `/api/photos/<jeton>`, `null` sans photo. */
  photoUrl: string | null;
}

/** Réponse 200 de `PUT /api/comptes/moi/photo`. */
export interface PhotoReponse {
  photoUrl: string;
}

export type Role = 'ADMIN_MASTER' | 'ADMIN' | 'BENEVOLE' | 'COUREUR';
