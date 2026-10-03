/** Corps de `POST /api/administration/admins` ; la réponse 201 est un `CompteReponse`. */
export interface CreerAdminRequete {
  pseudo: string;
  motDePasse: string;
}

/** Corps de `POST /api/administration/benevoles` ; la réponse 201 est un `CompteReponse`. */
export interface CreerBenevoleRequete {
  pseudo: string;
  motDePasse: string;
}
