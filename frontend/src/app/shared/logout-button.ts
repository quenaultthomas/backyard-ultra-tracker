import { Component, computed, inject, signal } from '@angular/core';
import { ScanQueueService } from '../infra/scan-queue.service';
import { SessionService } from '../infra/session.service';
import { ConfirmDialog } from './confirm-dialog';

/**
 * « Se déconnecter » (RG9) : efface les identifiants, jamais la file de scans. Confirmation si des scans sont
 * en attente.
 */
@Component({
  selector: 'app-logout-button',
  imports: [ConfirmDialog],
  template: `
    <button type="button" class="button" (click)="requestLogout()">Se déconnecter</button>
    <app-confirm-dialog [open]="confirming()" title="Se déconnecter ?" confirmLabel="Se déconnecter"
                        (confirmed)="logout()" (cancelled)="confirming.set(false)">
      <p>{{ pendingCount() }} scans en attente ne seront envoyés qu'après une nouvelle connexion. Se déconnecter ?</p>
    </app-confirm-dialog>
  `,
})
export class LogoutButton {
  private readonly session = inject(SessionService);
  private readonly queue = inject(ScanQueueService);
  protected readonly confirming = signal(false);
  protected readonly pendingCount = computed(() => this.queue.snapshot().pendingCount);

  protected requestLogout(): void {
    if (this.pendingCount() > 0) {
      this.confirming.set(true);
      return;
    }
    void this.logout();
  }

  protected async logout(): Promise<void> {
    this.confirming.set(false);
    await this.session.logout();
  }
}
