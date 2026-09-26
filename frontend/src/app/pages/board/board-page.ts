import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { API_PATHS } from '../../core/api-paths';
import { RaceBoardResponse, RunnerBoardEntry } from '../../core/api.types';
import { NEW_YARD_LABEL, yardCountdown } from '../../core/countdown';
import {
  formatDistance,
  formatElevation,
  formatLoopDuration,
  formatPace,
  formatRaceDate,
  formatTimeOfDay,
  raceStatusLabel,
  runnerStatusLabel,
} from '../../core/formats';
import { BOARD_TIMEOUT_MS } from '../../core/http-classification';
import { loadFailureMessage } from '../../core/outcomes';
import { PollOutcome, staleForSeconds } from '../../core/polling';
import { ApiClient } from '../../infra/api-client';
import { ClockOffsetService } from '../../infra/clock-offset.service';
import { secondTicker, startPagePolling } from '../../shared/page-lifecycle';

/**
 * Tableau de bord public d'une course (RG29 à RG33, RG35). Toutes les valeurs viennent de E4 (RG1) ; les
 * coureurs sont affichés dans l'ordre de l'API (RG32) ; le numéro de yard n'est jamais incrémenté localement.
 */
@Component({
  selector: 'app-board-page',
  imports: [RouterLink],
  template: `
    @if (notFound()) {
      <h1>Course introuvable</h1>
      <p><a routerLink="/">Retour à l'accueil</a></p>
    } @else {
      @if (board(); as data) {
        <h1>{{ data.race.name }}</h1>
        <p class="subtitle">{{ date(data.race.raceDate) }} — {{ raceStatus(data) }}</p>
        <p class="loop-parameters">
          Boucle : {{ distance(data.race.loopDistance) }} · {{ duration(data.race.loopDuration) }} ·
          {{ elevation(data.race.loopElevation) }}
        </p>
        @if (data.race.status === 'SETUP') {
          <p class="banner banner-info">Course non démarrée</p>
        }
        @if (data.currentYard >= 1) {
          <div class="yard-clock">
            <span class="yard-number">Yard {{ data.currentYard }}</span>
            @if (countdown(); as clock) {
              <span class="countdown">{{ clock.remaining }}</span>
              @if (clock.bellRang) {
                <span class="countdown-note">{{ newYardLabel }}</span>
              }
            }
          </div>
        }
      } @else if (!error()) {
        <p>Chargement…</p>
      }
      <div aria-live="polite" role="status">
        @if (board()?.race?.status === 'FINISHED') {
          <p class="banner banner-finished">
            Course terminée —
            @if (winner(); as champion) {
              Vainqueur : dossard {{ champion.bib }} — {{ champion.name }} — {{ champion.completedLoops }} tours
            } @else {
              sans vainqueur
            }
          </p>
        }
        @if (staleSeconds() !== null) {
          <p class="banner banner-warning">Données non actualisées depuis {{ staleSeconds() }} s</p>
        }
      </div>
      @if (error(); as message) {
        <p class="banner banner-error">{{ message }}</p>
      }
      @if (lastSuccessAt(); as updatedAt) {
        <p class="freshness">Mis à jour à {{ time(updatedAt) }}</p>
      }
      @if (board(); as data) {
        <table class="runner-table">
          <caption class="visually-hidden">Coureurs par dossard</caption>
          <thead>
            <tr>
              <th scope="col">Dossard</th>
              <th scope="col">Nom</th>
              <th scope="col">Statut</th>
              <th scope="col">Tours</th>
              <th scope="col">Distance</th>
              <th scope="col">D+</th>
              <th scope="col">Allure</th>
            </tr>
          </thead>
          <tbody>
            @for (runner of data.runners; track runner.runnerId) {
              <tr [class.runner-winner]="runner.status === 'WINNER'" [class.runner-dnf]="runner.status === 'DNF'">
                <td data-label="Dossard">{{ runner.bib }}</td>
                <td data-label="Nom">
                  <a [routerLink]="['/coureurs', runner.runnerId]">{{ runner.name }}</a>
                  @if (runner.corrected) {
                    <span class="badge">corrigé</span>
                  }
                </td>
                <td data-label="Statut">
                  @if (runner.status === 'WINNER') {
                    <span class="winner-icon" aria-hidden="true">🏆</span>
                  }
                  {{ status(runner) }}
                </td>
                <td data-label="Tours">{{ runner.completedLoops }}</td>
                <td data-label="Distance">{{ distance(runner.distanceMeters) }}</td>
                <td data-label="D+">{{ elevation(runner.elevationMeters) }}</td>
                <td data-label="Allure">{{ pace(runner.averagePaceSecondsPerKm) }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export class BoardPage implements OnInit {
  readonly raceId = input.required<string>();

  private readonly api = inject(ApiClient);
  private readonly clockOffset = inject(ClockOffsetService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly now = secondTicker();

  protected readonly board = signal<RaceBoardResponse | null>(null);
  protected readonly lastSuccessAt = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notFound = signal(false);
  protected readonly newYardLabel = NEW_YARD_LABEL;

  protected readonly countdown = computed(() => {
    const endsAt = this.board()?.currentYardEndsAt ?? null;
    return endsAt === null ? null : yardCountdown(endsAt, this.now(), this.clockOffset.offsetMs());
  });
  protected readonly staleSeconds = computed(() => staleForSeconds(this.lastSuccessAt(), this.now()));
  protected readonly winner = computed(() =>
    this.board()?.runners.find((runner) => runner.status === 'WINNER') ?? null);

  ngOnInit(): void {
    startPagePolling(() => this.poll(), this.destroyRef);
  }

  private async poll(): Promise<PollOutcome> {
    const result = await this.api.request<RaceBoardResponse>('GET', API_PATHS.board(this.raceId()), {
      timeoutMs: BOARD_TIMEOUT_MS,
    });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.clockOffset.record(result.sentAt, result.receivedAt, result.body.serverTime);
      this.board.set(result.body);
      this.lastSuccessAt.set(result.receivedAt);
      this.error.set(null);
      return { raceStatus: result.body.race.status, stop: false };
    }
    if (result.status === 404 || result.status === 400) {
      this.notFound.set(true);
      return { raceStatus: null, stop: true };
    }
    this.error.set(loadFailureMessage(result, navigator.onLine));
    return { raceStatus: null, stop: false };
  }

  protected date(raceDate: string): string {
    return formatRaceDate(raceDate);
  }

  protected raceStatus(board: RaceBoardResponse): string {
    return raceStatusLabel(board.race.status);
  }

  protected status(runner: RunnerBoardEntry): string {
    return runnerStatusLabel(runner.status, runner.dnfReason, runner.dnfYard);
  }

  protected distance(meters: number): string {
    return formatDistance(meters);
  }

  protected elevation(meters: number): string {
    return formatElevation(meters);
  }

  protected duration(seconds: number): string {
    return formatLoopDuration(seconds);
  }

  protected pace(secondsPerKm: number | null): string {
    return formatPace(secondsPerKm);
  }

  protected time(instant: number): string {
    return formatTimeOfDay(instant);
  }
}
