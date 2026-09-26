import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { RaceAction, raceActions, RunnerAction, runnerActions } from '../../core/action-visibility';
import { API_PATHS } from '../../core/api-paths';
import {
  AdminRunnerResponse,
  DnfReason,
  RaceRequest,
  RaceResponse,
  ReintegrationResponse,
} from '../../core/api.types';
import {
  dnfReasonLabel,
  formatDistance,
  formatElevation,
  formatLoopDuration,
  formatRaceDate,
  formatTimeOfDay,
  raceStatusLabel,
  runnerStatusLabel,
} from '../../core/formats';
import { ADMIN_TIMEOUT_MS, ClassifiedResult, fieldErrors } from '../../core/http-classification';
import { adminFailureMessage, shouldReloadAfterFailure } from '../../core/outcomes';
import { parseInteger, positiveInteger, requiredText } from '../../core/validation';
import { ApiClient, HttpMethod } from '../../infra/api-client';
import { ConfirmDialog } from '../../shared/confirm-dialog';
import { QrImage } from '../../shared/qr-image';
import { RaceForm } from './race-form';

/** Raisons proposées pour un DNF manuel : exactement trois, jamais TIMEOUT (réservé à l'auto-DNF, RG41). */
const MANUAL_DNF_REASONS: readonly { readonly value: DnfReason; readonly label: string }[] = [
  { value: 'VOLUNTARY', label: 'Abandon volontaire' },
  { value: 'MANUAL', label: "Décision de l'organisateur" },
  { value: 'OTHER', label: 'Autre' },
];

type PendingAction =
  | { readonly kind: 'delete-race' }
  | { readonly kind: 'start-race' }
  | { readonly kind: 'delete-runner'; readonly runner: AdminRunnerResponse }
  | { readonly kind: 'dnf'; readonly runner: AdminRunnerResponse }
  | { readonly kind: 'reintegrate'; readonly runner: AdminRunnerResponse }
  | { readonly kind: 'show-qr'; readonly runner: AdminRunnerResponse };

/**
 * Course et coureurs (RG37 à RG43) : actions proposées selon la table unique de RG38, confirmations des actions
 * irréversibles, DNF manuel et réintégration. Après chaque action, la course et les coureurs sont rechargés
 * depuis l'API ; aucune action n'est mise en file ni rejouée.
 */
