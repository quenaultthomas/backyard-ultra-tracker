import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

const URL_CSRF = '/api/csrf';

/**
 * Obtient le jeton CSRF : l'API pose le cookie `XSRF-TOKEN`, que `HttpClient` renvoie ensuite
 * automatiquement dans l'en-tête `X-XSRF-TOKEN` des requêtes modifiantes.
 */
@Injectable({ providedIn: 'root' })
export class CsrfService {
  private readonly http = inject(HttpClient);

  obtenirJeton(): Observable<void> {
    return this.http.get<void>(URL_CSRF);
  }
}
