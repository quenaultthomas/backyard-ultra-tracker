import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { passwordChangeErrors } from '../../core/account-validation';
import { API_PATHS } from '../../core/api-paths';
import { AccountRegistration, AccountRegistrationsResponse } from '../../core/api.types';
import { formatRaceDate, raceStatusLabel, runnerStatusLabel } from '../../core/formats';
import { BOARD_TIMEOUT_MS, errorMessage, fieldErrors } from '../../core/http-classification';
import { loadFailureMessage } from '../../core/outcomes';
import { ApiClient } from '../../infra/api-client';
import { RunnerAuthState } from '../../infra/runner-auth-state';
import { RunnerSessionService } from '../../infra/runner-session.service';
import { QrImage } from '../../shared/qr-image';

/**
 * « Mes inscriptions » (RG11, RG17, RG19, RG21 inc. 5) : QR code de chaque inscription, généré localement,
 * changement de mot de passe (une seule requête E23) et déconnexion du compte coureur seulement.
 */
@Component({
  selector: 'app-account-page',
  imports: [RouterLink, QrImage],
  template: `
    <h1>Mes inscriptions</h1>
    @if (pseudo(); as current) {
      <p>Vous êtes connecté en tant que <strong>{{ current }}</strong></p>
    }
    @if (loadError(); as message) {
      <p class="banner banner-error" role="alert">{{ message }}</p>
    }
    @if (registrations(); as list) {
      @if (list.length === 0) {
        <p>Aucune inscription.</p>
      }
      <ul class="card-list">
        @for (registration of list; track registration.runnerId) {
          <li class="card">
            <h2><a [routerLink]="['/courses', registration.raceId]">{{ registration.raceName }}</a></h2>
            <p>{{ date(registration.raceDate) }} — {{ raceStatus(registration) }}</p>
            <p class="bib">Dossard <strong>{{ registration.bib }}</strong> — {{ runnerStatus(registration) }}</p>
            <app-qr-image [value]="registration.qrToken" [label]="'QR code du dossard ' + registration.bib" />
            <p class="token">{{ registration.qrToken }}</p>
          </li>
        }
      </ul>
    } @else if (!loadError()) {
      <p>Chargement…</p>
    }

    <h2>Changer mon mot de passe</h2>
    <div aria-live="polite" role="status">
      @if (passwordChanged()) {
        <p class="banner banner-success">Mot de passe modifié</p>
      }
    </div>
    <form class="form" (submit)="changePassword($event)" novalidate>
      <input type="text" name="username" autocomplete="username" class="hidden" [value]="pseudo() ?? ''" readonly
             tabindex="-1" aria-hidden="true" />
      <div class="field">
        <label for="new-password">Nouveau mot de passe</label>
        <input id="new-password" name="newPassword" type="password" autocomplete="new-password" required
               [value]="newPassword()" (input)="newPassword.set(value($event))"
               [attr.aria-invalid]="passwordError('newPassword') !== null" aria-describedby="new-password-error" />
        <p id="new-password-error" class="field-error">{{ passwordError('newPassword') ?? '' }}</p>
      </div>
      <div class="field">
        <label for="new-password-confirmation">Confirmer le nouveau mot de passe</label>
        <input id="new-password-confirmation" name="confirmation" type="password" autocomplete="new-password"
               required [value]="confirmation()" (input)="confirmation.set(value($event))"
               [attr.aria-invalid]="passwordError('confirmation') !== null"
               aria-describedby="new-password-confirmation-error" />
        <p id="new-password-confirmation-error" class="field-error">{{ passwordError('confirmation') ?? '' }}</p>
      </div>
      @if (passwordMessage(); as message) {
        <p class="banner banner-error" role="alert">{{ message }}</p>
      }
      <button type="submit" class="button button-primary" [disabled]="changing()">Changer mon mot de passe</button>
    </form>

    <div class="toolbar">
      <button type="button" class="button" (click)="logout()">Se déconnecter</button>
    </div>
  `,
})
export class AccountPage implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly session = inject(RunnerSessionService);
  protected readonly pseudo = inject(RunnerAuthState).pseudo;
  protected readonly registrations = signal<readonly AccountRegistration[] | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly newPassword = signal('');
  protected readonly confirmation = signal('');
  protected readonly changing = signal(false);
  protected readonly passwordChanged = signal(false);
  protected readonly passwordMessage = signal<string | null>(null);
  private readonly passwordErrors = signal<ReadonlyMap<string, string>>(new Map());

  ngOnInit(): void {
    void this.load();
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected passwordError(field: string): string | null {
    return this.passwordErrors().get(field) ?? null;
  }

  protected async changePassword(event: Event): Promise<void> {
    event.preventDefault();
    if (this.changing()) {
      return;
    }
    this.passwordChanged.set(false);
    this.passwordMessage.set(null);
    const errors = passwordChangeErrors(this.newPassword(), this.confirmation());
    this.passwordErrors.set(errors);
    if (errors.size > 0) {
      return;
    }
    this.changing.set(true);
    const result = await this.session.changePassword(this.newPassword());
    this.changing.set(false);
    if (result.responseClass === 'SUCCESS') {
      this.newPassword.set('');
      this.confirmation.set('');
      this.passwordChanged.set(true);
      return;
    }
    this.passwordErrors.set(fieldErrors(result));
    this.passwordMessage.set(errorMessage(result));
  }

  protected async logout(): Promise<void> {
    await this.session.logout();
  }

  protected date(raceDate: string): string {
    return formatRaceDate(raceDate);
  }

  protected raceStatus(registration: AccountRegistration): string {
    return raceStatusLabel(registration.raceStatus);
  }

  protected runnerStatus(registration: AccountRegistration): string {
    return runnerStatusLabel(registration.status, null, null);
  }

  private async load(): Promise<void> {
    const result = await this.api.request<AccountRegistrationsResponse>('GET', API_PATHS.accountMe, {
      timeoutMs: BOARD_TIMEOUT_MS,
    });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.registrations.set(result.body.registrations);
      return;
    }
    if (result.responseClass !== 'AUTH') {
      this.loadError.set(loadFailureMessage(result, navigator.onLine));
    }
  }
}

