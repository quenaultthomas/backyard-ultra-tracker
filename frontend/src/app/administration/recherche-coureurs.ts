/** Compte coureur trouvé par la recherche par pseudo : identifiant et pseudo seulement. */
export interface CoureurTrouve {
  readonly id: string;
  readonly pseudo: string;
}

/** Réponse de `GET /api/administration/coureurs?q=…` : au plus 10 coureurs, triés par le serveur. */
export interface RechercheCoureursReponse {
  readonly coureurs: CoureurTrouve[];
  /** Vrai quand d'autres coureurs correspondent au-delà des résultats renvoyés. */
  readonly plusDeResultats: boolean;
}
