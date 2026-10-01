import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Role } from '../../core/api.types';
import {
  defaultLoginEntry,
  LOGIN_FAILURE_HINT,
  LOGIN_FAILURE_MESSAGE,
  loginDestination,
  LoginEntry,
  loginFieldLabels,
} from '../../core/login-entry';
import { AuthState } from '../../infra/auth-state';
import { RunnerAuthState } from '../../infra/runner-auth-state';
import { RunnerSessionService } from '../../infra/runner-session.service';
import { SessionService } from '../../infra/session.service';

type LoginOutcome = { readonly ok: true; readonly role?: Role } | { readonly ok: false; readonly message: string };

/**
 * Écran de connexion unique (RG1 à RG3 inc. 7), servi par `/connexion` et par l'alias `/compte/connexion`. Deux
 * entrées : « Coureur » (E21, emplacement coureur) et « Bénévole » (E19, emplacement staff). L'entrée présélectionnée
 * est calculée par {@link defaultLoginEntry} ; l'utilisateur peut en changer. Aucune requête avant la soumission.
 */
@Component({
  selector: 'app-login-page',
  template: `
    <h1>Connexion</h1>
    @if (notice(); as message) {
      <p class="banner banner-warning" role="alert">{{ message }}</p>
    }
    <form class="form" (submit)="submit($event)" novalidate>
      <fieldset class="radio-group">
        <legend>Je me connecte en tant que</legend>
        <div class="radio-option">
          <input type="radio" name="login-entry" id="login-entry-runner" value="RUNNER"
                 [checked]="entry() === 'RUNNER'" (change)="choose('RUNNER')" />
          <label for="login-entry-runner">Coureur</label>
        </div>
        <div class="radio-option">
          <input type="radio" name="login-entry" id="login-entry-staff" value="STAFF"
                 [checked]="entry() === 'STAFF'" (change)="choose('STAFF')" />
          <label for="login-entry-staff">Bénévole</label>
        </div>
      </fieldset>
      <div class="field">
        <label for="login-identifier">{{ labels().identifier }}</label>
        <input id="login-identifier" name="username" type="text" autocomplete="username" autocapitalize="none"
               spellcheck="false" required [value]="identifier()" (input)="identifier.set(inputValue($event))" />
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
        <label for="login-remember">{{ labels().remember }}</label>
      </div>
      @if (error(); as message) {
        <p class="banner banner-error" role="alert">{{ message }}</p>
        @if (showHint()) {
          <p class="login-hint">{{ hint }}</p>
        }
      }
      <button type="submit" class="button button-primary" [disabled]="submitting()">Se connecter</button>
    </form>
  `,
})
export class LoginPage {
  /** Écran demandé avant la connexion (paramètre de requête `retour`). */
  readonly retour = input<string>();
  /** Profil demandé (paramètre de requête `profil`) ; seule la valeur `benevole` est reconnue. */
  readonly profil = input<string>();
  /** Vrai sur l'alias `/compte/connexion` (donnée de route) : « Coureur » toujours présélectionnée (RG3). */
  readonly runnerRoute = input<boolean>(false);

  private readonly staffSession = inject(SessionService);
  private readonly runnerSession = inject(RunnerSessionService);
  private readonly staffNotice = inject(AuthState).notice;
  private readonly runnerNotice = inject(RunnerAuthState).notice;
  private readonly router = inject(Router);

  protected readonly hint = LOGIN_FAILURE_HINT;
  protected readonly entry = linkedSignal<LoginEntry>(() => defaultLoginEntry({
    retour: this.retour(),
    profil: this.profil(),
    runnerRoute: this.runnerRoute(),
  }));
  protected readonly labels = computed(() => loginFieldLabels(this.entry()));
  /** Message d'expiration du référentiel de l'entrée active ; celui de l'autre est conservé pour son entrée. */
  protected readonly notice = computed(() => this.entry() === 'RUNNER' ? this.runnerNotice() : this.staffNotice());
  protected readonly identifier = signal('');
  protected readonly password = signal('');
  protected readonly remember = signal(false);
  protected readonly showPassword = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly showHint = computed(() => this.error() === LOGIN_FAILURE_MESSAGE);

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checkboxValue(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected choose(entry: LoginEntry): void {
    this.entry.set(entry);
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting()) {
      return;
    }
    if (this.identifier().trim() === '' || this.password() === '') {
      this.error.set(`${this.labels().identifier} et mot de passe obligatoires`);
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const entry = this.entry();
    const outcome = await this.login(entry);
    this.submitting.set(false);
    this.password.set('');
    if (!outcome.ok) {
      this.error.set(outcome.message);
      return;
    }
    await this.router.navigateByUrl(loginDestination(entry, this.retour(), outcome.role));
  }

  /** Validation par l'endpoint du référentiel de l'entrée ; rien n'est conservé en cas d'échec (RG2). */
  private login(entry: LoginEntry): Promise<LoginOutcome> {
    return entry === 'RUNNER'
      ? this.runnerSession.login(this.identifier(), this.password(), this.remember())
      : this.staffSession.login(this.identifier().trim(), this.password(), this.remember());
  }
}
