import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { CoureurSuivi, SuiviCourse } from './suivi-course';

/** Appels publics du suivi d'une Course (aucune session requise). */
@Injectable({ providedIn: 'root' })
export class SuiviApiService {
  private readonly http = inject(HttpClient);

  /** Instantané public d'une Course ; 404 `COURSE_INTROUVABLE` si elle n'existe pas. */
  consulter(id: string): Observable<SuiviCourse> {
    return this.http
      .get<SuiviCourse>(`/api/courses/${encodeURIComponent(id)}/suivi`)
      .pipe(map(normaliserSuivi));
  }
}

/**
 * Tolérance de lecture : listes absentes traitées comme vides et `photoUrl` absente comme `null`,
 * pour qu'une réponse incomplète n'interrompe jamais l'affichage. Aucune donnée n'est déduite.
 */
function normaliserSuivi(suivi: SuiviCourse): SuiviCourse {
  return {
    ...suivi,
    coureursEnCourse: normaliserCoureurs(suivi.coureursEnCourse),
    abandons: suivi.abandons ?? [],
    vainqueurs: normaliserCoureurs(suivi.vainqueurs),
  };
}

function normaliserCoureurs(coureurs: CoureurSuivi[] | null | undefined): CoureurSuivi[] {
  return (coureurs ?? []).map((coureur) => ({ ...coureur, photoUrl: coureur.photoUrl ?? null }));
}