@Component({
  selector: 'app-admin-race-page',
  imports: [RouterLink, ConfirmDialog, QrImage, RaceForm],
  template: `
    @if (race(); as data) {
      <h1>{{ data.name }}</h1>
      <p>{{ date(data.raceDate) }} — {{ raceStatus(data) }}
        @if (data.startedAt) {
          — départ à {{ time(data.startedAt) }}
        }
      </p>
      <p>Boucle : {{ distance(data.loopDistance) }} · {{ duration(data.loopDuration) }} ·
        {{ elevation(data.loopElevation) }} — {{ runners()?.length ?? 0 }} inscrits</p>
      <p><a [routerLink]="['/courses', data.id]">Tableau de bord public</a></p>

      <div aria-live="polite" role="status">
        @if (info(); as text) {
          <p class="banner banner-success">{{ text }}</p>
        }
      </div>
      @if (message(); as text) {
        <p class="banner banner-error" role="alert">{{ text }}</p>
      }

      @if (editingRace()) {
        <h2>Modifier la course</h2>
        <app-race-form [initial]="data" [loopEditable]="raceActionSet().has('EDIT_ALL_FIELDS')" [busy]="busy()"
                       [serverErrors]="raceErrors()" (submitted)="updateRace($event)" (cancelled)="editingRace.set(false)" />
      } @else {
        <div class="toolbar">
          @if (raceActionSet().has('EDIT_ALL_FIELDS') || raceActionSet().has('EDIT_NAME_AND_DATE')) {
            <button type="button" class="button" (click)="editingRace.set(true)">Modifier</button>
          }
          @if (raceActionSet().has('START')) {
            <button type="button" class="button button-primary" (click)="ask({ kind: 'start-race' })">Démarrer</button>
          }
          @if (raceActionSet().has('PRINT_QR')) {
            <a class="button" [routerLink]="['/admin/courses', data.id, 'qr']">Imprimer les QR codes</a>
          }
          @if (raceActionSet().has('DELETE')) {
            <button type="button" class="button button-danger" (click)="ask({ kind: 'delete-race' })">Supprimer</button>
          }
        </div>
      }

      <h2>Coureurs</h2>
      @if (runners(); as list) {
        @if (list.length === 0) {
          <p>Aucun inscrit.</p>
        }
        <ul class="card-list">
          @for (runner of list; track runner.id) {
            <li class="card">
              @if (editingRunnerId() === runner.id) {
                <form class="form" (submit)="updateRunner($event, runner)" novalidate>
                  <div class="field">
                    <label [for]="'bib-' + runner.id">Dossard</label>
                    <input [id]="'bib-' + runner.id" type="number" inputmode="numeric" min="1"
                           [readOnly]="!can(runner, 'EDIT_BIB_AND_NAME')" [value]="editBib()"
                           (input)="editBib.set(value($event))" [attr.aria-invalid]="runnerError('bib') !== null" />
                    <p class="field-error">{{ runnerError('bib') ?? '' }}</p>
                  </div>
                  <div class="field">
                    <label [for]="'name-' + runner.id">Nom</label>
                    <input [id]="'name-' + runner.id" type="text" maxlength="255" [value]="editName()"
                           (input)="editName.set(value($event))" [attr.aria-invalid]="runnerError('name') !== null" />
                    <p class="field-error">{{ runnerError('name') ?? '' }}</p>
                  </div>
                  <div class="toolbar">
                    <button type="submit" class="button button-primary" [disabled]="busy()">Enregistrer</button>
                    <button type="button" class="button" (click)="editingRunnerId.set(null)">Annuler</button>
                  </div>
                </form>
              } @else {
                <p class="runner-line"><strong>{{ runner.bib }}</strong> — {{ runner.name }} — {{ runnerStatus(runner) }}</p>
                <div class="toolbar">
                  @if (can(runner, 'EDIT_BIB_AND_NAME') || can(runner, 'EDIT_NAME')) {
                    <button type="button" class="button" (click)="startRunnerEdit(runner)">Modifier</button>
                  }
                  @if (can(runner, 'SHOW_QR')) {
                    <button type="button" class="button" (click)="ask({ kind: 'show-qr', runner })">Afficher le QR</button>
                  }
                  @if (can(runner, 'DECLARE_DNF')) {
                    <button type="button" class="button button-danger" (click)="askDnf(runner)">Déclarer DNF</button>
                  }
                  @if (can(runner, 'REINTEGRATE')) {
                    <button type="button" class="button button-primary" (click)="ask({ kind: 'reintegrate', runner })">Réintégrer</button>
                  }
                  @if (can(runner, 'DELETE')) {
                    <button type="button" class="button button-danger" (click)="ask({ kind: 'delete-runner', runner })">Supprimer</button>
                  }
                </div>
              }
            </li>
          }
        </ul>
      }
    } @else if (message(); as text) {
      <p class="banner banner-error" role="alert">{{ text }}</p>
    } @else {
      <p>Chargement…</p>
    }

    <app-confirm-dialog [open]="pending()?.kind === 'start-race'" title="Démarrer la course" confirmLabel="Démarrer"
                        [busy]="busy()" (confirmed)="startRace()" (cancelled)="closeDialog()">
      <p>Démarrer {{ race()?.name }} maintenant ? L'heure de départ est celle du serveur. Les inscriptions seront
        fermées. Action irréversible.</p>
    </app-confirm-dialog>

    <app-confirm-dialog [open]="pending()?.kind === 'delete-race'" title="Supprimer la course" confirmLabel="Supprimer"
                        [destructive]="true" [busy]="busy()" (confirmed)="deleteRace()" (cancelled)="closeDialog()">
      <p>Supprimer la course {{ race()?.name }} et ses {{ runners()?.length ?? 0 }} inscrits ? Action irréversible.</p>
    </app-confirm-dialog>

    <app-confirm-dialog [open]="pending()?.kind === 'delete-runner'" title="Supprimer le coureur" confirmLabel="Supprimer"
                        [destructive]="true" [busy]="busy()" (confirmed)="deleteRunner()" (cancelled)="closeDialog()">
      <p>Supprimer {{ pendingRunner()?.bib }} — {{ pendingRunner()?.name }} ?</p>
    </app-confirm-dialog>

    <app-confirm-dialog [open]="pending()?.kind === 'dnf'" title="Déclarer DNF" confirmLabel="Confirmer le DNF"
                        [destructive]="true" [busy]="busy()" [confirmDisabled]="dnfReason() === null"
                        (confirmed)="declareDnf()" (cancelled)="closeDialog()">
      <p>{{ pendingRunner()?.bib }} — {{ pendingRunner()?.name }} — course {{ race()?.name }}</p>
      <fieldset class="radio-group">
        <legend>Raison du DNF</legend>
        @for (reason of dnfReasons; track reason.value) {
          <div class="radio-option">
            <input type="radio" name="dnf-reason" [id]="'dnf-reason-' + reason.value" [value]="reason.value"
                   [checked]="dnfReason() === reason.value" (change)="dnfReason.set(reason.value)" />
            <label [for]="'dnf-reason-' + reason.value">{{ reason.label }}</label>
          </div>
        }
      </fieldset>
    </app-confirm-dialog>

    <app-confirm-dialog [open]="pending()?.kind === 'reintegrate'" title="Réintégrer le coureur" confirmLabel="Confirmer"
                        [busy]="busy()" (confirmed)="reintegrate()" (cancelled)="closeDialog()">
      <p>Réintégrer {{ pendingRunner()?.bib }} — {{ pendingRunner()?.name }} (DNF {{ pendingDnfReason() }} au yard
        {{ pendingRunner()?.dnfYard }}) ? Cette action corrige une erreur : les passages manquants seront recréés,
        marqués "corrigé" et exclus du calcul de l'allure.</p>
    </app-confirm-dialog>

    <app-confirm-dialog [open]="pending()?.kind === 'show-qr'" title="QR code du coureur" confirmLabel="Imprimer"
                        cancelLabel="Fermer" (confirmed)="print()" (cancelled)="closeDialog()">
      @if (pendingRunner(); as runner) {
        <p class="bib">Dossard <strong>{{ runner.bib }}</strong> — {{ runner.name }}</p>
        <app-qr-image [value]="runner.qrToken" [label]="'QR code du dossard ' + runner.bib" />
        <p class="token">{{ runner.qrToken }}</p>
      }
    </app-confirm-dialog>
  `,
})
export class AdminRacePage implements OnInit {
  readonly raceId = input.required<string>();

