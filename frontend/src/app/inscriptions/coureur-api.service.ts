import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CourseOuverteReponse, InscriptionReponse } from './course-ouverte';
import { MonInscriptionReponse } from './mon-inscription';

const URL_COURSES_OUVERTES = '/api/coureur/courses';
const URL_MES_INSCRIPTIONS = '/api/coureur/inscriptions';

@Injectable({ providedIn: 'root' })
export class CoureurApiService {
  private readonly http = inject(HttpClient);

  /** Courses ouvertes, dans l'ordre renvoyé par l'API, avec l'Inscription du coureur connecté. */
  listerCoursesOuvertes(): Observable<CourseOuverteReponse[]> {
    return this.http.get<CourseOuverteReponse[]>(URL_COURSES_OUVERTES);
  }

  /** Inscrit le coureur connecté à la Course ; le dossard est attribué par l'API. */
  sinscrire(courseId: string): Observable<InscriptionReponse> {
    return this.http.post<InscriptionReponse>(
      `${URL_COURSES_OUVERTES}/${encodeURIComponent(courseId)}/inscriptions`,
      null,
    );
  }

  /** Inscriptions du coureur connecté (toutes Courses, tous statuts), jeton QR compris, dans l'ordre de l'API. */
  listerMesInscriptions(): Observable<MonInscriptionReponse[]> {
    return this.http.get<MonInscriptionReponse[]>(URL_MES_INSCRIPTIONS);
  }
}
