import { Component, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { API_PATHS } from '../../core/api-paths';
import { AdminRunnerResponse, RaceResponse } from '../../core/api.types';
import { ADMIN_TIMEOUT_MS } from '../../core/http-classification';
import { adminFailureMessage } from '../../core/outcomes';
import { ApiClient } from '../../infra/api-client';
import { QrImage } from '../../shared/qr-image';

/** Planche d'impression des QR codes (RG40) : une vignette par coureur, dans l'ordre de E13. */
@Component({
  selector: 'app-admin-qr-sheet-page',
  imports: [RouterLink, QrImage],
  template: `
    <div class="no-print">
      <h1>QR codes{{ race() ? ' — ' + race()?.name : '' }}</h1>
      <div class="toolbar">
        <button type="button" class="button button-primary" (click)="print()">Imprimer</button>
        <a class="button" [routerLink]="['/admin/courses', raceId()]">Retour à la course</a>
      </div>
      @if (message(); as text) {
        <p class="banner banner-error" role="alert">{{ text }}</p>
      }
    </div>
    @if (runners(); as list) {
      @if (list.length === 0) {
        <p>Aucun inscrit.</p>
      }
      <ul class="qr-sheet">
        @for (runner of list; track runner.id) {
          <li class="qr-card">
            <p class="qr-card-bib">{{ runner.bib }}</p>
            <p class="qr-card-name">{{ runner.name }}</p>
            <app-qr-image [value]="runner.qrToken" [label]="'QR code du dossard ' + runner.bib" />
          </li>
        }
      </ul>
    }
  `,
})
export class AdminQrSheetPage implements OnInit {
  readonly raceId = input.required<string>();

  private readonly api = inject(ApiClient);
  protected readonly race = signal<RaceResponse | null>(null);
  protected readonly runners = signal<readonly AdminRunnerResponse[] | null>(null);
  protected readonly message = signal<string | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  protected print(): void {
    window.print();
  }

  private async load(): Promise<void> {
    const [race, runners] = await Promise.all([
      this.api.request<RaceResponse>('GET', API_PATHS.adminRace(this.raceId()), { timeoutMs: ADMIN_TIMEOUT_MS }),
      this.api.request<AdminRunnerResponse[]>('GET', API_PATHS.adminRaceRunners(this.raceId()), {
        timeoutMs: ADMIN_TIMEOUT_MS,
      }),
    ]);
    this.race.set(race.body);
    if (runners.responseClass === 'SUCCESS' && runners.body !== null) {
      this.runners.set(runners.body);
      return;
    }
    this.message.set(adminFailureMessage(runners));
  }
}
