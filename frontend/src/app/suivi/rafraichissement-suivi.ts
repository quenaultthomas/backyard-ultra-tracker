import { HttpStatusCode } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { RafraichissementChronometrage } from '../partage/chronometrage/rafraichissement-chronometrage';
import { SuiviApiService } from './suivi-api.service';
import { SuiviCourse } from './suivi-course';

/**
 * Actualisation du suivi public (socle commun : `RafraichissementChronometrage`).
 * Arrêtée par une Course introuvable (404) et dès qu'une réponse porte le statut `TERMINEE`.
 */
@Injectable()
export class RafraichissementSuivi extends RafraichissementChronometrage<SuiviCourse> {
  private readonly suiviApi = inject(SuiviApiService);

  protected readonly statutsDefinitifs: readonly number[] = [HttpStatusCode.NotFound];

  protected consulter(courseId: string): Observable<SuiviCourse> {
    return this.suiviApi.consulter(courseId);
  }

  protected override estFinal(instantane: SuiviCourse): boolean {
    return instantane.statut === 'TERMINEE';
  }
}
