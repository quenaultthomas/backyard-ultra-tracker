import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';

import { Role } from './compte';
import { NavigationApresConnexionService } from './navigation-apres-connexion.service';
import { accueilDuRole } from './retour';
import { SessionService } from './session.service';

/**
 * Aide d'affichage, après relecture de l'état : un anonyme est envoyé vers la connexion (avec
 * retour). Un Compte d'un autre rôle est envoyé vers l'accès refusé, sauf pendant la navigation
 * issue d'une connexion, qui le conduit à l'accueil de son rôle (toujours autorisé, donc sans
 * boucle). Le contrôle réel est côté serveur.
 */
export function reserveAuxRoles(estAutorise: (role: Role) => boolean): CanActivateFn {
  return (_route, etatRouteur) => {
    const router = inject(Router);
    const apresConnexion = inject(NavigationApresConnexionService);
    return inject(SessionService)
      .compteUneFoisConnu()
      .pipe(
        map((compte) => {
          if (compte === null) {
            return router.createUrlTree(['/connexion'], {
              queryParams: { retour: etatRouteur.url },
            });
          }
          if (estAutorise(compte.role)) {
            return true;
          }
          const refus = apresConnexion.estEnCours() ? accueilDuRole(compte.role) : '/acces-refuse';
          return router.parseUrl(refus);
        }),
      );
  };
}

/** Écrans ouverts à tout Compte connecté, quel que soit son rôle. */
export const reserveAuxComptesConnectes = reserveAuxRoles(() => true);
