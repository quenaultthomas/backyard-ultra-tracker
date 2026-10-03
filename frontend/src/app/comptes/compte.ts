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

/** Compte renvoyé par `POST /api/comptes` (201), `POST /api/connexion` et `GET /api/comptes/moi`. */
export interface CompteReponse {
  id: string;
  pseudo: string;
  role: Role;
  /** Instant ISO-8601 UTC. */
  creeLe: string;
}

export type Role = 'ADMIN_MASTER' | 'ADMIN' | 'BENEVOLE' | 'COUREUR';
