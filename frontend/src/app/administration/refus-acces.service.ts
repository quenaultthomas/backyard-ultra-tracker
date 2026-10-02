import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';

import { SessionService } from '../comptes/session.service';
import { lireProbleme } from '../partage/probleme';

/** Redirige l'utilisateur quand l'API refuse l'accès à un écran d'administration. */
@Injectable({ providedIn: 'root' })
export class RefusAccesService {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);

  /**
   * 401 : connexion avec retour vers `ecran` ; 403 (hors jeton CSRF refusé) : accès refusé.
   * Renvoie `true` si l'erreur a été traitée par une redirection.
   */
  rediriger(erreur: unknown, ecran: string): boolean {
    const statut = erreur instanceof HttpErrorResponse ? erreur.status : null;
    if (statut === HttpStatusCode.Unauthorized) {
      this.session.oublierSessionExpiree();
      void this.router.navigate(['/connexion'], { queryParams: { retour: ecran } });
      return true;
    }
    if (statut === HttpStatusCode.Forbidden && lireProbleme(erreur)?.code !== 'CSRF_INVALIDE') {
      void this.router.navigateByUrl('/acces-refuse');
      return true;
    }
    return false;
  }
}
