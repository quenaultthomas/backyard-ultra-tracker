import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, defer, map, of, repeat, timeout } from 'rxjs';

import { EtatApi, ReponseSante } from './reponse-sante';

const URL_SANTE = '/api/sante';
const DELAI_MAX_MS = 3000;
const INTERVALLE_MS = 5000;

@Injectable({ providedIn: 'root' })
export class SanteApiService {
  private readonly http = inject(HttpClient);

  /**
   * Interroge l'API au chargement puis toutes les 5 s après la fin de la requête précédente
   * (jamais de requêtes concurrentes).
   */
  surveillerEtat(): Observable<EtatApi> {
    return defer(() => this.verifierEtat()).pipe(repeat({ delay: INTERVALLE_MS }));
  }

  private verifierEtat(): Observable<EtatApi> {
    return this.http
      .get<ReponseSante>(URL_SANTE, {
        headers: new HttpHeaders({ Accept: 'application/json' }),
        observe: 'response',
      })
      .pipe(
        timeout(DELAI_MAX_MS),
        map((reponse): EtatApi =>
          reponse.status === 200 && reponse.body?.status === 'UP' ? 'DISPONIBLE' : 'INDISPONIBLE',
        ),
        catchError(() => of<EtatApi>('INDISPONIBLE')),
      );
  }
}
