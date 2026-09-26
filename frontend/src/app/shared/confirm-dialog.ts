import { afterRenderEffect, Component, ElementRef, input, output, viewChild } from '@angular/core';

/**
 * Fenêtre de confirmation modale (RG41, RG42, RG49, RG51) : dialogue natif, focus initial sur « Annuler »,
 * focus maintenu dans la fenêtre, Échap équivaut à « Annuler », focus rendu au bouton d'origine à la fermeture.
 * Le bouton de confirmation est désactivé pendant la requête : un double clic n'envoie qu'une requête.
 */
@Component({
  selector: 'app-confirm-dialog',
  template: `
    <dialog #dialog class="dialog" [attr.aria-labelledby]="titleId" (cancel)="onCancelEvent($event)"
            (keydown)="trapFocus($event)">
      <h2 [id]="titleId">{{ title() }}</h2>
      <div class="dialog-body">
        <ng-content />
      </div>
      <div class="dialog-actions">
        <button #cancelButton type="button" class="button" (click)="cancel()">{{ cancelLabel() }}</button>
        <button type="button" class="button" [class.button-danger]="destructive()" [class.button-primary]="!destructive()"
                [disabled]="confirmDisabled() || busy()" (click)="confirmed.emit()">
          {{ confirmLabel() }}
        </button>
      </div>
    </dialog>
  `,
})
export class ConfirmDialog {
  private static nextId = 0;

  readonly open = input.required<boolean>();
  readonly title = input.required<string>();
  readonly confirmLabel = input('Confirmer');
  readonly cancelLabel = input('Annuler');
  readonly destructive = input(false);
  readonly confirmDisabled = input(false);
  readonly busy = input(false);
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  protected readonly titleId = `dialog-title-${ConfirmDialog.nextId++}`;
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly cancelButton = viewChild.required<ElementRef<HTMLButtonElement>>('cancelButton');
  private returnFocusTo: HTMLElement | null = null;

  constructor() {
    afterRenderEffect(() => {
      const element = this.dialog().nativeElement;
      if (this.open() && !element.open) {
        this.returnFocusTo = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        element.showModal();
        this.cancelButton().nativeElement.focus();
      } else if (!this.open() && element.open) {
        element.close();
        this.returnFocusTo?.focus();
        this.returnFocusTo = null;
      }
    });
  }

  protected cancel(): void {
    if (!this.busy()) {
      this.cancelled.emit();
    }
  }

  /** Échap : même effet qu'« Annuler », la fermeture est pilotée par l'état `open`. */
  protected onCancelEvent(event: Event): void {
    event.preventDefault();
    this.cancel();
  }

  protected trapFocus(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }
    const focusable = Array.from(this.dialog().nativeElement.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href]',
    ));
    if (focusable.length === 0) {
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
