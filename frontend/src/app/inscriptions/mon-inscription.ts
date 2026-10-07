import { StatutCourse } from '../administration/courses/course';
import { StatutInscription } from './course-ouverte';

/** Élément de `GET /api/coureur/inscriptions` : une Inscription du coureur connecté, avec sa Course. */
export interface MonInscriptionReponse {
  id: string;
  courseId: string;
  courseNom: string;
  /** Jour de la Course, `aaaa-mm-jj`. */
  courseDate: string;
  courseStatut: StatutCourse;
  /** Adresse publique du logo, `null` sans logo. */
  logoUrl: string | null;
  dossard: number;
  statut: StatutInscription;
  /**
   * Jeton opaque, uniquement pour dessiner le QR code : jamais affiché en texte, ni placé dans
   * un attribut, une URL ou le stockage du navigateur, ni journalisé.
   */
  jetonQr: string;
}

export const LIBELLES_STATUT_INSCRIPTION: Readonly<Record<StatutInscription, string>> = {
  EN_COURSE: 'En course',
  ABANDON: 'Abandon',
  VAINQUEUR: 'Vainqueur',
};

/** Classes de la pastille de statut d'une Inscription (affichage seulement, docs/design.md). */
export const PASTILLES_STATUT_INSCRIPTION: Readonly<Record<StatutInscription, string>> = {
  EN_COURSE: 'pastille-statut pastille-statut--en-course',
  ABANDON: 'pastille-statut pastille-statut--abandon',
  VAINQUEUR: 'pastille-statut pastille-statut--vainqueur',
};
