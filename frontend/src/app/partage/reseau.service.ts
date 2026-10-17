import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';
import { TimeoutError } from 'rxjs';

/** Réponses du relais quand l'API est injoignable : équivalentes à une absence de réponse. */
const STATUTS_SANS_REPONSE: readonly number[] = [
  0,
  HttpStatusCode.BadGateway,
  HttpStatusCode.ServiceUnavailable,
  HttpStatusCode.GatewayTimeout,
];

/** Réseau coupé, délai dépassé ou API injoignable (502, 503, 504) : le serveur n'a pas répondu. */
export function estSansReponseDuServeur(erreur: unknown): boolean {
  return (
    erreur instanceof TimeoutError ||
    (erreur instanceof HttpErrorResponse && STATUTS_SANS_REPONSE.includes(erreur.status))
  );
}

/** État du réseau tel que le navigateur le déclare (`navigator.onLine`, événements `online` / `offline`). */
@Injectable({ providedIn: 'root' })
export class ReseauService {
  private readonly enLigne = signal(navigator.onLine);

  readonly horsLigne = computed(() => !this.enLigne());

  constructor() {
    const fenetre = inject(DOCUMENT).defaultView;
    fenetre?.addEventListener('online', () => this.enLigne.set(true));
    fenetre?.addEventListener('offline', () => this.enLigne.set(false));
  }
}
