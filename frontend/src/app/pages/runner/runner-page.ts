import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { API_PATHS } from '../../core/api-paths';
import { PassageSource, RaceResponse, RaceStatus, RunnerDetailResponse } from '../../core/api.types';
import {
  formatDistance,
  formatElevation,
  formatLoopTime,
  formatPace,
  formatTimeOfDay,
  passageSourceLabel,
  runnerStatusLabel,
} from '../../core/formats';
import { BOARD_TIMEOUT_MS } from '../../core/http-classification';
import { loadFailureMessage } from '../../core/outcomes';
import { PollOutcome } from '../../core/polling';
import { ApiClient } from '../../infra/api-client';
import { startPagePolling } from '../../shared/page-lifecycle';

/** Détail public d'un coureur (RG34) : données de E5, polling au rythme de sa course (E2). */
@Component({
  selector: 'app-runner-page',
  imports: [RouterLink],
  template: `
    @if (notFound()) {
      <h1>Coureur introuvable</h1>
      <p><a routerLink="/">Retour à l'accueil</a></p>
    } @else {
      @if (runner(); as data) {
        <h1>Dossard {{ data.bib }} — {{ data.name }}</h1>
        <p>
          <a [routerLink]="['/courses', data.raceId]">Tableau de bord{{ race() ? ' — ' + race()?.name : '' }}</a>
        </p>
        <dl class="stats">
          <div><dt>Statut</dt><dd>{{ status(data) }}
            @if (data.corrected) {
              <span class="badge">corrigé</span>
            }
          </dd></div>
          <div><dt>Tours</dt><dd>{{ data.completedLoops }}</dd></div>
          <div><dt>Distance</dt><dd>{{ distance(data.distanceMeters) }}</dd></div>
          <div><dt>D+</dt><dd>{{ elevation(data.elevationMeters) }}</dd></div>
          <div><dt>Allure</dt><dd>{{ pace(data.averagePaceSecondsPerKm) }}</dd></div>
        </dl>
        <h2>Passages</h2>
        @if (data.passages.length === 0) {
          <p>Aucun passage.</p>
        } @else {
          <table class="runner-table">
            <thead>
              <tr>
                <th scope="col">Yard</th>
                <th scope="col">Source</th>
                <th scope="col">Heure de scan</th>
                <th scope="col">Temps de boucle</th>
              </tr>
            </thead>
            <tbody>
              @for (passage of data.passages; track passage.yardNumber) {
                <tr>
                  <td data-label="Yard">{{ passage.yardNumber }}</td>
                  <td data-label="Source">{{ source(passage.source) }}</td>
                  <td data-label="Heure de scan">{{ time(passage.scannedAt) }}</td>
                  <td data-label="Temps de boucle">{{ loopTime(passage.loopTimeMillis) }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      } @else if (!error()) {
        <p>Chargement…</p>
      }
      @if (error(); as message) {
        <p class="banner banner-error">{{ message }}</p>
      }
    }
  `,
})
export class RunnerPage implements OnInit {
  readonly runnerId = input.required<string>();

  private readonly api = inject(ApiClient);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly runner = signal<RunnerDetailResponse | null>(null);
  protected readonly race = signal<RaceResponse | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notFound = signal(false);

  ngOnInit(): void {
    startPagePolling(() => this.poll(), this.destroyRef);
  }

  private async poll(): Promise<PollOutcome> {
    const result = await this.api.request<RunnerDetailResponse>('GET', API_PATHS.runner(this.runnerId()), {
      timeoutMs: BOARD_TIMEOUT_MS,
    });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.runner.set(result.body);
      this.error.set(null);
      return { raceStatus: await this.raceStatus(result.body.raceId), stop: false };
    }
    if (result.status === 404 || result.status === 400) {
      this.notFound.set(true);
      return { raceStatus: null, stop: true };
    }
    this.error.set(loadFailureMessage(result, navigator.onLine));
    return { raceStatus: null, stop: false };
  }

  /** Statut de la course du coureur (E2), qui fixe le rythme du polling ; null si indisponible. */
  private async raceStatus(raceId: number): Promise<RaceStatus | null> {
    const result = await this.api.request<RaceResponse>('GET', API_PATHS.race(raceId), { timeoutMs: BOARD_TIMEOUT_MS });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.race.set(result.body);
      return result.body.status;
    }
    return null;
  }

  protected status(runner: RunnerDetailResponse): string {
    return runnerStatusLabel(runner.status, runner.dnfReason, runner.dnfYard);
  }

  protected distance(meters: number): string {
    return formatDistance(meters);
  }

  protected elevation(meters: number): string {
    return formatElevation(meters);
  }

  protected pace(secondsPerKm: number | null): string {
    return formatPace(secondsPerKm);
  }

  protected source(source: PassageSource): string {
    return passageSourceLabel(source);
  }

  protected time(instant: string | null): string {
    return formatTimeOfDay(instant);
  }

  protected loopTime(millis: number | null): string {
    return formatLoopTime(millis);
  }
}
