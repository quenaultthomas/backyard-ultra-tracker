import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CourseBenevoleReponse } from './course-benevole';
import { PassageReponse, PassageRequete } from './passage';

const URL_COURSES_DU_BENEVOLE = '/api/benevole/courses';

@Injectable({ providedIn: 'root' })
export class BenevoleApiService {
  private readonly http = inject(HttpClient);

  /** Courses du bénévole connecté (tous statuts), dans l'ordre renvoyé par l'API. */
  listerMesCourses(): Observable<CourseBenevoleReponse[]> {
    return this.http.get<CourseBenevoleReponse[]>(URL_COURSES_DU_BENEVOLE);
  }

  /** Passage du coureur dont le jeton est scanné, sur la Boucle déterminée par le serveur. */
  enregistrerPassage(courseId: string, requete: PassageRequete): Observable<PassageReponse> {
    return this.http.post<PassageReponse>(
      `${URL_COURSES_DU_BENEVOLE}/${encodeURIComponent(courseId)}/passages`,
      requete,
    );
  }
}
