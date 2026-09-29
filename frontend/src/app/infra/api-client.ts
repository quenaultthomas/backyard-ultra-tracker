import { inject, Injectable } from '@angular/core';
import {
  authorizationFor,
  credentialEvent,
  CredentialEvent,
  credentialScope,
  CredentialSources,
} from '../core/credential-routing';
import { classify, ClassifiedResult, RawHttpResult } from '../core/http-classification';
import { AuthState } from './auth-state';
import { RunnerAuthState } from './runner-auth-state';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE';

export interface ApiResult<T> extends ClassifiedResult<T> {
  /** Instants de l'appareil à l'envoi et à la réception, pour la mesure du décalage d'horloge (RG4). */
  readonly sentAt: number;
  readonly receivedAt: number;
}

export interface RequestOptions {
  readonly body?: unknown;
  readonly timeoutMs: number;
  /** Identifiants à utiliser à la place de ceux de la session (connexion, E19). */
  readonly authorization?: string;
}

/**
 * Accès HTTP à l'API de la même origine. L'en-tête Authorization n'est ajouté qu'aux chemins protégés, avec les
 * identifiants de leur emplacement : ADMIN/SCANNER vers `/api/scan|admin/**` (RG8 inc. 4), coureur vers
 * `/api/account/**` (RG21 inc. 5). Toute réponse est classée (RG3) ; aucune exception n'est levée pour une erreur
 * HTTP ou réseau. Un 401 sur une requête protégée faite avec les identifiants d'un emplacement est signalé à l'état
 * de cet emplacement seulement ({@link AuthState} ou {@link RunnerAuthState}).
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly authState = inject(AuthState);
  private readonly runnerAuthState = inject(RunnerAuthState);
  private readonly sources: CredentialSources = {
    staffAuthorization: () => this.authState.authorization(),
    runnerAuthorization: () => this.runnerAuthState.authorization(),
  };

  async request<T>(method: HttpMethod, path: string, options: RequestOptions): Promise<ApiResult<T>> {
    const body = options.body === undefined ? undefined : JSON.stringify(options.body);
    return this.requestRaw<T>(method, path, body, options);
  }

  async requestRaw<T>(method: HttpMethod, path: string, body: string | undefined, options: RequestOptions):
    Promise<ApiResult<T>> {
    const exchange = await this.exchange(method, path, body, options);
    return { ...classify<T>(exchange.raw), sentAt: exchange.sentAt, receivedAt: exchange.receivedAt };
  }

  /**
   * Échange HTTP brut, avant classification : le corps déjà sérialisé est transmis octet pour octet
   * (idempotence du scan, RG23).
   */
  async exchange(method: HttpMethod, path: string, body: string | undefined, options: RequestOptions):
    Promise<{ readonly raw: RawHttpResult; readonly sentAt: number; readonly receivedAt: number }> {
    const scope = credentialScope(path);
    const authorization = scope === null ? null : options.authorization ?? await authorizationFor(path, this.sources);
    const sentAt = Date.now();
    const raw = await fetchRaw(method, path, body, authorization, options.timeoutMs);
    const receivedAt = Date.now();
    if (options.authorization === undefined) {
      await this.applyCredentialEvent(credentialEvent(path, classify(raw).responseClass));
    }
    return { raw, sentAt, receivedAt };
  }

  /** Un 401 n'efface que l'emplacement concerné ; un 2xx coureur prolonge l'expiration glissante (RG21 inc. 5). */
  private async applyCredentialEvent(event: CredentialEvent | null): Promise<void> {
    switch (event) {
      case 'STAFF_UNAUTHORIZED':
        this.authState.reportUnauthorized();
        return;
      case 'RUNNER_UNAUTHORIZED':
        this.runnerAuthState.reportUnauthorized();
        return;
      case 'RUNNER_ACTIVITY':
        await this.runnerAuthState.recordActivity();
        return;
      default:
        return;
    }
  }
}

async function fetchRaw(method: HttpMethod, path: string, body: string | undefined, authorization: string | null,
                        timeoutMs: number): Promise<RawHttpResult> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (authorization !== null) {
    headers['Authorization'] = authorization;
  }
  try {
    const response = await fetch(path, {
      method, headers, body, signal: controller.signal, cache: 'no-store', credentials: 'omit', redirect: 'error',
    });
    return await readResponse(response);
  } catch (error: unknown) {
    return timedOut || isAbort(error) ? { kind: 'timeout' } : { kind: 'network-error' };
  } finally {
    window.clearTimeout(timer);
  }
}

async function readResponse(response: Response): Promise<RawHttpResult> {
  const contentType = response.headers.get('Content-Type') ?? '';
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return { kind: 'response', status: response.status, json: true, body: null };
  }
  if (!contentType.includes('json')) {
    await response.text();
    return { kind: 'response', status: response.status, json: false, body: null };
  }
  try {
    return { kind: 'response', status: response.status, json: true, body: await response.json() as unknown };
  } catch {
    return { kind: 'response', status: response.status, json: false, body: null };
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
