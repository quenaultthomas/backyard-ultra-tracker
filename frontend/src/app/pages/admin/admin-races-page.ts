import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { API_PATHS } from '../../core/api-paths';
import { RaceRequest, RaceResponse } from '../../core/api.types';
import { formatRaceDate, raceStatusLabel } from '../../core/formats';
import { ADMIN_TIMEOUT_MS, fieldErrors } from '../../core/http-classification';
import { adminFailureMessage } from '../../core/outcomes';
import { ApiClient } from '../../infra/api-client';
import { RaceForm } from './race-form';

/** Liste et création des courses (RG37). Aucune action n'est mise en file ni rejouée (RG43). */
@Component({
  selector: 'app-admin-races-page',
  imports: [RouterLink, RaceForm],
  template: `
    <h1>Administration des courses</h1>
    @if (message(); as text) {
      <p class="banner banner-error" role="alert">{{ text }}</p>
    }
    @if (creating()) {
      <h2>Nouvelle course</h2>
      <app-race-form submitLabel="Créer la course" [busy]="busy()" [serverErrors]="serverErrors()"
                     (submitted)="create($event)" (cancelled)="creating.set(false)" />
    } @else {
      <div class="toolbar">
        <button type="button" class="button button-primary" (click)="startCreation()">Créer une course</button>
        <button type="button" class="button" (click)="load()">Actualiser</button>
      </div>
    }
    @if (races(); as list) {
      @if (list.length === 0) {
        <p>Aucune course.</p>
      }
      <ul class="card-list">
        @for (race of list; track race.id) {
          <li class="card">
            <h2><a [routerLink]="['/admin/courses', race.id]">{{ race.name }}</a></h2>
            <p>{{ date(race.raceDate) }} — {{ status(race) }}</p>
            <div class="toolbar">
              <a class="button" [routerLink]="['/admin/courses', race.id]">Gérer la course et les coureurs</a>
              <a class="button" [routerLink]="['/courses', race.id]">Tableau de bord</a>
            </div>
          </li>
        }
      </ul>
    }
  `,
})
export class AdminRacesPage implements OnInit {
  private readonly api = inject(ApiClient);
  protected readonly races = signal<readonly RaceResponse[] | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly busy = signal(false);
  protected readonly serverErrors = signal<ReadonlyMap<string, string>>(new Map());

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const result = await this.api.request<RaceResponse[]>('GET', API_PATHS.adminRaces, { timeoutMs: ADMIN_TIMEOUT_MS });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.races.set(result.body);
      return;
    }
    this.message.set(adminFailureMessage(result));
  }

  protected startCreation(): void {
    this.serverErrors.set(new Map());
    this.message.set(null);
    this.creating.set(true);
  }

  protected async create(request: RaceRequest): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    const result = await this.api.request<RaceResponse>('POST', API_PATHS.adminRaces, {
      body: request, timeoutMs: ADMIN_TIMEOUT_MS,
    });
    this.busy.set(false);
    if (result.responseClass === 'SUCCESS') {
      this.creating.set(false);
      this.message.set(null);
      await this.load();
      return;
    }
    this.serverErrors.set(fieldErrors(result));
    this.message.set(adminFailureMessage(result));
  }

  protected date(raceDate: string): string {
    return formatRaceDate(raceDate);
  }

  protected status(race: RaceResponse): string {
    return raceStatusLabel(race.status);
  }
}
