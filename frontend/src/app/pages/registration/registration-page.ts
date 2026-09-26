import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { API_PATHS } from '../../core/api-paths';
import { RaceResponse, RegistrationResponse } from '../../core/api.types';
import {
  formatDistance,
  formatElevation,
  formatLoopDuration,
  formatRaceDate,
  raceStatusLabel,
} from '../../core/formats';
import { ADMIN_TIMEOUT_MS, BOARD_TIMEOUT_MS, ClassifiedResult, errorMessage, fieldErrors } from '../../core/http-classification';
import { loadFailureMessage, REGISTRATION_RETRY_DELAY_MS, registrationDecision } from '../../core/outcomes';
import { requiredText } from '../../core/validation';
import { ApiClient } from '../../infra/api-client';
import { qrCodeDataUrl } from '../../infra/qr-code';
import { QrImage } from '../../shared/qr-image';

/** Inscription publique à une course (RG11 à RG13). */
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
          Conservez ce QR code : il ne sera plus affiché. L'organisateur peut le réimprimer.
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
        } @else {
          <form class="form" (submit)="submit($event)" novalidate>
            <div class="field">
              <label for="runner-name">Nom</label>
              <input id="runner-name" name="name" type="text" autocomplete="name" maxlength="255" required
                     [value]="name()" (input)="name.set(inputValue($event))"
                     [attr.aria-invalid]="nameError() !== null" aria-describedby="runner-name-error" />
              <p id="runner-name-error" class="field-error">{{ nameError() ?? '' }}</p>
            </div>
            <button type="submit" class="button button-primary" [disabled]="submitting()">S'inscrire</button>
          </form>
        }
      } @else if (!error()) {
        <p>Chargement…</p>
      }
      @if (error(); as message) {
        <p class="banner banner-error" role="alert">{{ message }}</p>
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
  protected readonly race = signal<RaceResponse | null>(null);
  protected readonly notFound = signal(false);
  protected readonly closed = signal(false);
  protected readonly name = signal('');
  protected readonly nameError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly canRetry = signal(false);
  protected readonly submitting = signal(false);
  protected readonly confirmation = signal<RegistrationResponse | null>(null);
  protected readonly qrDataUrl = computed(() => {
    const registered = this.confirmation();
    return registered === null ? '' : qrCodeDataUrl(registered.qrToken);
  });

  ngOnInit(): void {
    void this.loadRace();
  }

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting()) {
      return;
    }
    const formatError = requiredText(this.name());
    this.nameError.set(formatError);
    if (formatError !== null) {
      return;
    }
    await this.register();
  }

  protected async retry(): Promise<void> {
    if (!this.submitting()) {
      await this.register();
    }
  }

  protected print(): void {
    window.print();
  }

  /** Un envoi, et un seul nouvel essai automatique après 1 s sur 409 DATA_INTEGRITY (RG12, RG13). */
  private async register(): Promise<void> {
    this.submitting.set(true);
    this.error.set(null);
    this.canRetry.set(false);
    try {
      let attempt = 1;
      let result = await this.send();
      if (registrationDecision(result, attempt) === 'RETRY_AUTOMATICALLY') {
        await delay(REGISTRATION_RETRY_DELAY_MS);
        attempt += 1;
        result = await this.send();
      }
      this.apply(result, attempt);
    } finally {
      this.submitting.set(false);
    }
  }

  private send(): Promise<ClassifiedResult<RegistrationResponse>> {
    return this.api.request<RegistrationResponse>('POST', API_PATHS.registrations(this.raceId()), {
      body: { name: this.name().trim() },
      timeoutMs: ADMIN_TIMEOUT_MS,
    });
  }

  private apply(result: ClassifiedResult<RegistrationResponse>, attempt: number): void {
    switch (registrationDecision(result, attempt)) {
      case 'CONFIRMED':
        this.confirmation.set(result.body);
        return;
      case 'FIELD_ERRORS':
        this.nameError.set(fieldErrors(result).get('name') ?? errorMessage(result));
        return;
      case 'CLOSED':
        this.error.set(errorMessage(result));
        this.closed.set(true);
        return;
      case 'NOT_FOUND':
        this.notFound.set(true);
        return;
      case 'UNREACHABLE':
        this.error.set('Serveur injoignable, réessayez');
        return;
      case 'FAILED_WITH_RETRY':
        this.error.set(errorMessage(result));
        this.canRetry.set(true);
        return;
      case 'RETRY_AUTOMATICALLY':
      case 'FAILED':
        this.error.set(errorMessage(result));
        return;
    }
  }

  private async loadRace(): Promise<void> {
    const result = await this.api.request<RaceResponse>('GET', API_PATHS.race(this.raceId()), {
      timeoutMs: BOARD_TIMEOUT_MS,
    });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.race.set(result.body);
      this.closed.set(!result.body.registrationOpen);
      return;
    }
    if (result.status === 404 || result.status === 400) {
      this.notFound.set(true);
      return;
    }
    this.error.set(loadFailureMessage(result, navigator.onLine));
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

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}
