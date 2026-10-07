import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  ChangerMotDePasseRequete,
  CompteReponse,
  ConnexionRequete,
  CreerCompteRequete,
  SupprimerCompteRequete,
} from './compte';

const URL_COMPTES = '/api/comptes';
const URL_COMPTE_COURANT = '/api/comptes/moi';
const URL_MOT_DE_PASSE = '/api/comptes/moi/mot-de-passe';
const URL_SUPPRESSION = '/api/comptes/moi/suppression';
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

  changerMotDePasse(requete: ChangerMotDePasseRequete): Observable<void> {
    return this.http.put<void>(URL_MOT_DE_PASSE, requete);
  }

  supprimerCompte(requete: SupprimerCompteRequete): Observable<void> {
    return this.http.post<void>(URL_SUPPRESSION, requete);
  }

  lireCompteCourant(): Observable<CompteReponse> {
    return this.http.get<CompteReponse>(URL_COMPTE_COURANT);
  }
}
