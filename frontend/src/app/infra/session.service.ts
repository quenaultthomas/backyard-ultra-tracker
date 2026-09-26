import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { API_PATHS } from '../core/api-paths';
import { Role, SessionResponse } from '../core/api.types';
import { basicAuthorization } from '../core/credentials';
import { ADMIN_TIMEOUT_MS, errorMessage, isServerUnreachable } from '../core/http-classification';
import { ApiClient } from './api-client';
import { AuthState } from './auth-state';
import { ClockOffsetService } from './clock-offset.service';

export type LoginResult = { readonly ok: true; readonly role: Role } | { readonly ok: false; readonly message: string };

export const SESSION_EXPIRED = 'Session expirée ou identifiants modifiés : reconnectez-vous';

/**
 * Connexion, déconnexion et expiration de session (RG6 à RG10). La connexion valide les identifiants par E19
 * `GET /api/scan/me` et mesure le décalage d'horloge (RG4, RG52).
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly api = inject(ApiClient);
  private readonly authState = inject(AuthState);
  private readonly clockOffset = inject(ClockOffsetService);
  private readonly router = inject(Router);

  /** Reprise des identifiants SCANNER mémorisés, terminée avant toute décision d'accès. */
  readonly ready: Promise<void> = this.restore();

  constructor() {
    this.authState.onUnauthorized(() => {
      void this.expire();
    });
  }

  async login(username: string, password: string, rememberFor24h: boolean): Promise<LoginResult> {
    const authorization = basicAuthorization(username, password);
    const result = await this.api.request<SessionResponse>('GET', API_PATHS.session, {
      timeoutMs: ADMIN_TIMEOUT_MS, authorization,
    });
    if (result.responseClass === 'SUCCESS' && result.body !== null) {
      this.clockOffset.record(result.sentAt, result.receivedAt, result.body.serverTime);
      const session = await this.authState.store.signIn(result.body.username, result.body.role, authorization,
        rememberFor24h);
      this.authState.session.set(session);
      this.authState.notice.set(null);
      return { ok: true, role: session.role };
    }
    if (result.responseClass === 'AUTH') {
      return { ok: false, message: 'Identifiants invalides' };
    }
    return {
      ok: false,
      message: isServerUnreachable(result) ? 'Connexion impossible : serveur injoignable' : errorMessage(result),
    };
  }

  /** « Se déconnecter » (RG9) : identifiants effacés, la file de scans est conservée. */
  async logout(): Promise<void> {
    await this.authState.store.signOut();
    this.authState.session.set(null);
    await this.router.navigateByUrl('/');
  }

  /** 401 sur une requête protégée (RG10) : identifiants effacés, écran de connexion avec message. */
  async expire(): Promise<void> {
    await this.authState.store.signOut();
    this.authState.session.set(null);
    this.authState.notice.set(SESSION_EXPIRED);
    const returnUrl = this.router.url.startsWith('/connexion') ? null : this.router.url;
    await this.router.navigate(['/connexion'], { queryParams: returnUrl === null ? {} : { retour: returnUrl } });
  }

  private async restore(): Promise<void> {
    try {
      this.authState.session.set(await this.authState.store.restore());
    } catch (error: unknown) {
      console.error('Lecture des identifiants mémorisés impossible : connexion requise', error);
      this.authState.session.set(null);
    }
  }
}
