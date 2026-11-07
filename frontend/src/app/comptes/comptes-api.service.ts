import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  ChangerMotDePasseRequete,
  CompteCourantReponse,
  CompteReponse,
  ConnexionRequete,
  CreerCompteRequete,
  PhotoReponse,
  SupprimerCompteRequete,
} from './compte';

const URL_COMPTES = '/api/comptes';
const URL_COMPTE_COURANT = '/api/comptes/moi';
const URL_MOT_DE_PASSE = '/api/comptes/moi/mot-de-passe';
const URL_PHOTO = '/api/comptes/moi/photo';
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

  lireCompteCourant(): Observable<CompteCourantReponse> {
    return this.http.get<CompteCourantReponse>(URL_COMPTE_COURANT);
  }

  /** Ajoute ou remplace la photo du Compte de la session (partie multipart `fichier`). */
  televerserPhoto(fichier: Blob): Observable<PhotoReponse> {
    const corps = new FormData();
    corps.append('fichier', fichier, 'photo.jpg');
    return this.http.put<PhotoReponse>(URL_PHOTO, corps);
  }

  /** Retire la photo du Compte de la session (204, même sans photo). */
  retirerPhoto(): Observable<void> {
    return this.http.delete<void>(URL_PHOTO);
  }
}
