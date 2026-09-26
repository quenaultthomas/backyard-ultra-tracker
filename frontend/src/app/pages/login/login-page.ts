import { Component, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Role } from '../../core/api.types';
import { AuthState } from '../../infra/auth-state';
import { SessionService } from '../../infra/session.service';

/** Connexion (RG6, RG7) : validation des identifiants par E19, renvoi vers l'écran demandé. */
@Component({
  selector: 'app-login-page',
  template: `
    <h1>Connexion</h1>
    @if (notice(); as message) {
      <p class="banner banner-warning" role="alert">{{ message }}</p>
    }
    <form class="form" (submit)="submit($event)" novalidate>
      <div class="field">
        <label for="login-username">Nom d'utilisateur</label>
        <input id="login-username" name="username" type="text" autocomplete="username" autocapitalize="none"
               spellcheck="false" required [value]="username()" (input)="username.set(inputValue($event))" />
      </div>
      <div class="field">
        <label for="login-password">Mot de passe</label>
        <div class="inline-field">
          <input id="login-password" name="password" [type]="showPassword() ? 'text' : 'password'"
                 autocomplete="current-password" required [value]="password()"
                 (input)="password.set(inputValue($event))" />
          <button type="button" class="button" [attr.aria-pressed]="showPassword()"
                  (click)="showPassword.set(!showPassword())">Afficher</button>
        </div>
      </div>
      <div class="field field-checkbox">
        <input id="login-remember" name="remember" type="checkbox" [checked]="remember()"
               (change)="remember.set(checkboxValue($event))" />
        <label for="login-remember">Rester connecté 24 h sur cet appareil (compte scanner uniquement)</label>
      </div>
      @if (error(); as message) {
        <p class="banner banner-error" role="alert">{{ message }}</p>
      }
      <button type="submit" class="button button-primary" [disabled]="submitting()">Se connecter</button>
    </form>
  `,
})
export class LoginPage {
  /** Écran demandé avant la connexion (paramètre de requête `retour`). */
  readonly retour = input<string>();

  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly notice = inject(AuthState).notice;
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly remember = signal(false);
  protected readonly showPassword = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checkboxValue(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting()) {
      return;
    }
    if (this.username().trim() === '' || this.password() === '') {
      this.error.set('Nom d\'utilisateur et mot de passe obligatoires');
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const result = await this.session.login(this.username().trim(), this.password(), this.remember());
    this.submitting.set(false);
    if (!result.ok) {
      this.error.set(result.message);
      return;
    }
    this.password.set('');
    await this.router.navigateByUrl(this.destination(result.role));
  }

  /** Retour vers l'écran demandé s'il s'agit d'un chemin interne, sinon l'écran du rôle. */
  private destination(role: Role): string {
    const requested = this.retour();
    if (requested !== undefined && requested.startsWith('/') && !requested.startsWith('//')) {
      return requested;
    }
    return role === 'ADMIN' ? '/admin' : '/scan';
  }
}
