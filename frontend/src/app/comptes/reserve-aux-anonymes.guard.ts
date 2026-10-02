import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';

import { SessionService } from './session.service';

/** Écrans réservés aux anonymes : un utilisateur connecté est renvoyé vers l'accueil. */
export const reserveAuxAnonymes: CanActivateFn = () => {
  const router = inject(Router);
  return inject(SessionService)
    .estConnecteUneFoisConnu()
    .pipe(map((connecte) => (connecte ? router.createUrlTree(['/']) : true)));
};
