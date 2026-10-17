import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';

import { SessionService } from './session.service';

/**
 * Compte mémorisé ouvert hors ligne : au retour du réseau ou de l'onglet au premier plan, la
 * session est relue. Session expirée : reconnexion avec retour vers l'écran courant ; toujours
 * sans réponse : nouvel essai à l'événement suivant. Indépendant du cycle d'envoi des scans.
 */
@Injectable({ providedIn: 'root' })
export class RevalidationSessionService {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private enCours = false;

  constructor() {
    const document = inject(DOCUMENT);
    document.defaultView?.addEventListener('online', () => this.revalider());
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this.revalider();
      }
    });
  }

  private revalider(): void {
    if (!this.session.horsLigne() || this.enCours) {
      return;
    }
    this.enCours = true;
    const ecran = this.router.url;
    this.session.restaurer().subscribe(() => {
      this.enCours = false;
      if (this.session.etat() === 'ANONYME') {
        void this.router.navigate(['/connexion'], { queryParams: { retour: ecran } });
      }
    });
  }
}
