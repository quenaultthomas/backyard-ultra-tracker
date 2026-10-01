import { Component, inject, input, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { registrationFormErrors } from '../../core/account-validation';
import { API_PATHS } from '../../core/api-paths';
import { AccountCreationRequest, AccountCreationResponse } from '../../core/api.types';
import { ADMIN_TIMEOUT_MS, ClassifiedResult, errorMessage, fieldErrors } from '../../core/http-classification';
import { loginDestination } from '../../core/login-entry';
import { ApiClient } from '../../infra/api-client';
import { RunnerAuthState } from '../../infra/runner-auth-state';
import { RunnerSessionService } from '../../infra/runner-session.service';

/**
 * Création autonome d'un compte coureur, sans course (RG7 inc. 7) : une seule requête E26, jamais rejouée, puis
 * connexion automatique en coureur par une seule requête E21 et arrivée sur `/compte` ou sur l'écran demandé.
 * Un coureur déjà connecté voit un message, sans formulaire (CL8).
 */
@Component({
  selector: 'app-account-creation-page',
  imports: [RouterLink],
  template: `
    <h1>Créer un compte</h1>
    @if (connectedPseudo(); as pseudo) {
      <p>Vous êtes connecté en tant que <strong>{{ pseudo }}</strong></p>
      <p><a routerLink="/compte">Aller à mon compte</a></p>
    } @else {
      <p class="banner banner-info">
        N'utilisez pas votre nom réel ni un pseudo qui permet de vous identifier. Sans email, un mot de passe
        oublié ne peut être réinitialisé que par l'organisateur.
      </p>
      <p>Bénévole : votre compte est créé par l'organisateur.</p>
      <form class="form" (submit)="submit($event)" novalidate>
        <div class="field">
          <label for="account-creation-pseudo">Pseudo</label>
          <input id="account-creation-pseudo" name="pseudo" type="text" autocomplete="username" autocapitalize="none"
                 spellcheck="false" maxlength="40" required [value]="pseudo()" (input)="pseudo.set(inputValue($event))"
                 [attr.aria-invalid]="formError('pseudo') !== null" aria-describedby="account-creation-pseudo-error" />
          <p id="account-creation-pseudo-error" class="field-error">{{ formError('pseudo') ?? '' }}</p>
        </div>
        <div class="field">
          <label for="account-creation-password">Mot de passe</label>
          <input id="account-creation-password" name="password" type="password" autocomplete="new-password" required
                 [value]="password()" (input)="password.set(inputValue($event))"
                 [attr.aria-invalid]="formError('password') !== null"
                 aria-describedby="account-creation-password-error" />
          <p id="account-creation-password-error" class="field-error">{{ formError('password') ?? '' }}</p>
        </div>
        <div class="field">
          <label for="account-creation-confirmation">Confirmer le mot de passe</label>
          <input id="account-creation-confirmation" name="confirmation" type="password" autocomplete="new-password"
                 required [value]="confirmationPassword()" (input)="confirmationPassword.set(inputValue($event))"
                 [attr.aria-invalid]="formError('confirmation') !== null"
                 aria-describedby="account-creation-confirmation-error" />
          <p id="account-creation-confirmation-error" class="field-error">{{ formError('confirmation') ?? '' }}</p>
        </div>
        <div class="field field-checkbox">
          <input id="account-creation-remember" name="remember" type="checkbox" [checked]="remember()"
                 (change)="remember.set(checkboxValue($event))" />
          <label for="account-creation-remember">Rester connecté 24 h sur cet appareil</label>
        </div>
        @if (error(); as message) {
          <p class="banner banner-error" role="alert">{{ message }}</p>
        }
        <button type="submit" class="button button-primary" [disabled]="submitting()">Créer mon compte</button>
      </form>
      <p><a routerLink="/compte/connexion" [queryParams]="{ retour: retour() }">J'ai déjà un compte</a></p>
    }
  `,
})
export class AccountCreationPage implements OnInit {
  /** Écran demandé après la création (paramètre de requête `retour`), par exemple `/inscription/{raceId}`. */
  readonly retour = input<string>();

  private readonly api = inject(ApiClient);
  private readonly runnerSession = inject(RunnerSessionService);
  private readonly router = inject(Router);
  protected readonly connectedPseudo = inject(RunnerAuthState).pseudo;
  protected readonly pseudo = signal('');
  protected readonly password = signal('');
  protected readonly confirmationPassword = signal('');
  protected readonly remember = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly formErrors = signal<ReadonlyMap<string, string>>(new Map());

  ngOnInit(): void {
    void this.runnerSession.ready;
  }

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checkboxValue(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected formError(field: string): string | null {
    return this.formErrors().get(field) ?? null;
  }

  /** Validation miroir de RG2 et RG3 (inc. 5) ; mots de passe différents : aucune requête. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting()) {
      return;
    }
    const errors = registrationFormErrors(this.pseudo(), this.password(), this.confirmationPassword());
    this.formErrors.set(errors);
    if (errors.size > 0) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    try {
      await this.createAccount();
    } finally {
      this.submitting.set(false);
    }
  }

  /** E26 une seule fois ; sur 201, connexion automatique en coureur. */
  private async createAccount(): Promise<void> {
    const body: AccountCreationRequest = { pseudo: this.pseudo(), password: this.password() };
    const result = await this.api.request<AccountCreationResponse>('POST', API_PATHS.publicAccounts, {
      body,
      timeoutMs: ADMIN_TIMEOUT_MS,
    });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      await this.signIn(result.body.pseudo);
      return;
    }
    this.showFailure(result);
  }

  /** E21 avec les identifiants du compte créé, emplacement coureur seulement (RG21 inc. 5). */
  private async signIn(storedPseudo: string): Promise<void> {
    const login = await this.runnerSession.login(storedPseudo, this.password(), this.remember());
    this.clearPasswords();
    if (!login.ok) {
      this.error.set(`Compte créé, mais la connexion a échoué : ${login.message}`);
      return;
    }
    await this.router.navigateByUrl(loginDestination('RUNNER', this.retour()));
  }

  /** 400 : message par champ ; 409 : detail du serveur ; 429 et réseau : message de la classification. */
  private showFailure(result: ClassifiedResult<AccountCreationResponse>): void {
    this.formErrors.set(fieldErrors(result));
    this.error.set(errorMessage(result));
  }

  private clearPasswords(): void {
    this.password.set('');
    this.confirmationPassword.set('');
  }
}
