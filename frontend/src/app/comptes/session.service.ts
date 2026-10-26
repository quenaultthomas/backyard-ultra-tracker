import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { Observable, catchError, concatMap, filter, map, of, take, tap, timeout } from 'rxjs';

import { CsrfService } from '../partage/csrf.service';
import { CLE_DERNIER_BENEVOLE, MemoireLocaleService } from '../partage/memoire-locale.service';
import { estSansReponseDuServeur } from '../partage/reseau.service';
import {
  ChangerMotDePasseRequete,
  CompteReponse,
  ConnexionRequete,
  SupprimerCompteRequete,
} from './compte';
import { ComptesApiService } from './comptes-api.service';
import { estAdminMaster, estAdministrateur, estBenevole, estCoureur } from './roles';

/** `INCONNU` tant que `GET /api/comptes/moi` n'a pas répondu. */
export type EtatSession = 'INCONNU' | 'ANONYME' | 'CONNECTE';

/** Compte connecté tel que l'application l'utilise (sans `creeLe`, absent d'un Compte mémorisé). */
export type CompteConnecte = Pick<CompteReponse, 'id' | 'pseudo' | 'role'>;

/** Fin de session (volontaire ou refusée par l'API), annoncée une fois par l'écran de connexion. */
export type FinSession = 'DECONNEXION' | 'SUPPRESSION_COMPTE' | 'SESSION_EXPIREE';

const DELAI_MAX_RESTAURATION_MS = 5000;

