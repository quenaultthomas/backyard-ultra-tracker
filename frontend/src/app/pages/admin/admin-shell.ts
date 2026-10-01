import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthState } from '../../infra/auth-state';
import { LogoutButton } from '../../shared/logout-button';

/**
 * Coquille de l'administration. Seul un ADMIN y arrive (garde `canMatch` des routes, RG3 inc. 6) ; si la session
 * ADMIN disparaît pendant l'affichage (déconnexion, 401 traité par `SessionService.expire`, RG10 inc. 4), aucun
 * écran admin n'est plus rendu, et aucun message ne signale l'administration en attendant la navigation.
 */
@Component({
  selector: 'app-admin-shell',
  imports: [RouterOutlet, RouterLink, LogoutButton],
  template: `
    @if (isAdmin()) {
      <div class="admin-bar no-print">
        <a routerLink="/admin">Courses (admin)</a>
        <span>Connecté : admin</span>
        <app-logout-button />
      </div>
      <router-outlet />
    }
  `,
})
export class AdminShell {
  private readonly authState = inject(AuthState);
  protected readonly isAdmin = computed(() => this.authState.role() === 'ADMIN');
}
