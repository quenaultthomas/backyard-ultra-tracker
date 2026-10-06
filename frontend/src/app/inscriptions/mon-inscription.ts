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
