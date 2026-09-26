import { computed, Injectable, signal } from '@angular/core';
import {
  ClockOffsetMeasure,
  clockStatus,
  measureClockOffset,
  offsetOf,
  readClockOffsetMeasure,
} from '../core/clock-offset';

const STORAGE_KEY = 'backyard.clock-offset';

/**
 * Dernière mesure du décalage d'horloge avec le serveur (RG4), conservée localement même hors ligne.
 * Ce n'est pas un secret : `localStorage` suffit.
 */
@Injectable({ providedIn: 'root' })
export class ClockOffsetService {
  readonly measure = signal<ClockOffsetMeasure | null>(loadMeasure());
  readonly status = computed(() => clockStatus(this.measure()));
  readonly offsetMs = computed(() => offsetOf(this.measure()));

  record(sentAt: number, receivedAt: number, serverTime: string): void {
    const measure = measureClockOffset(sentAt, receivedAt, serverTime);
    this.measure.set(measure);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(measure));
  }
}

function loadMeasure(): ClockOffsetMeasure | null {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored === null) {
    return null;
  }
  try {
    return readClockOffsetMeasure(JSON.parse(stored) as unknown);
  } catch (error: unknown) {
    console.warn('Mesure de décalage d\'horloge illisible, ignorée', error);
    localStorage.removeItem(STORAGE_KEY);
    return null;
  }
}
