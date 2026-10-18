import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { SuiviCourse } from './suivi-course';

/** Appels publics du suivi d'une Course (aucune session requise). */
@Injectable({ providedIn: 'root' })
export class SuiviApiService {
  private readonly http = inject(HttpClient);

  /** Instantané public d'une Course ; 404 `COURSE_INTROUVABLE` si elle n'existe pas. */
  consulter(id: string): Observable<SuiviCourse> {
    return this.http.get<SuiviCourse>(`/api/courses/${encodeURIComponent(id)}/suivi`);
  }
}
