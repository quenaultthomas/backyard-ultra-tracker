/** Statut d'une Inscription, tel que renvoyé par l'API. */
export type StatutInscription = 'EN_COURSE' | 'ABANDON' | 'VAINQUEUR';

/** Inscription du coureur connecté (`POST .../inscriptions` 201, `monInscription`). Jamais de jeton QR. */
export interface InscriptionReponse {
  id: string;
  courseId: string;
  dossard: number;
  statut: StatutInscription;
}

/** Course renvoyée par `GET /api/coureur/courses` : une Course ouverte aux inscriptions. */
export interface CourseOuverteReponse {
  id: string;
  nom: string;
  /** Jour de la Course, `aaaa-mm-jj`. */
  date: string;
  distanceBoucleMetres: number;
  dureeBoucleMinutes: number;
  denivelePositifBoucleMetres: number;
  nombreMaxParticipants: number;
  nombreMaxBoucles: number;
  /** Adresse publique du logo, `null` sans logo. */
  logoUrl: string | null;
  /** Inscription du coureur connecté à cette Course, `null` s'il n'y est pas inscrit. */
  monInscription: InscriptionReponse | null;
  /** Course pleine (calculé par le serveur) ; toujours présent. */
  complete: boolean;
}
