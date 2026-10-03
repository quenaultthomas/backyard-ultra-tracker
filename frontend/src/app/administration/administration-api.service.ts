import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CompteReponse } from '../comptes/compte';
import { CourseReponse, DeclarerCourseRequete, ModifierCourseRequete } from './courses/course';
import { CreerAdminRequete, CreerBenevoleRequete } from './requetes-creation-compte';

const URL_ACCES = '/api/administration/acces';
const URL_ADMINS = '/api/administration/admins';
const URL_BENEVOLES = '/api/administration/benevoles';
const URL_COURSES = '/api/administration/courses';

@Injectable({ providedIn: 'root' })
export class AdministrationApiService {
  private readonly http = inject(HttpClient);

  /** 204 pour un admin ; 401 sans session, 403 `ACCES_REFUSE` pour un autre rôle. */
  verifierAcces(): Observable<void> {
    return this.http.get<void>(URL_ACCES);
  }

  /** Comptes `ADMIN`, dans l'ordre renvoyé par l'API (réservé à l'admin master). */
  listerAdmins(): Observable<CompteReponse[]> {
    return this.http.get<CompteReponse[]>(URL_ADMINS);
  }

  /** Crée un Compte `ADMIN` (réservé à l'admin master). */
  creerAdmin(requete: CreerAdminRequete): Observable<CompteReponse> {
    return this.http.post<CompteReponse>(URL_ADMINS, requete);
  }

  /** Comptes `BENEVOLE`, dans l'ordre renvoyé par l'API (admins et admin master). */
  listerBenevoles(): Observable<CompteReponse[]> {
    return this.http.get<CompteReponse[]>(URL_BENEVOLES);
  }

  /** Crée un Compte `BENEVOLE` (admins et admin master). */
  creerBenevole(requete: CreerBenevoleRequete): Observable<CompteReponse> {
    return this.http.post<CompteReponse>(URL_BENEVOLES, requete);
  }

  /** Toutes les Courses, dans l'ordre renvoyé par l'API (admins et admin master). */
  listerCourses(): Observable<CourseReponse[]> {
    return this.http.get<CourseReponse[]>(URL_COURSES);
  }

  /** Déclare une Course, créée `EN_PREPARATION` (admins et admin master). */
  declarerCourse(requete: DeclarerCourseRequete): Observable<CourseReponse> {
    return this.http.post<CourseReponse>(URL_COURSES, requete);
  }

  /** Remplace les champs modifiables d'une Course `EN_PREPARATION` (admins et admin master). */
  modifierCourse(id: string, requete: ModifierCourseRequete): Observable<CourseReponse> {
    return this.http.put<CourseReponse>(urlCourse(id), requete);
  }

  /** Envoie (ou remplace) le logo d'une Course `EN_PREPARATION`, en partie multipart `fichier`. */
  envoyerLogo(id: string, fichier: Blob): Observable<CourseReponse> {
    const corps = new FormData();
    corps.append('fichier', fichier);
    return this.http.put<CourseReponse>(`${urlCourse(id)}/logo`, corps);
  }

  /** Supprime le logo d'une Course `EN_PREPARATION` (204, même sans logo). */
  supprimerLogo(id: string): Observable<void> {
    return this.http.delete<void>(`${urlCourse(id)}/logo`);
  }
}

function urlCourse(id: string): string {
  return `${URL_COURSES}/${encodeURIComponent(id)}`;
}
