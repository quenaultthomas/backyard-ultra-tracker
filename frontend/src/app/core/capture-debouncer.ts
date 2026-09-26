/**
 * Anti-rebond des captures (RG17) : une relecture du même qrToken moins de 10 s après sa dernière capture
 * sur cet appareil est ignorée. À 10 s pile ou plus, c'est une nouvelle capture. Mémoire non persistée (RG27).
 */

export const DEBOUNCE_WINDOW_MS = 10_000;

export class CaptureDebouncer {
  private readonly lastCaptureAt = new Map<string, number>();

  /** Vrai si la lecture doit être ignorée. */
  isRecentCapture(qrToken: string, now: number): boolean {
    const previous = this.lastCaptureAt.get(qrToken);
    return previous !== undefined && now - previous < DEBOUNCE_WINDOW_MS;
  }

  recordCapture(qrToken: string, now: number): void {
    this.lastCaptureAt.set(qrToken, now);
  }
}
