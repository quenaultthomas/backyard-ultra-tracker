import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { API_PATHS } from '../../core/api-paths';
import { RaceResponse } from '../../core/api.types';
import { formatRaceDate, raceStatusLabel } from '../../core/formats';
import { BOARD_TIMEOUT_MS } from '../../core/http-classification';
import { loadFailureMessage } from '../../core/outcomes';
import { ApiClient } from '../../infra/api-client';

/** Accueil : liste des courses dans l'ordre de l'API (RG28, RG35). */
@Component({
  selector: 'app-home-page',
  imports: [RouterLink],
  template: `
    <h1>Courses</h1>
    <div class="toolbar">
      <button type="button" class="button" (click)="load()" [disabled]="loading()">Actualiser</button>
      <a class="button" routerLink="/scan">Scan</a>
      <a class="button" routerLink="/admin">Administration</a>
    </div>
    @if (error(); as message) {
      <p class="banner banner-error" role="alert">{{ message }}</p>
    }
    @if (races(); as list) {
      @if (list.length === 0) {
        <p>Aucune course.</p>
      }
      <ul class="card-list">
        @for (race of list; track race.id) {
          <li class="card">
            <h2>{{ race.name }}</h2>
            <p>{{ date(race.raceDate) }} — {{ status(race) }}</p>
            <div class="toolbar">
              <a class="button" [routerLink]="['/courses', race.id]">Tableau de bord</a>
              @if (race.registrationOpen) {
                <a class="button button-primary" [routerLink]="['/inscription', race.id]">S'inscrire</a>
              }
            </div>
          </li>
        }
      </ul>
    }
  `,
})
export class HomePage implements OnInit {
  private readonly api = inject(ApiClient);
  protected readonly races = signal<readonly RaceResponse[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    const result = await this.api.request<RaceResponse[]>('GET', API_PATHS.races, { timeoutMs: BOARD_TIMEOUT_MS });
    this.loading.set(false);
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.races.set(result.body);
      this.error.set(null);
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
}
