import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CompteReponse, CreerCompteRequete } from './compte';

const URL_COMPTES = '/api/comptes';

@Injectable({ providedIn: 'root' })
export class ComptesApiService {
  private readonly http = inject(HttpClient);

  creerCompte(requete: CreerCompteRequete): Observable<CompteReponse> {
    return this.http.post<CompteReponse>(URL_COMPTES, requete);
  }
}
