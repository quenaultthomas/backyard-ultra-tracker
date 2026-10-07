import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { Observable, catchError, concatMap, filter, map, of, take, tap, timeout } from 'rxjs';

import { CsrfService } from '../partage/csrf.service';
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

/** Fin de session volontaire, annoncée une fois par l'écran de connexion. */
export type FinSession = 'DECONNEXION' | 'SUPPRESSION_COMPTE';

const DELAI_MAX_RESTAURATION_MS = 5000;

/**
 * État connecté de l'application, gardé en mémoire uniquement : la seule source de vérité est
 * la session serveur, relue par `GET /api/comptes/moi` à chaque chargement.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly comptesApi = inject(ComptesApiService);
  private readonly csrf = inject(CsrfService);

  /** `undefined` : état inconnu ; `null` : anonyme. */
  private readonly compteCourant = signal<CompteReponse | null | undefined>(undefined);
  private readonly finSessionRecente = signal<FinSession | null>(null);

  readonly compte = this.compteCourant.asReadonly();
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

  /** Relit le Compte courant ; toute erreur (401, réseau, 5xx, délai) vaut état anonyme. */
  restaurer(): Observable<void> {
    return this.comptesApi.lireCompteCourant().pipe(
      timeout(DELAI_MAX_RESTAURATION_MS),
      catchError(() => of(null)),
      map((compte) => this.compteCourant.set(compte)),
    );
  }

  /** Émet une fois l'état connu : le Compte connecté, ou `null` si anonyme. */
  compteUneFoisConnu(): Observable<CompteReponse | null> {
    // Lecture synchrone du signal : `toObservable` émet via un effet, donc en retard d'une mise à jour.
    const compte = this.compteCourant();
    if (compte !== undefined) {
      return of(compte);
    }
    return this.compte$.pipe(
      filter((compte): compte is CompteReponse | null => compte !== undefined),
      take(1),
    );
  }

  /** Émet une fois l'état connu : `true` si connecté. */
  estConnecteUneFoisConnu(): Observable<boolean> {
    return this.compteUneFoisConnu().pipe(map((compte) => compte !== null));
  }

  /** La session serveur n'existe plus (401) : l'état local devient anonyme. */
  oublierSessionExpiree(): void {
    this.compteCourant.set(null);
  }

  /** Le jeton CSRF n'est pas garanti après connexion ou déconnexion : on en redemande un. */
  connecter(requete: ConnexionRequete): Observable<CompteReponse> {
    return this.comptesApi.connecter(requete).pipe(
      concatMap((compte) => this.csrf.renouvelerJeton().pipe(map(() => compte))),
      tap((compte) => this.compteCourant.set(compte)),
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
          this.compteCourant.set(null);
          this.finSessionRecente.set(fin);
        }),
      );
  }
}
