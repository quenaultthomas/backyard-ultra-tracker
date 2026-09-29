import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { registrationFormErrors } from '../../core/account-validation';
import { API_PATHS } from '../../core/api-paths';
import { RaceResponse, RegistrationRequest, RegistrationResponse } from '../../core/api.types';
import {
  formatDistance,
  formatElevation,
  formatLoopDuration,
  formatRaceDate,
  raceStatusLabel,
} from '../../core/formats';
import { ADMIN_TIMEOUT_MS, BOARD_TIMEOUT_MS, ClassifiedResult, errorMessage, fieldErrors } from '../../core/http-classification';
import {
  loadFailureMessage,
  REGISTRATION_RETRY_DELAY_MS,
  registrationDecision,
  showAccountLinkAfterConflict,
} from '../../core/outcomes';
import { ApiClient } from '../../infra/api-client';
import { qrCodeDataUrl } from '../../infra/qr-code';
import { RunnerAuthState } from '../../infra/runner-auth-state';
import { RunnerSessionService } from '../../infra/runner-session.service';
import { QrImage } from '../../shared/qr-image';

/**
 * Inscription publique à une course (RG11 à RG13 inc. 4, RG7, RG8, RG17 inc. 5) : création d'un compte pseudo
 * (E3), ou, une fois connecté en coureur, inscription du compte existant par une seule requête E20.
 */
@Component({
  selector: 'app-registration-page',
  imports: [RouterLink, QrImage],
  template: `
    @if (notFound()) {
      <h1>Course introuvable</h1>
      <p><a routerLink="/">Retour à l'accueil</a></p>
    } @else if (confirmation(); as registered) {
      <section class="registration-confirmation">
        <h1>Inscription confirmée</h1>
        <p class="bib">Dossard <strong>{{ registered.bib }}</strong></p>
        <p class="runner-name">{{ registered.name }}</p>
        <app-qr-image [value]="registered.qrToken" [label]="'QR code du dossard ' + registered.bib" />
        <p class="token">{{ registered.qrToken }}</p>
        <p class="banner banner-warning">
          Vous pourrez réafficher ce QR code depuis <a routerLink="/compte">Mes inscriptions</a>.
        </p>
        <div class="toolbar no-print">
          <button type="button" class="button button-primary" (click)="print()">Imprimer</button>
          <a class="button" [href]="qrDataUrl()" [attr.download]="'qr-dossard-' + registered.bib + '.gif'">
            Enregistrer l'image
          </a>
        </div>
      </section>
    } @else {
      @if (race(); as data) {
        <h1>Inscription — {{ data.name }}</h1>
        <p>{{ date(data.raceDate) }} — {{ status(data) }}</p>
        <p>Boucle : {{ distance(data.loopDistance) }} · {{ duration(data.loopDuration) }} ·
          {{ elevation(data.loopElevation) }}</p>
        @if (closed()) {
          <p class="banner banner-info">Inscriptions fermées</p>
        } @else if (connectedPseudo(); as pseudo) {
          <p>Vous êtes connecté en tant que <strong>{{ pseudo }}</strong></p>
          <button type="button" class="button button-primary" [disabled]="submitting()" (click)="registerAccount()">
            M'inscrire à cette course
          </button>
        } @else {
          <p class="banner banner-info">
            N'utilisez pas votre nom réel ni un pseudo qui permet de vous identifier. Sans email, un mot de passe
            oublié ne peut être réinitialisé que par l'organisateur.
          </p>
          <form class="form" (submit)="submit($event)" novalidate>
            <div class="field">
              <label for="registration-pseudo">Pseudo</label>
              <input id="registration-pseudo" name="pseudo" type="text" autocomplete="username" autocapitalize="none"
                     spellcheck="false" maxlength="40" required [value]="pseudo()" (input)="pseudo.set(inputValue($event))"
                     [attr.aria-invalid]="formError('pseudo') !== null" aria-describedby="registration-pseudo-error" />
              <p id="registration-pseudo-error" class="field-error">{{ formError('pseudo') ?? '' }}</p>
            </div>
            <div class="field">
              <label for="registration-password">Mot de passe</label>
              <input id="registration-password" name="password" type="password" autocomplete="new-password" required
                     [value]="password()" (input)="password.set(inputValue($event))"
                     [attr.aria-invalid]="formError('password') !== null"
                     aria-describedby="registration-password-error" />
              <p id="registration-password-error" class="field-error">{{ formError('password') ?? '' }}</p>
            </div>
            <div class="field">
              <label for="registration-confirmation">Confirmer le mot de passe</label>
              <input id="registration-confirmation" name="confirmation" type="password" autocomplete="new-password"
                     required [value]="confirmationPassword()" (input)="confirmationPassword.set(inputValue($event))"
                     [attr.aria-invalid]="formError('confirmation') !== null"
                     aria-describedby="registration-confirmation-error" />
              <p id="registration-confirmation-error" class="field-error">{{ formError('confirmation') ?? '' }}</p>
            </div>
            <button type="submit" class="button button-primary" [disabled]="submitting()">S'inscrire</button>
          </form>
          <p><a routerLink="/compte/connexion" [queryParams]="{ retour: returnUrl() }">J'ai déjà un compte</a></p>
        }
      } @else if (!error()) {
        <p>Chargement…</p>
      }
      @if (error(); as message) {
        <p class="banner banner-error" role="alert">{{ message }}</p>
        @if (showAccountLink()) {
          <p><a routerLink="/compte/connexion" [queryParams]="{ retour: returnUrl() }">J'ai déjà un compte</a></p>
        }
        @if (canRetry()) {
          <button type="button" class="button" (click)="retry()">Réessayer</button>
        }
      }
    }
  `,
})
export class RegistrationPage implements OnInit {
  readonly raceId = input.required<string>();

