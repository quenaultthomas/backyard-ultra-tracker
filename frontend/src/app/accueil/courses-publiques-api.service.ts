import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CoursesPubliquesReponse } from './courses-publiques';

/** Liste publique des Courses (aucune session requise). */
@Injectable({ providedIn: 'root' })
export class CoursesPubliquesApiService {
  private readonly http = inject(HttpClient);

  /** Courses en cours, à venir et terminées, dans l'ordre de l'API. */
  lister(): Observable<CoursesPubliquesReponse> {
    return this.http.get<CoursesPubliquesReponse>('/api/courses');
  }
}
