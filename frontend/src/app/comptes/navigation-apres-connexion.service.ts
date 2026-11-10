import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';

/**
 * Navigation vers la destination choisie après une connexion. Tant qu'elle est en cours, la garde
 * de rôles remplace une route interdite au rôle par l'accueil du rôle au lieu de l'accès refusé.
 * L'indicateur vit en mémoire et seulement le temps de cette navigation : un rechargement, un lien
 * direct ou « Précédent » n'en portent jamais (contrairement à un état d'historique, restauré).
 */
@Injectable({ providedIn: 'root' })
export class NavigationApresConnexionService {
  private readonly router = inject(Router);
  private enCours = false;

  rejoindre(destination: string): void {
    this.enCours = true;
    void this.router.navigateByUrl(destination).finally(() => (this.enCours = false));
  }

  /** Vrai pendant la navigation issue d'une connexion (redirections de garde comprises). */
  estEnCours(): boolean {
    return this.enCours;
  }
}
