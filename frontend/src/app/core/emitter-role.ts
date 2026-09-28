import { Suspension } from './scan-queue';

/**
 * Rôle d'émetteur de la file de scans d'un contexte (onglet ou fenêtre) de la PWA (RG21, « émetteur effectif ») :
 * - un seul contexte émetteur par appareil, grâce à un verrou partagé entre contextes ;
 * - seul un contexte capable d'envoyer (identifiants autorisés au scan, file non suspendue) demande ou garde le
 *   verrou : un contexte qui ne peut pas envoyer ne bloque jamais l'envoi d'un autre ;
 * - le verrou n'est rendu qu'une fois l'envoi en cours terminé : jamais deux requêtes de scan simultanées ni envoi
 *   en double (CL12).
 * Aucune dépendance au navigateur : le verrou et la file sont injectés.
 */

/** Verrou exclusif partagé par tous les contextes de l'appareil (Web Locks en production). */
export interface EmitterLock {
  /**
   * Demande le verrou. À l'obtention, `whileHeld` est appelé et le verrou reste détenu jusqu'à la résolution de la
   * promesse qu'il retourne. `signal` annule une demande encore en attente : la promesse retournée est alors
   * rejetée.
   */
  request(whileHeld: () => Promise<void>, signal: AbortSignal): Promise<void>;
}

/** Partie de la file utilisée par le rôle d'émetteur. */
export interface EmittingQueue {
  setEmitter(emitter: boolean): void;
  /** Résolue quand plus aucun envoi n'est en cours. */
  whenIdle(): Promise<void>;
}

export interface EmitterRoleDependencies {
  readonly lock: EmitterLock;
  readonly queue: EmittingQueue;
  /** Vrai si ce contexte peut envoyer maintenant (voir `canEmit`). */
  readonly canEmit: () => boolean;
  /** Erreur inattendue du verrou, jamais ignorée. */
  readonly onFailure: (error: unknown) => void;
}

/**
 * Un contexte peut être émetteur s'il dispose d'identifiants autorisés au scan (SCANNER ou ADMIN) et si sa file
 * n'est pas suspendue par un 401 ou un 403 (RG10, RG21).
 */
export function canEmit(hasScanCredentials: boolean, suspension: Suspension | null): boolean {
  return hasScanCredentials && suspension === null;
}

type RoleState = 'released' | 'requesting' | 'held' | 'releasing';

export class EmitterRole {
  private state: RoleState = 'released';
  private pendingRequest: AbortController | null = null;
  private endHolding: (() => void) | null = null;

  constructor(private readonly deps: EmitterRoleDependencies) {}

  /** À appeler à chaque changement de la capacité d'envoi : demande, annule ou rend le verrou. */
  update(): void {
    const eligible = this.deps.canEmit();
    if (eligible && this.state === 'released') {
      this.claim();
    } else if (!eligible && this.state === 'requesting') {
      this.pendingRequest?.abort();
    } else if (!eligible && this.state === 'held') {
      this.relinquish();
    }
  }

  private claim(): void {
    const request = new AbortController();
    this.pendingRequest = request;
    this.state = 'requesting';
    this.deps.lock.request(() => this.hold(), request.signal)
      .then(() => this.onReleased(), (error: unknown) => this.onRequestFailed(request, error));
  }

  /** Verrou obtenu : le contexte devient émetteur, sauf s'il ne peut plus envoyer entre-temps. */
  private hold(): Promise<void> {
    this.pendingRequest = null;
    if (!this.deps.canEmit()) {
      return Promise.resolve();
    }
    this.state = 'held';
    this.deps.queue.setEmitter(true);
    return new Promise<void>((resolve) => {
      this.endHolding = resolve;
    });
  }

  /** Le contexte cesse d'émettre, puis rend le verrou une fois l'envoi en cours terminé. */
  private relinquish(): void {
    this.state = 'releasing';
    this.deps.queue.setEmitter(false);
    this.deps.queue.whenIdle().then(() => {
      this.endHolding?.();
      this.endHolding = null;
    }, (error: unknown) => this.deps.onFailure(error));
  }

  private onReleased(): void {
    this.state = 'released';
    this.update();
  }

  /**
   * Une demande annulée par ce contexte est attendue : la capacité d'envoi est réévaluée. Toute autre erreur est
   * signalée, sans nouvelle demande automatique (pas de boucle d'échecs).
   */
  private onRequestFailed(request: AbortController, error: unknown): void {
    this.pendingRequest = null;
    this.state = 'released';
    if (!request.signal.aborted) {
      this.deps.onFailure(error);
      return;
    }
    this.update();
  }
}
