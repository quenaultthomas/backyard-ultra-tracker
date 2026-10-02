import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CompteReponse, ConnexionRequete, CreerCompteRequete } from './compte';

const URL_COMPTES = '/api/comptes';
const URL_COMPTE_COURANT = '/api/comptes/moi';
const URL_CONNEXION = '/api/connexion';
const URL_DECONNEXION = '/api/deconnexion';

@Injectable({ providedIn: 'root' })
export class ComptesApiService {
  private readonly http = inject(HttpClient);

  creerCompte(requete: CreerCompteRequete): Observable<CompteReponse> {
    return this.http.post<CompteReponse>(URL_COMPTES, requete);
  }

  connecter(requete: ConnexionRequete): Observable<CompteReponse> {
    return this.http.post<CompteReponse>(URL_CONNEXION, requete);
  }

  deconnecter(): Observable<void> {
    return this.http.post<void>(URL_DECONNEXION, null);
  }

  lireCompteCourant(): Observable<CompteReponse> {
    return this.http.get<CompteReponse>(URL_COMPTE_COURANT);
  }
}
