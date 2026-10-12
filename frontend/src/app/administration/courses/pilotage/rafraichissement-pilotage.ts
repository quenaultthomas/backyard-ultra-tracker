import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';

import { AdministrationApiService } from '../../administration-api.service';
import { PilotageCourse } from '../pilotage-course';

const PERIODE_ACTUALISATION_MS = 3000;
const DELAI_NOUVELLE_DEMANDE_MS = 1000;
const PERIODE_AFFICHAGE_MS = 200;

/** Refus qui arrêtent l'actualisation (session, rôle, Course introuvable ou non démarrée). */
const STATUTS_DEFINITIFS: readonly number[] = [
  HttpStatusCode.Unauthorized,
  HttpStatusCode.Forbidden,
  HttpStatusCode.NotFound,
  HttpStatusCode.Conflict,
];

/**
 * Actualisation de l'écran de pilotage : un instantané serveur toutes les 3 s (un seul appel à la
 * fois) et, entre deux instantanés, avancement local des durées renvoyées par le serveur avec
 * l'horloge monotone `performance.now()`. L'heure du navigateur n'est jamais comparée à celle du
 * serveur et la Boucle courante n'est jamais recalculée ici : seul le serveur la décide.
 * Fourni par l'écran : tout s'arrête à sa destruction.
 */
@Injectable()
export class RafraichissementPilotage {
  private readonly administrationApi = inject(AdministrationApiService);

  /** Dernier instantané appliqué, `null` avant le premier succès. */
  readonly instantane = signal<PilotageCourse | null>(null);
  /** La dernière demande a échoué (réseau, 5xx) ; les tentatives continuent. */
  readonly echec = signal(false);
  /** « Boucle N en cours », posé seulement quand la Boucle change entre deux instantanés. */
  readonly annonce = signal('');

  private readonly receptionA = signal(0);
  private readonly maintenant = signal(0);
  private readonly ecart = computed(() => Math.max(0, this.maintenant() - this.receptionA()));

  /** Temps avant la fin de la Boucle, avancé localement ; `null` sans chronométrage. */
  readonly tempsAvantFinBoucle = computed(() => {
    const reste = this.instantane()?.tempsAvantFinBoucleMillisecondes ?? null;
    return reste === null ? null : Math.max(0, reste - this.ecart());
  });

  /** Durée écoulée depuis le départ, avancée localement ; `null` sans chronométrage. */
  readonly dureeEcoulee = computed(() => {
    const ecoulee = this.instantane()?.dureeEcouleeMillisecondes ?? null;
    return ecoulee === null ? null : ecoulee + this.ecart();
  });

  private courseId = '';
  private surRefus: (erreur: HttpErrorResponse) => void = () => undefined;
  private requete: Subscription | null = null;
  private minuteur: ReturnType<typeof setTimeout> | undefined;
  private battement: ReturnType<typeof setInterval> | undefined;
  /** Un nouvel instantané a été demandé au zéro du compte à rebours, la bascule est attendue. */
  private basculeDemandee = false;
  private arrete = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.arreter());
  }

  /** Premier appel immédiat ; `surRefus` reçoit les 401, 403, 404 et 409 (actualisation arrêtée). */
  demarrer(courseId: string, surRefus: (erreur: HttpErrorResponse) => void): void {
    this.courseId = courseId;
    this.surRefus = surRefus;
    this.battement = setInterval(() => this.battre(), PERIODE_AFFICHAGE_MS);
    this.demander();
  }

  private arreter(): void {
    this.arrete = true;
    clearTimeout(this.minuteur);
    clearInterval(this.battement);
    this.requete?.unsubscribe();
    this.requete = null;
  }

  /** Avance l'affichage et demande la bascule dès que le reste local atteint zéro. */
  private battre(): void {
    this.maintenant.set(performance.now());
    const instantane = this.instantane();
    if (
      instantane !== null &&
      !instantane.boucleMaxTerminee &&
      this.tempsAvantFinBoucle() === 0 &&
      !this.basculeDemandee &&
      this.requete === null
    ) {
      this.basculeDemandee = true;
      this.demander();
    }
  }

  private planifier(delai: number): void {
    clearTimeout(this.minuteur);
    this.minuteur = setTimeout(() => this.demander(), delai);
  }

  private demander(): void {
    clearTimeout(this.minuteur);
    if (this.arrete || this.requete !== null) {
      return;
    }
    this.requete = this.administrationApi.consulterPilotage(this.courseId).subscribe({
      next: (instantane) => {
        this.requete = null;
        this.appliquer(instantane);
      },
      error: (erreur: unknown) => {
        this.requete = null;
        this.traiterErreur(erreur);
      },
    });
  }

  private appliquer(suivant: PilotageCourse): void {
    this.echec.set(false);
    const precedent = this.instantane();
    // Instantané plus ancien que celui déjà affiché (instants du serveur entre eux) : ignoré.
    if (
      precedent !== null &&
      Date.parse(suivant.heureServeur) < Date.parse(precedent.heureServeur)
    ) {
      this.planifier(this.basculeDemandee ? DELAI_NOUVELLE_DEMANDE_MS : PERIODE_ACTUALISATION_MS);
      return;
    }
    const ancienneBoucle = precedent?.boucleCourante ?? null;
    const nouvelleBoucle = suivant.boucleCourante;
    if (ancienneBoucle !== null && nouvelleBoucle !== null && ancienneBoucle !== nouvelleBoucle) {
      this.annonce.set(`Boucle ${nouvelleBoucle} en cours`);
    }
    const reception = performance.now();
    this.receptionA.set(reception);
    this.maintenant.set(reception);
    this.instantane.set(suivant);
    // Cas pathologique : la réponse au zéro porte encore la même Boucle, terminée.
    this.basculeDemandee =
      this.basculeDemandee &&
      ancienneBoucle === nouvelleBoucle &&
      suivant.tempsAvantFinBoucleMillisecondes === 0 &&
      !suivant.boucleMaxTerminee;
    this.planifier(this.basculeDemandee ? DELAI_NOUVELLE_DEMANDE_MS : PERIODE_ACTUALISATION_MS);
  }

  private traiterErreur(erreur: unknown): void {
    if (erreur instanceof HttpErrorResponse && STATUTS_DEFINITIFS.includes(erreur.status)) {
      this.arreter();
      this.surRefus(erreur);
      return;
    }
    this.echec.set(true);
    this.planifier(PERIODE_ACTUALISATION_MS);
  }
}
