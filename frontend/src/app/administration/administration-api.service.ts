import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CompteReponse } from '../comptes/compte';
import { CourseReponse, DeclarerCourseRequete } from './courses/course';
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
}
