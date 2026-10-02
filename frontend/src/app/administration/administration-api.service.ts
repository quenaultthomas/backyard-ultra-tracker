import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

const URL_ACCES = '/api/administration/acces';

@Injectable({ providedIn: 'root' })
export class AdministrationApiService {
  private readonly http = inject(HttpClient);

  /** 204 pour un admin ; 401 sans session, 403 `ACCES_REFUSE` pour un autre rôle. */
  verifierAcces(): Observable<void> {
    return this.http.get<void>(URL_ACCES);
  }
}