  private readonly api = inject(ApiClient);
  private readonly runnerAuth = inject(RunnerAuthState);
  private readonly runnerSession = inject(RunnerSessionService);
  protected readonly race = signal<RaceResponse | null>(null);
  protected readonly notFound = signal(false);
  protected readonly closed = signal(false);
  protected readonly pseudo = signal('');
  protected readonly password = signal('');
  protected readonly confirmationPassword = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly showAccountLink = signal(false);
  protected readonly canRetry = signal(false);
  protected readonly submitting = signal(false);
  protected readonly confirmation = signal<RegistrationResponse | null>(null);
  protected readonly connectedPseudo = this.runnerAuth.pseudo;
  protected readonly returnUrl = computed(() => `/inscription/${this.raceId()}`);
  protected readonly qrDataUrl = computed(() => {
    const registered = this.confirmation();
    return registered === null ? '' : qrCodeDataUrl(registered.qrToken);
  });
  private readonly formErrors = signal<ReadonlyMap<string, string>>(new Map());

  ngOnInit(): void {
    void this.runnerSession.ready;
    void this.loadRace();
  }

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected formError(field: string): string | null {
    return this.formErrors().get(field) ?? null;
  }

  /** E3 : validation miroir de RG2 et RG3 ; mots de passe différents, aucune requête. */
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
    await this.registerWithNewAccount();
  }

  protected async retry(): Promise<void> {
    if (!this.submitting()) {
      await this.registerWithNewAccount();
    }
  }

  /** E20 (RG8) : une seule requête, jamais rejouée automatiquement. */
  protected async registerAccount(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    this.startSending();
    try {
      const result = await this.api.request<RegistrationResponse>('POST', API_PATHS.accountRegistrations(this.raceId()),
        { timeoutMs: ADMIN_TIMEOUT_MS });
      await this.apply(result, NO_AUTOMATIC_RETRY);
    } finally {
      this.submitting.set(false);
    }
  }

  protected print(): void {
    window.print();
  }

  /** E3 : un envoi, et un seul nouvel essai automatique après 1 s sur 409 DATA_INTEGRITY (RG12, RG13 inc. 4). */
  private async registerWithNewAccount(): Promise<void> {
    this.startSending();
    try {
      let attempt = 1;
      let result = await this.sendNewAccount();
      if (registrationDecision(result, attempt) === 'RETRY_AUTOMATICALLY') {
        await delay(REGISTRATION_RETRY_DELAY_MS);
        attempt += 1;
        result = await this.sendNewAccount();
      }
      await this.apply(result, attempt);
    } finally {
      this.submitting.set(false);
    }
  }

  private startSending(): void {
    this.submitting.set(true);
    this.error.set(null);
    this.showAccountLink.set(false);
    this.canRetry.set(false);
  }

  private sendNewAccount(): Promise<ClassifiedResult<RegistrationResponse>> {
    const body: RegistrationRequest = { pseudo: this.pseudo(), password: this.password() };
    return this.api.request<RegistrationResponse>('POST', API_PATHS.registrations(this.raceId()), {
      body,
      timeoutMs: ADMIN_TIMEOUT_MS,
    });
  }

  private async apply(result: ClassifiedResult<RegistrationResponse>, attempt: number): Promise<void> {
    switch (registrationDecision(result, attempt)) {
      case 'CONFIRMED':
        this.password.set('');
        this.confirmationPassword.set('');
        this.confirmation.set(result.body);
        return;
      case 'FIELD_ERRORS':
        this.formErrors.set(fieldErrors(result));
        this.error.set(errorMessage(result));
        return;
      case 'CLOSED':
        await this.showConflict(result);
        return;
      case 'NOT_FOUND':
        this.notFound.set(true);
        return;
      case 'UNREACHABLE':
        this.error.set('Serveur injoignable, réessayez');
        return;
      case 'FAILED_WITH_RETRY':
        this.error.set(errorMessage(result));
        this.canRetry.set(this.connectedPseudo() === null);
        return;
      case 'TOO_MANY_ATTEMPTS':
      case 'RETRY_AUTOMATICALLY':
      case 'FAILED':
        this.error.set(errorMessage(result));
        return;
    }
  }

  /**
   * 409 BUSINESS_CONFLICT : inscriptions fermées, pseudo déjà utilisé ou déjà inscrit. Le detail est affiché ; la
   * course est relue, et le lien « J'ai déjà un compte » n'est proposé que si elle est encore en SETUP (D1).
   */
  private async showConflict(result: ClassifiedResult<RegistrationResponse>): Promise<void> {
    const rereadRace = await this.loadRace();
    this.error.set(errorMessage(result));
    this.showAccountLink.set(showAccountLinkAfterConflict(rereadRace, this.connectedPseudo() !== null));
  }

  /** Charge la course (E2) ; renvoie la course relue, ou null si elle n'a pas pu l'être. */
  private async loadRace(): Promise<RaceResponse | null> {
    const result = await this.api.request<RaceResponse>('GET', API_PATHS.race(this.raceId()), {
      timeoutMs: BOARD_TIMEOUT_MS,
    });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.race.set(result.body);
      this.closed.set(!result.body.registrationOpen);
      return result.body;
    }
    if (result.status === 404 || result.status === 400) {
      this.notFound.set(true);
      return null;
    }
    this.error.set(loadFailureMessage(result, navigator.onLine));
    return null;
  }

  protected date(raceDate: string): string {
    return formatRaceDate(raceDate);
  }

  protected status(race: RaceResponse): string {
    return raceStatusLabel(race.status);
  }

  protected distance(meters: number): string {
    return formatDistance(meters);
  }

  protected duration(seconds: number): string {
    return formatLoopDuration(seconds);
  }

  protected elevation(meters: number): string {
    return formatElevation(meters);
  }
}

/** Numéro de tentative qui exclut tout nouvel essai automatique (E20 : une seule requête, RG17 inc. 5). */
const NO_AUTOMATIC_RETRY = 2;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}
