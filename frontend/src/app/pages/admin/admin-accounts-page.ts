import { Component, inject, OnInit, signal } from '@angular/core';
import { API_PATHS } from '../../core/api-paths';
import { AccountSummary } from '../../core/api.types';
import { ADMIN_TIMEOUT_MS } from '../../core/http-classification';
import { adminFailureMessage } from '../../core/outcomes';
import { ApiClient } from '../../infra/api-client';
import { AccountAdminActions } from '../../shared/account-admin-actions';

/**
 * Écran admin « Comptes » (RG17, RG24 inc. 5) : liste des comptes coureurs par E25, recherche par pseudo (une seule
 * requête E25 par recherche), réinitialisation et suppression. Après une suppression, la liste est rechargée avec
 * le même filtre. Le filtre est envoyé tel que saisi : sa normalisation est faite par l'API (RG2).
 */
@Component({
  selector: 'app-admin-accounts-page',
  imports: [AccountAdminActions],
  template: `
    <h1>Comptes</h1>
    <form class="form" role="search" (submit)="search($event)" novalidate>
      <div class="field">
        <label for="account-search">Rechercher un pseudo</label>
        <input id="account-search" name="pseudo" type="search" autocomplete="off" autocapitalize="none"
               spellcheck="false" [value]="filter()" (input)="filter.set(value($event))" />
      </div>
      <button type="submit" class="button button-primary" [disabled]="loading()">Rechercher</button>
    </form>
    <div aria-live="polite" role="status">
      @if (info(); as text) {
        <p class="banner banner-success">{{ text }}</p>
      }
    </div>
    @if (message(); as text) {
      <p class="banner banner-error" role="alert">{{ text }}</p>
    }
    @if (accounts(); as list) {
      @if (list.length === 0) {
        <p>Aucun compte</p>
      }
      <ul class="card-list">
        @for (account of list; track account.accountId) {
          <li class="card">
            <p class="account-line"><strong>{{ account.pseudo }}</strong> — {{ account.runnerCount }}
              {{ account.runnerCount > 1 ? 'inscriptions' : 'inscription' }}</p>
            <div class="toolbar">
              <app-account-admin-actions [accountId]="account.accountId" [pseudo]="account.pseudo"
                                         [registrationCount]="account.runnerCount" (reported)="report($event)"
                                         (deleted)="reload()" />
            </div>
          </li>
        }
      </ul>
    } @else if (!message()) {
      <p>Chargement…</p>
    }
  `,
})
export class AdminAccountsPage implements OnInit {
  private readonly api = inject(ApiClient);
  protected readonly filter = signal('');
  protected readonly accounts = signal<readonly AccountSummary[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  /** Filtre de la dernière recherche envoyée, réutilisé au rechargement après une suppression. */
  private appliedFilter: string | undefined = undefined;

  ngOnInit(): void {
    void this.load(undefined);
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.loading()) {
      this.info.set(null);
      await this.load(this.filter());
    }
  }

  protected async reload(): Promise<void> {
    await this.load(this.appliedFilter);
  }

  protected report(outcome: { readonly ok: boolean; readonly message: string }): void {
    this.info.set(outcome.ok ? outcome.message : null);
    this.message.set(outcome.ok ? null : outcome.message);
  }

  private async load(filter: string | undefined): Promise<void> {
    this.loading.set(true);
    this.appliedFilter = filter;
    const result = await this.api.request<AccountSummary[]>('GET', API_PATHS.adminAccounts(filter), {
      timeoutMs: ADMIN_TIMEOUT_MS,
    });
    this.loading.set(false);
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.accounts.set(result.body);
      return;
    }
    this.message.set(adminFailureMessage(result));
  }
}