  private readonly api = inject(ApiClient);
  private readonly router = inject(Router);
  protected readonly dnfReasons = MANUAL_DNF_REASONS;
  protected readonly race = signal<RaceResponse | null>(null);
  protected readonly runners = signal<readonly AdminRunnerResponse[] | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly editingRace = signal(false);
  protected readonly raceErrors = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly pending = signal<PendingAction | null>(null);
  protected readonly dnfReason = signal<DnfReason | null>(null);
  protected readonly editingRunnerId = signal<number | null>(null);
  protected readonly editBib = signal('');
  protected readonly editName = signal('');
  private readonly runnerErrors = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly raceActionSet = computed(() => {
    const race = this.race();
    return race === null ? new Set<RaceAction>() : raceActions(race.status);
  });
  protected readonly pendingRunner = computed(() => {
    const action = this.pending();
    return action !== null && 'runner' in action ? action.runner : null;
  });
  protected readonly pendingDnfReason = computed(() => {
    const reason = this.pendingRunner()?.dnfReason ?? null;
    return reason === null ? '' : dnfReasonLabel(reason);
  });

  ngOnInit(): void {
    void this.reload();
  }

  protected can(runner: AdminRunnerResponse, action: RunnerAction): boolean {
    const race = this.race();
    return race !== null && runnerActions(race.status, runner.status).has(action);
  }

  protected ask(action: PendingAction): void {
    this.message.set(null);
    this.info.set(null);
    this.pending.set(action);
  }

  protected askDnf(runner: AdminRunnerResponse): void {
    this.dnfReason.set(null);
    this.ask({ kind: 'dnf', runner });
  }

  protected closeDialog(): void {
    this.pending.set(null);
  }

  protected print(): void {
    window.print();
  }

  protected async updateRace(request: RaceRequest): Promise<void> {
    const result = await this.act<RaceResponse>('PUT', API_PATHS.adminRace(this.raceId()), request);
    if (result === null) {
      return;
    }
    this.raceErrors.set(fieldErrors(result));
    if (result.responseClass === 'SUCCESS') {
      this.editingRace.set(false);
      this.info.set('Course modifiée');
    }
  }

  protected async startRace(): Promise<void> {
    const result = await this.act<RaceResponse>('POST', API_PATHS.adminStart(this.raceId()));
    if (result?.responseClass === 'SUCCESS' && result.body?.startedAt) {
      this.info.set(`Course démarrée à ${formatTimeOfDay(result.body.startedAt)}`);
    }
  }

  protected async deleteRace(): Promise<void> {
    const result = await this.act<void>('DELETE', API_PATHS.adminRace(this.raceId()), undefined, false);
    if (result?.responseClass === 'SUCCESS') {
      await this.router.navigateByUrl('/admin');
    }
  }

  protected async deleteRunner(): Promise<void> {
    const runner = this.pendingRunner();
    if (runner !== null) {
      await this.act<void>('DELETE', API_PATHS.adminRunner(runner.id));
    }
  }

