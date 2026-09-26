import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthState } from '../../infra/auth-state';
import { LogoutButton } from '../../shared/logout-button';

/**
 * Coquille de l'administration (RG36, RG10) : les écrans admin ne sont créés qu'avec un compte ADMIN. Un compte
 * SCANNER voit « Accès réservé à l'administrateur », sans aucune donnée ni requête admin.
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
    } @else {
      <h1>Accès réservé à l'administrateur</h1>
      <p>Le compte connecté (scanner) ne permet pas d'administrer les courses.</p>
      <a class="button button-primary" routerLink="/connexion" [queryParams]="{ retour: router.url }">
        Se connecter en administrateur
      </a>
    }
  `,
})
export class AdminShell {
  protected readonly router = inject(Router);
  private readonly authState = inject(AuthState);
  protected readonly isAdmin = computed(() => this.authState.role() === 'ADMIN');
}
