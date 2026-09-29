import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { API_PATHS } from '../core/api-paths';
import { AccountRegistrationsResponse, PasswordChangeRequest } from '../core/api.types';
import { basicAuthorization } from '../core/credentials';
import { ADMIN_TIMEOUT_MS, ClassifiedResult } from '../core/http-classification';
import { runnerLoginFailureMessage } from '../core/outcomes';
import { ApiClient } from './api-client';
import { RunnerAuthState } from './runner-auth-state';

export type RunnerLoginResult = { readonly ok: true } | { readonly ok: false; readonly message: string };

export const RUNNER_SESSION_EXPIRED = 'Session expirée ou identifiants modifiés : reconnectez-vous';
export const RUNNER_LOGIN_ROUTE = '/compte/connexion';

/**
 * Connexion, déconnexion, changement de mot de passe et expiration du compte coureur (RG19, RG21 inc. 5). La
 * connexion valide les identifiants par E21 `GET /api/account/me`. Ne touche jamais aux identifiants ADMIN/SCANNER.
 */
@Injectable({ providedIn: 'root' })
export class RunnerSessionService {
  private readonly api = inject(ApiClient);
  private readonly runnerAuth = inject(RunnerAuthState);
  private readonly router = inject(Router);

  /** Reprise des identifiants coureur mémorisés, terminée avant toute décision d'accès. */
  readonly ready: Promise<void> = this.restore();

  constructor() {
    this.runnerAuth.onUnauthorized(() => {
      void this.expire();
    });
  }

  /** RG21 : E21 avec les identifiants saisis ; rien n'est conservé en cas d'échec. */
  async login(pseudo: string, password: string, rememberFor24h: boolean): Promise<RunnerLoginResult> {
    const authorization = basicAuthorization(pseudo, password);
    const result = await this.api.request<AccountRegistrationsResponse>('GET', API_PATHS.accountMe, {
      timeoutMs: ADMIN_TIMEOUT_MS, authorization,
    });
    const failure = runnerLoginFailureMessage(result);
    if (failure !== null || result.body === null) {
      return { ok: false, message: failure ?? 'Connexion impossible' };
    }
    const session = await this.runnerAuth.store.signIn(result.body.pseudo, authorization, rememberFor24h);
    this.runnerAuth.session.set(session);
    this.runnerAuth.notice.set(null);
    return { ok: true };
  }

  /**
   * RG19 : une seule requête E23 avec les identifiants actuels ; sur 204, les identifiants conservés sont remplacés
   * par les nouveaux, avec la même option de conservation.
   */
  async changePassword(newPassword: string): Promise<ClassifiedResult<void>> {
    const pseudo = this.runnerAuth.pseudo();
    const body: PasswordChangeRequest = { newPassword };
    const result = await this.api.request<void>('PUT', API_PATHS.accountPassword, {
      body, timeoutMs: ADMIN_TIMEOUT_MS,
    });
    if (result.responseClass === 'SUCCESS' && pseudo !== null) {
      this.runnerAuth.session.set(await this.runnerAuth.store.replaceAuthorization(
        basicAuthorization(pseudo, newPassword)));
    }
    return result;
  }

  /** « Se déconnecter » sur /compte : identifiants coureur seulement. */
  async logout(): Promise<void> {
    await this.runnerAuth.store.signOut();
    this.runnerAuth.session.set(null);
    await this.router.navigateByUrl('/');
  }

  /** 401 sur `/api/account/**` : identifiants coureur effacés, écran de connexion coureur avec message. */
  async expire(): Promise<void> {
    await this.runnerAuth.store.signOut();
    this.runnerAuth.session.set(null);
    this.runnerAuth.notice.set(RUNNER_SESSION_EXPIRED);
    const returnUrl = this.router.url.startsWith(RUNNER_LOGIN_ROUTE) ? null : this.router.url;
    await this.router.navigate([RUNNER_LOGIN_ROUTE], {
      queryParams: returnUrl === null ? {} : { retour: returnUrl },
    });
  }

  private async restore(): Promise<void> {
    try {
      this.runnerAuth.session.set(await this.runnerAuth.store.restore());
    } catch (error: unknown) {
      console.error('Lecture des identifiants coureur mémorisés impossible : connexion requise', error);
      this.runnerAuth.session.set(null);
    }
  }
}
