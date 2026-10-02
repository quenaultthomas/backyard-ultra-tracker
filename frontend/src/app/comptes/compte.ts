/** Corps de `POST /api/comptes`. */
export interface CreerCompteRequete {
  pseudo: string;
  motDePasse: string;
}

/** Réponse 201 de `POST /api/comptes`. */
export interface CompteReponse {
  id: string;
  pseudo: string;
  role: Role;
  /** Instant ISO-8601 UTC. */
  creeLe: string;
}

export type Role = 'ADMIN_MASTER' | 'ADMIN' | 'BENEVOLE' | 'COUREUR';
