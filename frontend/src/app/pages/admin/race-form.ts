import { Component, computed, input, OnInit, output, signal } from '@angular/core';
import { RaceRequest, RaceResponse } from '../../core/api.types';
import { formatLoopDuration } from '../../core/formats';
import { isoDate, nonNegativeInteger, parseInteger, positiveInteger, requiredText } from '../../core/validation';

type RaceField = keyof RaceRequest;

/**
 * Formulaire de création ou de modification d'une course (RG37) : validation de format (RG1), puis erreurs du
 * serveur par champ (RG3). Les champs de boucle sont en lecture seule quand seuls le nom et la date sont
 * modifiables (RG38).
 */
@Component({
  selector: 'app-race-form',
  template: `
    <form class="form" (submit)="submit($event)" novalidate>
      <div class="field">
        <label [for]="id('name')">Nom</label>
        <input [id]="id('name')" type="text" maxlength="255" [value]="name()" (input)="name.set(value($event))"
               [attr.aria-invalid]="error('name') !== null" [attr.aria-describedby]="id('name-error')" />
        <p [id]="id('name-error')" class="field-error">{{ error('name') ?? '' }}</p>
      </div>
      <div class="field">
        <label [for]="id('raceDate')">Date</label>
        <input [id]="id('raceDate')" type="date" [value]="raceDate()" (input)="raceDate.set(value($event))"
               [attr.aria-invalid]="error('raceDate') !== null" [attr.aria-describedby]="id('raceDate-error')" />
        <p [id]="id('raceDate-error')" class="field-error">{{ error('raceDate') ?? '' }}</p>
      </div>
      <div class="field">
        <label [for]="id('loopDistance')">Distance de boucle (m)</label>
        <input [id]="id('loopDistance')" type="number" inputmode="numeric" min="1" [readOnly]="!loopEditable()"
               [value]="loopDistance()" (input)="loopDistance.set(value($event))"
               [attr.aria-invalid]="error('loopDistance') !== null" [attr.aria-describedby]="id('loopDistance-error')" />
        <p [id]="id('loopDistance-error')" class="field-error">{{ error('loopDistance') ?? '' }}</p>
      </div>
      <div class="field">
        <label [for]="id('loopDuration')">Durée de boucle (s)</label>
        <div class="inline-field">
          <input [id]="id('loopDuration')" type="number" inputmode="numeric" min="1" [readOnly]="!loopEditable()"
                 [value]="loopDuration()" (input)="loopDuration.set(value($event))"
                 [attr.aria-invalid]="error('loopDuration') !== null"
                 [attr.aria-describedby]="id('loopDuration-preview') + ' ' + id('loopDuration-error')" />
          <output [id]="id('loopDuration-preview')" class="preview">{{ durationPreview() }}</output>
        </div>
        <p [id]="id('loopDuration-error')" class="field-error">{{ error('loopDuration') ?? '' }}</p>
      </div>
      <div class="field">
        <label [for]="id('loopElevation')">D+ par boucle (m)</label>
        <input [id]="id('loopElevation')" type="number" inputmode="numeric" min="0" [readOnly]="!loopEditable()"
               [value]="loopElevation()" (input)="loopElevation.set(value($event))"
               [attr.aria-invalid]="error('loopElevation') !== null" [attr.aria-describedby]="id('loopElevation-error')" />
        <p [id]="id('loopElevation-error')" class="field-error">{{ error('loopElevation') ?? '' }}</p>
      </div>
      <div class="toolbar">
        <button type="submit" class="button button-primary" [disabled]="busy()">{{ submitLabel() }}</button>
        <button type="button" class="button" (click)="cancelled.emit()">Annuler</button>
      </div>
    </form>
  `,
})
export class RaceForm implements OnInit {
  private static nextId = 0;

  readonly initial = input<RaceResponse | null>(null);
  readonly loopEditable = input(true);
  readonly submitLabel = input('Enregistrer');
  readonly busy = input(false);
  readonly serverErrors = input<ReadonlyMap<string, string>>(new Map());
  readonly submitted = output<RaceRequest>();
  readonly cancelled = output<void>();

  protected readonly name = signal('');
  protected readonly raceDate = signal('');
  protected readonly loopDistance = signal('');
  protected readonly loopDuration = signal('');
  protected readonly loopElevation = signal('');
  private readonly localErrors = signal<ReadonlyMap<string, string>>(new Map());
  private readonly formId = `race-form-${RaceForm.nextId++}`;

  protected readonly durationPreview = computed(() => {
    const seconds = parseInteger(this.loopDuration());
    return seconds === null || seconds <= 0 ? '' : formatLoopDuration(seconds);
  });

  ngOnInit(): void {
    const race = this.initial();
    if (race !== null) {
      this.name.set(race.name);
      this.raceDate.set(race.raceDate);
      this.loopDistance.set(String(race.loopDistance));
      this.loopDuration.set(String(race.loopDuration));
      this.loopElevation.set(String(race.loopElevation));
    }
  }

  protected id(suffix: string): string {
    return `${this.formId}-${suffix}`;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected error(field: RaceField): string | null {
    return this.localErrors().get(field) ?? this.serverErrors().get(field) ?? null;
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const errors = new Map<string, string>();
    const checks: [RaceField, string | null][] = [
      ['name', requiredText(this.name())],
      ['raceDate', isoDate(this.raceDate())],
      ['loopDistance', positiveInteger(this.loopDistance())],
      ['loopDuration', positiveInteger(this.loopDuration())],
      ['loopElevation', nonNegativeInteger(this.loopElevation())],
    ];
    checks.filter(([, message]) => message !== null).forEach(([field, message]) => errors.set(field, message ?? ''));
    this.localErrors.set(errors);
    if (errors.size > 0) {
      return;
    }
    this.submitted.emit({
      name: this.name().trim(),
      raceDate: this.raceDate().trim(),
      loopDistance: parseInteger(this.loopDistance()) ?? 0,
      loopDuration: parseInteger(this.loopDuration()) ?? 0,
      loopElevation: parseInteger(this.loopElevation()) ?? 0,
    });
  }
}