  protected async declareDnf(): Promise<void> {
    const runner = this.pendingRunner();
    const reason = this.dnfReason();
    if (runner === null || reason === null) {
      return;
    }
    const result = await this.act<AdminRunnerResponse>('POST', API_PATHS.adminDnf(runner.id), { reason });
    if (result?.responseClass === 'SUCCESS' && result.body !== null) {
      this.info.set(`${result.body.bib} — ${result.body.name} : ${this.runnerStatus(result.body)}`);
    }
  }

  protected async reintegrate(): Promise<void> {
    const runner = this.pendingRunner();
    if (runner === null) {
      return;
    }
    const result = await this.act<ReintegrationResponse>('POST', API_PATHS.adminReintegration(runner.id));
    if (result?.responseClass === 'SUCCESS' && result.body !== null) {
      const yards = result.body.recreatedPassages.map((passage) => passage.yardNumber);
      this.info.set(`${result.body.runner.bib} — ${result.body.runner.name} : `
        + `${this.runnerStatus(result.body.runner)}. `
        + (yards.length === 0 ? 'Aucun passage à recréer' : `Passages recréés : yards ${yards.join(', ')}`));
    }
  }

  protected startRunnerEdit(runner: AdminRunnerResponse): void {
    this.runnerErrors.set(new Map());
    this.editBib.set(String(runner.bib));
    this.editName.set(runner.name);
    this.editingRunnerId.set(runner.id);
  }

  protected async updateRunner(event: Event, runner: AdminRunnerResponse): Promise<void> {
    event.preventDefault();
    const errors = new Map<string, string>();
    const bibError = positiveInteger(this.editBib());
    const nameError = requiredText(this.editName());
    if (bibError !== null) {
      errors.set('bib', bibError);
    }
    if (nameError !== null) {
      errors.set('name', nameError);
    }
    this.runnerErrors.set(errors);
    if (errors.size > 0) {
      return;
    }
    const result = await this.act<AdminRunnerResponse>('PUT', API_PATHS.adminRunner(runner.id), {
      bib: parseInteger(this.editBib()), name: this.editName().trim(),
    });
    if (result === null) {
      return;
    }
    this.runnerErrors.set(fieldErrors(result));
    if (result.responseClass === 'SUCCESS') {
      this.editingRunnerId.set(null);
    }
  }

  protected runnerError(field: string): string | null {
    return this.runnerErrors().get(field) ?? null;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected date(raceDate: string): string {
    return formatRaceDate(raceDate);
  }

  protected raceStatus(race: RaceResponse): string {
    return raceStatusLabel(race.status);
  }

  protected runnerStatus(runner: AdminRunnerResponse): string {
    return runnerStatusLabel(runner.status, runner.dnfReason, runner.dnfYard);
  }

  protected time(instant: string): string {
    return formatTimeOfDay(instant);
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

  /**
   * Action admin (RG43) : une seule requête, bouton désactivé pendant son exécution, jamais rejouée. Succès :
   * rechargement depuis l'API ; échec : message, puis rechargement si le serveur a refusé (données périmées).
   */
  private async act<T>(method: HttpMethod, path: string, body?: unknown, reloadAfterSuccess = true):
    Promise<ClassifiedResult<T> | null> {
    if (this.busy()) {
      return null;
    }
    this.busy.set(true);
    this.message.set(null);
    this.info.set(null);
    const result = await this.api.request<T>(method, path, { body, timeoutMs: ADMIN_TIMEOUT_MS });
    this.busy.set(false);
    this.pending.set(null);
    if (result.responseClass === 'SUCCESS') {
      if (reloadAfterSuccess) {
        await this.reload();
      }
      return result;
    }
    this.message.set(adminFailureMessage(result));
    if (shouldReloadAfterFailure(result)) {
      await this.reload();
    }
    return result;
  }

  private async reload(): Promise<void> {
    const [race, runners] = await Promise.all([
      this.api.request<RaceResponse>('GET', API_PATHS.adminRace(this.raceId()), { timeoutMs: ADMIN_TIMEOUT_MS }),
      this.api.request<AdminRunnerResponse[]>('GET', API_PATHS.adminRaceRunners(this.raceId()), {
        timeoutMs: ADMIN_TIMEOUT_MS,
      }),
    ]);
    if (race.responseClass === 'SUCCESS' && race.body !== null) {
      this.race.set(race.body);
    } else {
      this.message.set(adminFailureMessage(race));
    }
    if (runners.responseClass === 'SUCCESS' && runners.body !== null) {
      this.runners.set(runners.body);
    }
  }
}
