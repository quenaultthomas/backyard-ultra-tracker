import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CompteReponse } from '../comptes/compte';
import { CreerAdminRequete } from './admin';

const URL_ACCES = '/api/administration/acces';
const URL_ADMINS = '/api/administration/admins';

@Injectable({ providedIn: 'root' })
export class AdministrationApiService {
  private readonly http = inject(HttpClient);

  /** 204 pour un admin ; 401 sans session, 403 `ACCES_REFUSE` pour un autre rôle. */
  verifierAcces(): Observable<void> {
    return this.http.get<void>(URL_ACCES);
  }

  /** Comptes `ADMIN`, dans l'ordre renvoyé par l'API (réservé à l'admin master). */
  listerAdmins(): Observable<CompteReponse[]> {
    return this.http.get<CompteReponse[]>(URL_ADMINS);
  }

  /** Crée un Compte `ADMIN` (réservé à l'admin master). */
  creerAdmin(requete: CreerAdminRequete): Observable<CompteReponse> {
    return this.http.post<CompteReponse>(URL_ADMINS, requete);
  }
}
