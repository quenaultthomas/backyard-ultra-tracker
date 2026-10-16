import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

const URL_CSRF = '/api/csrf';

/**
 * Obtient le jeton CSRF : l'API pose le cookie `XSRF-TOKEN`, que `HttpClient` renvoie ensuite
 * automatiquement dans l'en-tête `X-XSRF-TOKEN` des requêtes modifiantes.
 */
@Injectable({ providedIn: 'root' })
export class CsrfService {
  private readonly http = inject(HttpClient);

  /**
   * Demande un nouveau jeton. Un échec n'est pas signalé : l'envoi suivant sans jeton valide
   * reçoit un 403 `CSRF_INVALIDE`, qui affiche « La page a expiré » et redemande un jeton.
   */
  renouvelerJeton(): Observable<void> {
    return this.obtenirJeton().pipe(catchError(() => of(undefined)));
  }

  /** Comme `renouvelerJeton`, mais un échec est propagé (la file de scans le traite en panne). */
  obtenirJeton(): Observable<void> {
    return this.http.get<void>(URL_CSRF).pipe(map(() => undefined));
  }
}
