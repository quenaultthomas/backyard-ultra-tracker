/** Corps renvoyé par GET /api/sante (alias Caddy de /actuator/health). */
export interface ReponseSante {
  status: string;
}

/** État de l'API tel qu'affiché par l'indicateur de l'accueil. */
export type EtatApi = 'VERIFICATION' | 'DISPONIBLE' | 'INDISPONIBLE';
