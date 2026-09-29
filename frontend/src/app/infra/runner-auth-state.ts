import { computed, Injectable, signal } from '@angular/core';
import { RunnerCredentialStore, RunnerSession } from '../core/runner-credentials';
import { indexedDbRunnerCredentialPersistence } from './indexed-db';

/**
 * État d'authentification du compte coureur (RG21 inc. 5), distinct de {@link AuthState} (ADMIN/SCANNER) : se
 * connecter ou se déconnecter en coureur ne touche jamais l'autre emplacement. Les identifiants ne sont jamais
 * journalisés ni placés dans une URL.
 */
@Injectable({ providedIn: 'root' })
export class RunnerAuthState {
  readonly store = new RunnerCredentialStore(indexedDbRunnerCredentialPersistence, () => Date.now());
  readonly session = signal<RunnerSession | null>(null);
  readonly pseudo = computed(() => this.session()?.pseudo ?? null);
  /** Message à afficher sur l'écran de connexion coureur (ex. session expirée). */
  readonly notice = signal<string | null>(null);

  private readonly unauthorizedListeners = new Set<() => void>();

  /** Valeur Authorization courante ; des identifiants mémorisés expirés sont effacés à cette lecture. */
  async authorization(): Promise<string | null> {
    const current = await this.store.current();
    if (current === null && this.session() !== null) {
      this.session.set(null);
    }
    return current?.authorization ?? null;
  }

  /** Réponse 2xx reçue avec les identifiants coureur : l'expiration glisse. */
  async recordActivity(): Promise<void> {
    await this.store.recordActivity();
    this.session.set(await this.store.current());
  }

  onUnauthorized(listener: () => void): void {
    this.unauthorizedListeners.add(listener);
  }

  /** 401 reçu sur `/api/account/**` avec les identifiants coureur. */
  reportUnauthorized(): void {
    this.unauthorizedListeners.forEach((listener) => listener());
  }
}
