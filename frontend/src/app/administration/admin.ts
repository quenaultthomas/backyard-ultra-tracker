/** Corps de `POST /api/administration/admins` ; la réponse 201 est un `CompteReponse`. */
export interface CreerAdminRequete {
  pseudo: string;
  motDePasse: string;
}
