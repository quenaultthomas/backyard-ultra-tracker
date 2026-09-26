import { Injectable, signal } from '@angular/core';
import { FeedbackSound, ScanFeedback } from '../core/scan-feedback';

const SOUND_SETTING_KEY = 'backyard.sound';

interface Beep {
  readonly frequency: number;
  readonly durationMs: number;
}

const SOUNDS: Readonly<Record<FeedbackSound, readonly Beep[]>> = {
  capture: [{ frequency: 880, durationMs: 100 }],
  success: [{ frequency: 1320, durationMs: 100 }, { frequency: 1320, durationMs: 100 }],
  error: [{ frequency: 440, durationMs: 150 }, { frequency: 440, durationMs: 150 }, { frequency: 440, durationMs: 150 }],
};

const GAP_MS = 80;

/** Son et vibration du retour de scan (RG50). Le son se coupe par un réglage mémorisé sur l'appareil. */
@Injectable({ providedIn: 'root' })
export class FeedbackPlayer {
  readonly soundEnabled = signal(localStorage.getItem(SOUND_SETTING_KEY) !== 'off');
  private audioContext: AudioContext | null = null;

  toggleSound(): void {
    const enabled = !this.soundEnabled();
    this.soundEnabled.set(enabled);
    localStorage.setItem(SOUND_SETTING_KEY, enabled ? 'on' : 'off');
  }

  /** À appeler lors d'un geste de l'utilisateur : les navigateurs n'autorisent l'audio qu'après un geste. */
  unlockAudio(): void {
    if (this.audioContext === null && typeof AudioContext !== 'undefined') {
      this.audioContext = new AudioContext();
    }
    void this.audioContext?.resume();
  }

  play(feedback: ScanFeedback): void {
    if (feedback.sound !== null && this.soundEnabled()) {
      this.beep(SOUNDS[feedback.sound]);
    }
    if (feedback.vibration !== null && typeof navigator.vibrate === 'function') {
      navigator.vibrate([...feedback.vibration]);
    }
  }

  private beep(beeps: readonly Beep[]): void {
    this.unlockAudio();
    const context = this.audioContext;
    if (context === null) {
      return;
    }
    let start = context.currentTime;
    for (const beep of beeps) {
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.frequency.value = beep.frequency;
      gain.gain.value = 0.25;
      oscillator.connect(gain).connect(context.destination);
      oscillator.start(start);
      oscillator.stop(start + beep.durationMs / 1000);
      start += (beep.durationMs + GAP_MS) / 1000;
    }
  }
}
