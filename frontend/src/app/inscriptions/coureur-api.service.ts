import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CourseOuverteReponse, InscriptionReponse } from './course-ouverte';

const URL_COURSES_OUVERTES = '/api/coureur/courses';

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
}
