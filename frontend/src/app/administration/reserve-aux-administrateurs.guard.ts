import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';

import { estAdministrateur } from '../comptes/roles';
import { SessionService } from '../comptes/session.service';

/**
 * Aide d'affichage, après relecture de l'état : un anonyme est envoyé vers la connexion
 * (avec retour), un Compte non admin vers l'accès refusé. Le contrôle réel est côté serveur.
 */
export const reserveAuxAdministrateurs: CanActivateFn = (_route, etatRouteur) => {
  const router = inject(Router);
  return inject(SessionService)
    .compteUneFoisConnu()
    .pipe(
      map((compte) => {
        if (compte === null) {
          return router.createUrlTree(['/connexion'], {
            queryParams: { retour: etatRouteur.url },
          });
        }
        return estAdministrateur(compte.role) ? true : router.createUrlTree(['/acces-refuse']);
      }),
    );
};
