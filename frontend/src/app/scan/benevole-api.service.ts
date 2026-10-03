import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CourseBenevoleReponse } from './course-benevole';

const URL_COURSES_DU_BENEVOLE = '/api/benevole/courses';

@Injectable({ providedIn: 'root' })
export class BenevoleApiService {
  private readonly http = inject(HttpClient);

  /** Courses du bénévole connecté (tous statuts), dans l'ordre renvoyé par l'API. */
  listerMesCourses(): Observable<CourseBenevoleReponse[]> {
    return this.http.get<CourseBenevoleReponse[]>(URL_COURSES_DU_BENEVOLE);
  }
}
