import { computed, Injectable, signal } from '@angular/core';
import { CredentialStore, Session } from '../core/credentials';
import { indexedDbCredentialPersistence } from './indexed-db';

/**
 * État d'authentification partagé par le client HTTP et le service de session (RG6 à RG10). Les identifiants
 * ne sont jamais journalisés ni placés dans une URL.
 */
@Injectable({ providedIn: 'root' })
export class AuthState {
  readonly store = new CredentialStore(indexedDbCredentialPersistence, () => Date.now());
  readonly session = signal<Session | null>(null);
  readonly role = computed(() => this.session()?.role ?? null);
  /** Message à afficher sur l'écran de connexion (ex. session expirée). */
  readonly notice = signal<string | null>(null);

  private readonly unauthorizedListeners = new Set<() => void>();

  /** Valeur Authorization courante ; des identifiants mémorisés expirés sont effacés à cette lecture (RG7). */
  async authorization(): Promise<string | null> {
    const current = await this.store.current();
    if (current === null && this.session() !== null) {
      this.session.set(null);
    }
    return current?.authorization ?? null;
  }

  onUnauthorized(listener: () => void): void {
    this.unauthorizedListeners.add(listener);
  }

  /** 401 reçu sur une requête protégée (RG10). */
  reportUnauthorized(): void {
    this.unauthorizedListeners.forEach((listener) => listener());
  }
}
