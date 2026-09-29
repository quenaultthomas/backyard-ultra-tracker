import { Component, inject, input, output, signal } from '@angular/core';
import { passwordChangeErrors } from '../core/account-validation';
import { API_PATHS } from '../core/api-paths';
import { PasswordChangeRequest } from '../core/api.types';
import { ADMIN_TIMEOUT_MS, fieldErrors } from '../core/http-classification';
import { accountDeletionConfirmation, adminFailureMessage } from '../core/outcomes';
import { ApiClient } from '../infra/api-client';
import { ConfirmDialog } from './confirm-dialog';

type PendingAccountAction = 'reset-password' | 'delete-account';

/**
 * Actions de l'admin sur un compte coureur (RG14, RG17, RG20 inc. 5) : « Réinitialiser le mot de passe » (saisie du
 * mot de passe provisoire, une seule requête E22) et « Supprimer le compte » (confirmation, une seule requête E24).
 * Aucune requête avant « Confirmer » ; aucune action n'est rejouée automatiquement (RG43 inc. 4).
 */
@Component({
  selector: 'app-account-admin-actions',
  imports: [ConfirmDialog],
  template: `
    <button type="button" class="button" (click)="ask('reset-password')">Réinitialiser le mot de passe</button>
    <button type="button" class="button button-danger" (click)="ask('delete-account')">Supprimer le compte</button>

    <app-confirm-dialog [open]="pending() === 'reset-password'" title="Réinitialiser le mot de passe"
                        confirmLabel="Confirmer" [busy]="busy()" (confirmed)="resetPassword()" (cancelled)="close()">
      <p>Mot de passe provisoire du compte {{ pseudo() }}, à transmettre de vive voix.</p>
      <div class="field">
        <label [for]="'provisional-password-' + accountId()">Mot de passe provisoire</label>
        <input [id]="'provisional-password-' + accountId()" type="password" autocomplete="new-password"
               [value]="newPassword()" (input)="newPassword.set(value($event))"
               [attr.aria-invalid]="error('newPassword') !== null" />
        <p class="field-error">{{ error('newPassword') ?? '' }}</p>
      </div>
      <div class="field">
        <label [for]="'provisional-confirmation-' + accountId()">Confirmer le mot de passe provisoire</label>
        <input [id]="'provisional-confirmation-' + accountId()" type="password" autocomplete="new-password"
               [value]="confirmation()" (input)="confirmation.set(value($event))"
               [attr.aria-invalid]="error('confirmation') !== null" />
        <p class="field-error">{{ error('confirmation') ?? '' }}</p>
      </div>
    </app-confirm-dialog>

    <app-confirm-dialog [open]="pending() === 'delete-account'" title="Supprimer le compte" confirmLabel="Confirmer"
                        [destructive]="true" [busy]="busy()" (confirmed)="deleteAccount()" (cancelled)="close()">
      <p>{{ deletionText() }}</p>
    </app-confirm-dialog>
  `,
})
export class AccountAdminActions {
  readonly accountId = input.required<number>();
  readonly pseudo = input.required<string>();
  readonly registrationCount = input.required<number>();
  /** Message de réussite ou d'échec, affiché par la page. */
  readonly reported = output<{ readonly ok: boolean; readonly message: string }>();
  /** Compte supprimé : la page recharge sa liste. */
  readonly deleted = output<void>();

  private readonly api = inject(ApiClient);
  protected readonly pending = signal<PendingAccountAction | null>(null);
  protected readonly busy = signal(false);
  protected readonly newPassword = signal('');
  protected readonly confirmation = signal('');
  private readonly errors = signal<ReadonlyMap<string, string>>(new Map());

  protected ask(action: PendingAccountAction): void {
    this.newPassword.set('');
    this.confirmation.set('');
    this.errors.set(new Map());
    this.pending.set(action);
  }

  protected close(): void {
    this.pending.set(null);
    this.newPassword.set('');
    this.confirmation.set('');
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected error(field: string): string | null {
    return this.errors().get(field) ?? null;
  }

  protected deletionText(): string {
    return accountDeletionConfirmation(this.pseudo(), this.registrationCount());
  }

  protected async resetPassword(): Promise<void> {
    const errors = passwordChangeErrors(this.newPassword(), this.confirmation());
    this.errors.set(errors);
    if (errors.size > 0 || this.busy()) {
      return;
    }
    const body: PasswordChangeRequest = { newPassword: this.newPassword() };
    this.busy.set(true);
    const result = await this.api.request<void>('PUT', API_PATHS.adminAccountPassword(this.accountId()), {
      body, timeoutMs: ADMIN_TIMEOUT_MS,
    });
    this.busy.set(false);
    if (result.responseClass === 'SUCCESS') {
      this.close();
      this.reported.emit({ ok: true, message: `Mot de passe du compte ${this.pseudo()} réinitialisé` });
      return;
    }
    this.errors.set(fieldErrors(result));
    this.close();
    this.reported.emit({ ok: false, message: adminFailureMessage(result) });
  }

  protected async deleteAccount(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    const result = await this.api.request<void>('DELETE', API_PATHS.adminAccount(this.accountId()), {
      timeoutMs: ADMIN_TIMEOUT_MS,
    });
    this.busy.set(false);
    this.close();
    if (result.responseClass === 'SUCCESS') {
      this.reported.emit({ ok: true, message: `Compte ${this.pseudo()} supprimé` });
      this.deleted.emit();
      return;
    }
    this.reported.emit({ ok: false, message: adminFailureMessage(result) });
  }
}
