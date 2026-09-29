import { Component, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { RunnerAuthState } from '../../infra/runner-auth-state';
import { RunnerSessionService } from '../../infra/runner-session.service';

/**
 * Connexion coureur (RG21 inc. 5) : pseudo et mot de passe validés par E21, « Rester connecté 24 h sur cet
 * appareil » décoché par défaut, retour vers la page d'origine (inscription d'une course) ou vers /compte.
 */
@Component({
  selector: 'app-runner-login-page',
  template: `
    <h1>Connexion coureur</h1>
    @if (notice(); as message) {
      <p class="banner banner-warning" role="alert">{{ message }}</p>
    }
    <form class="form" (submit)="submit($event)" novalidate>
      <div class="field">
        <label for="runner-login-pseudo">Pseudo</label>
        <input id="runner-login-pseudo" name="pseudo" type="text" autocomplete="username" autocapitalize="none"
               spellcheck="false" required [value]="pseudo()" (input)="pseudo.set(inputValue($event))" />
      </div>
      <div class="field">
        <label for="runner-login-password">Mot de passe</label>
        <div class="inline-field">
          <input id="runner-login-password" name="password" [type]="showPassword() ? 'text' : 'password'"
                 autocomplete="current-password" required [value]="password()"
                 (input)="password.set(inputValue($event))" />
          <button type="button" class="button" [attr.aria-pressed]="showPassword()"
                  (click)="showPassword.set(!showPassword())">Afficher</button>
        </div>
      </div>
      <div class="field field-checkbox">
        <input id="runner-login-remember" name="remember" type="checkbox" [checked]="remember()"
               (change)="remember.set(checkboxValue($event))" />
        <label for="runner-login-remember">Rester connecté 24 h sur cet appareil</label>
      </div>
      @if (error(); as message) {
        <p class="banner banner-error" role="alert">{{ message }}</p>
      }
      <button type="submit" class="button button-primary" [disabled]="submitting()">Se connecter</button>
    </form>
  `,
})
export class RunnerLoginPage {
  /** Page demandée avant la connexion (paramètre de requête `retour`). */
  readonly retour = input<string>();

  private readonly session = inject(RunnerSessionService);
  private readonly router = inject(Router);
  protected readonly notice = inject(RunnerAuthState).notice;
  protected readonly pseudo = signal('');
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
    if (this.pseudo().trim() === '' || this.password() === '') {
      this.error.set('Pseudo et mot de passe obligatoires');
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const result = await this.session.login(this.pseudo(), this.password(), this.remember());
    this.submitting.set(false);
    if (!result.ok) {
      this.password.set('');
      this.error.set(result.message);
      return;
    }
    this.password.set('');
    await this.router.navigateByUrl(this.destination());
  }

  /** Retour vers la page demandée s'il s'agit d'un chemin interne, sinon « Mes inscriptions ». */
  private destination(): string {
    const requested = this.retour();
    if (requested !== undefined && requested.startsWith('/') && !requested.startsWith('//')) {
      return requested;
    }
    return '/compte';
  }
}
