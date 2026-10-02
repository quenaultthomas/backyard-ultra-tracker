import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';

import { Role } from '../comptes/compte';
import { estAdminMaster, estAdministrateur } from '../comptes/roles';
import { SessionService } from '../comptes/session.service';

/**
 * Aide d'affichage, après relecture de l'état : un anonyme est envoyé vers la connexion
 * (avec retour), un Compte d'un autre rôle vers l'accès refusé. Le contrôle réel est côté serveur.
 */
function reserveAuxRoles(estAutorise: (role: Role) => boolean): CanActivateFn {
  return (_route, etatRouteur) => {
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
          return estAutorise(compte.role) ? true : router.createUrlTree(['/acces-refuse']);
        }),
      );
  };
}

export const reserveAuxAdministrateurs = reserveAuxRoles(estAdministrateur);
export const reserveAAdminMaster = reserveAuxRoles(estAdminMaster);