/**
 * État connecté de l'application : la seule source de vérité est la session serveur, relue par
 * `GET /api/comptes/moi` à chaque chargement. Seul un Compte bénévole est mémorisé sur l'appareil,
 * pour rouvrir l'espace bénévole quand le serveur ne répond pas (`horsLigne`).
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly comptesApi = inject(ComptesApiService);
  private readonly csrf = inject(CsrfService);
  private readonly memoire = inject(MemoireLocaleService);

  /** `undefined` : état inconnu ; `null` : anonyme. */
  private readonly compteCourant = signal<CompteConnecte | null | undefined>(undefined);
  private readonly compteMemorise = signal(false);
  private readonly finSessionRecente = signal<FinSession | null>(null);

  readonly compte = this.compteCourant.asReadonly();
  /** Vrai quand le Compte vient de l'appareil, le serveur n'ayant pas répondu : à revalider. */
  readonly horsLigne = this.compteMemorise.asReadonly();
  readonly etat = computed<EtatSession>(() => {
    const compte = this.compteCourant();
    if (compte === undefined) {
      return 'INCONNU';
    }
    return compte === null ? 'ANONYME' : 'CONNECTE';
  });
  readonly estAdministrateur = computed(() => {
    const compte = this.compteCourant();
    return compte != null && estAdministrateur(compte.role);
  });
  /** Aide d'affichage : les actions réservées à l'admin master restent contrôlées par l'API. */
  readonly estAdminMaster = computed(() => {
    const compte = this.compteCourant();
    return compte != null && estAdminMaster(compte.role);
  });
  readonly estBenevole = computed(() => {
    const compte = this.compteCourant();
    return compte != null && estBenevole(compte.role);
  });
  readonly estCoureur = computed(() => {
    const compte = this.compteCourant();
    return compte != null && estCoureur(compte.role);
  });
  private readonly compte$ = toObservable(this.compteCourant);

  /**
   * Relit le Compte courant. Sans réponse du serveur, le bénévole mémorisé reste connecté hors
   * ligne ; toute autre erreur vaut état anonyme (un 401 efface la mémoire).
   */
  restaurer(): Observable<void> {
    return this.comptesApi.lireCompteCourant().pipe(
      timeout(DELAI_MAX_RESTAURATION_MS),
      map((compte) => this.etablir(compte)),
      catchError((erreur: unknown) => of(this.constaterEchec(erreur))),
    );
  }

  /** Émet une fois l'état connu : le Compte connecté, ou `null` si anonyme. */
  compteUneFoisConnu(): Observable<CompteConnecte | null> {
    // Lecture synchrone du signal : `toObservable` émet via un effet, donc en retard d'une mise à jour.
    const compte = this.compteCourant();
    if (compte !== undefined) {
      return of(compte);
    }
    return this.compte$.pipe(
      filter((compte): compte is CompteConnecte | null => compte !== undefined),
      take(1),
    );
  }

  /** Émet une fois l'état connu : `true` si connecté. */
  estConnecteUneFoisConnu(): Observable<boolean> {
    return this.compteUneFoisConnu().pipe(map((compte) => compte !== null));
  }

  /**
   * La session serveur n'existe plus (401) : l'état local devient anonyme. La fin de session n'est
   * annoncée que si l'utilisateur était connecté (pas pour un visiteur).
   */
  oublierSessionExpiree(): void {
    if (this.etat() === 'CONNECTE') {
      this.finSessionRecente.set('SESSION_EXPIREE');
    }
    this.oublier();
  }

  /** Le jeton CSRF n'est pas garanti après connexion ou déconnexion : on en redemande un. */
  connecter(requete: ConnexionRequete): Observable<CompteReponse> {
    return this.comptesApi.connecter(requete).pipe(
      concatMap((compte) => this.csrf.renouvelerJeton().pipe(map(() => compte))),
      tap((compte) => this.etablir(compte)),
    );
  }

  /**
   * L'identifiant de session est renouvelé par l'API : on redemande un jeton CSRF. Le Compte
   * reste connecté, l'état local est inchangé.
   */
  changerMotDePasse(requete: ChangerMotDePasseRequete): Observable<void> {
    return this.comptesApi
      .changerMotDePasse(requete)
      .pipe(concatMap(() => this.csrf.renouvelerJeton()));
  }

  deconnecter(): Observable<void> {
    return this.comptesApi.deconnecter().pipe(this.terminerSession('DECONNEXION'));
  }

  /** Le Compte est anonymisé et toutes ses sessions sont fermées par l'API (204). */
  supprimerCompte(requete: SupprimerCompteRequete): Observable<void> {
    return this.comptesApi
      .supprimerCompte(requete)
      .pipe(this.terminerSession('SUPPRESSION_COMPTE'));
  }

  /** Indique, une seule fois, la fin de session qui vient d'avoir lieu (message de l'écran de connexion). */
  consommerFinSessionRecente(): FinSession | null {
    const fin = this.finSessionRecente();
    this.finSessionRecente.set(null);
    return fin;
  }

  /** La session serveur est fermée : nouveau jeton CSRF, puis état local anonyme. */
  private terminerSession(fin: FinSession): (source: Observable<void>) => Observable<void> {
    return (source) =>
      source.pipe(
        concatMap(() => this.csrf.renouvelerJeton()),
        tap(() => {
          this.oublier();
          this.finSessionRecente.set(fin);
        }),
      );
  }

  /** Compte confirmé par le serveur : un bénévole est mémorisé, tout autre rôle efface la mémoire. */
  private etablir(compte: CompteConnecte): void {
    if (estBenevole(compte.role)) {
      const memorise = this.lireBenevoleMemorise();
      if (memorise?.id !== compte.id) {
        this.memoire.oublier();
      }
      this.memoire.ecrire(CLE_DERNIER_BENEVOLE, {
        id: compte.id,
        pseudo: compte.pseudo,
        role: compte.role,
      });
    } else {
      this.memoire.oublier();
    }
    this.compteMemorise.set(false);
    this.compteCourant.set(compte);
  }

  private constaterEchec(erreur: unknown): void {
    if (erreur instanceof HttpErrorResponse && erreur.status === HttpStatusCode.Unauthorized) {
      this.oublier();
      return;
    }
    const memorise = estSansReponseDuServeur(erreur) ? this.lireBenevoleMemorise() : null;
    this.compteMemorise.set(memorise !== null);
    this.compteCourant.set(memorise);
  }

  private oublier(): void {
    this.memoire.oublier();
    this.compteMemorise.set(false);
    this.compteCourant.set(null);
  }

  private lireBenevoleMemorise(): CompteConnecte | null {
    const valeur = this.memoire.lire(CLE_DERNIER_BENEVOLE) as Partial<CompteConnecte> | null;
    return typeof valeur?.id === 'string' &&
      typeof valeur.pseudo === 'string' &&
      valeur.role === 'BENEVOLE'
      ? { id: valeur.id, pseudo: valeur.pseudo, role: valeur.role }
      : null;
  }
}
