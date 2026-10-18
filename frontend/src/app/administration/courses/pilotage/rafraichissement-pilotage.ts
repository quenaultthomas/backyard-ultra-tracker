import { HttpStatusCode } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { RafraichissementChronometrage } from '../../../partage/chronometrage/rafraichissement-chronometrage';
import { AdministrationApiService } from '../../administration-api.service';
import { PilotageCourse } from '../pilotage-course';

/**
 * Actualisation de l'écran de pilotage (socle commun : `RafraichissementChronometrage`).
 * Arrêtée par les refus de session, de rôle, une Course introuvable ou non démarrée.
 */
@Injectable()
export class RafraichissementPilotage extends RafraichissementChronometrage<PilotageCourse> {
  private readonly administrationApi = inject(AdministrationApiService);

  protected readonly statutsDefinitifs: readonly number[] = [
    HttpStatusCode.Unauthorized,
    HttpStatusCode.Forbidden,
    HttpStatusCode.NotFound,
    HttpStatusCode.Conflict,
  ];

  protected consulter(courseId: string): Observable<PilotageCourse> {
    return this.administrationApi.consulterPilotage(courseId);
  }
}
