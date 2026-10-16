import {
  DestroyRef,
  Directive,
  ElementRef,
  Injector,
  Signal,
  afterNextRender,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, finalize, switchMap } from 'rxjs';

import { interpreterErreurFormulaire } from '../../../comptes/erreurs-compte';
import { CsrfService } from '../../../partage/csrf.service';
import { lireProbleme } from '../../../partage/probleme';
import { RafraichissementPilotage } from './rafraichissement-pilotage';
import { RedirectionActionPilotage, RefusActionPilotage } from './refus-action-pilotage';

/**
 * Socle des actions confirmées du pilotage (abandon manuel, réintégration) : un seul panneau en
 * ligne, hors du tableau (il survit aux rafraîchissements), focus sur son titre (`#titre`) à
 * l'ouverture, Échap ou « Annuler » rendent le focus au bouton de la ligne, un seul envoi à la
 * fois, puis message de résultat. Le serveur décide de tout ; l'écran affiche sa réponse.
 * L'hôte du composant porte `tabindex="-1"` : il reçoit le focus quand le panneau se ferme sur
 * un résultat (la ligne du coureur disparaît).
 */
@Directive()
export abstract class ConfirmationPilotage<C> {
  protected readonly rafraichissement = inject(RafraichissementPilotage);
  protected readonly csrf = inject(CsrfService);
  protected readonly destroyRef = inject(DestroyRef);
  private readonly redirection = inject(RedirectionActionPilotage);
  private readonly injector = inject(Injector);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly titre = viewChild<ElementRef<HTMLElement>>('titre');

  readonly courseId = input.required<string>();

  /** Inscription visée par le panneau ouvert, `null` panneau fermé. */
  readonly cibleId = signal<string | null>(null);
  readonly envoiEnCours = signal(false);

  protected readonly succes = signal<string | null>(null);
  protected readonly erreur = signal<string | null>(null);

  private boutonOrigine: HTMLElement | null = null;

  /** Refus du serveur qui ferment le panneau, par `code` du `ProblemDetail`. */
  protected abstract readonly refus: Readonly<Record<string, RefusActionPilotage>>;

  /** Coureur visé, lu dans le dernier instantané ; `undefined` s'il n'y figure plus. */
  protected abstract readonly cible: Signal<C | undefined>;

  /** Ferme le panneau avec `message` quand le coureur visé disparaît d'un nouvel instantané. */
  protected fermerSiCibleAbsente(message: string): void {
    effect(() => {
      if (this.cibleId() !== null && this.cible() === undefined && !this.envoiEnCours()) {
        this.fermerSurResultat({ erreur: message });
      }
    });
  }

  /** Ouvre le panneau pour `inscriptionId`, sans appel réseau ; le focus va sur son titre. */
  ouvrir(inscriptionId: string, bouton: HTMLElement): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.boutonOrigine = bouton;
    this.reinitialiser();
    this.succes.set(null);
    this.erreur.set(null);
    this.cibleId.set(inscriptionId);
    afterNextRender(() => this.titre()?.nativeElement.focus(), { injector: this.injector });
  }

  /** Ferme le panneau sans appel ni message (ouverture d'une autre confirmation du pilotage). */
  fermer(): void {
    if (!this.envoiEnCours()) {
      this.cibleId.set(null);
    }
  }

  /** « Annuler » ou Échap : fermé sans appel, focus rendu au bouton de la ligne (ou au repli). */
  protected annuler(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.cibleId.set(null);
    const bouton = this.boutonOrigine;
    (bouton?.isConnected ? bouton : this.repliFocus())?.focus();
  }

  /** Remise à zéro propre au panneau, à chaque ouverture. */
  protected reinitialiser(): void {
    // Aucun état propre par défaut.
  }

  /** Cible du focus à l'annulation si le bouton de la ligne a disparu. */
  protected repliFocus(): HTMLElement | null {
    return null;
  }

  /** Nouveau jeton CSRF puis `requete` ; un seul envoi à la fois. */
  protected envoyer<R>(requete: () => Observable<R>, surSucces: (reponse: R) => void): void {
    this.envoiEnCours.set(true);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(requete),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({ next: surSucces, error: (erreur: unknown) => this.traiterErreur(erreur) });
  }

  /** Succès : panneau fermé avec `message`, instantané redemandé aussitôt. */
  protected reussir(message: string): void {
    this.fermerSurResultat({ succes: message });
    this.rafraichissement.actualiserMaintenant();
  }

  /** Erreur hors redirection et hors refus connu : message général, panneau conservé. */
  protected afficherErreur(erreur: unknown): void {
    const { generale, jetonExpire } = interpreterErreurFormulaire(erreur, {}, []);
    this.erreur.set(generale ?? null);
    this.renouvelerJetonSiExpire(jetonExpire);
  }

  protected renouvelerJetonSiExpire(jetonExpire: boolean | undefined): void {
    if (jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }

  /** Redirections (401, 403, Course introuvable), refus qui ferment, sinon panneau conservé. */
  private traiterErreur(erreur: unknown): void {
    if (this.redirection.rediriger(erreur, this.courseId())) {
      return;
    }
    const code = lireProbleme(erreur)?.code;
    if (code !== undefined && Object.hasOwn(this.refus, code)) {
      const { message, rafraichir } = this.refus[code];
      this.fermerSurResultat({ erreur: message });
      if (rafraichir) {
        this.rafraichissement.actualiserMaintenant();
      }
      return;
    }
    this.afficherErreur(erreur);
  }

  /** Panneau fermé avec un message ; le focus, s'il était dans le panneau, va sur ce message. */
  private fermerSurResultat(resultat: { succes?: string; erreur?: string }): void {
    const focusDansPanneau = this.hote.nativeElement.contains(document.activeElement);
    this.cibleId.set(null);
    this.succes.set(resultat.succes ?? null);
    this.erreur.set(resultat.erreur ?? null);
    if (focusDansPanneau) {
      afterNextRender(() => this.hote.nativeElement.focus(), { injector: this.injector });
    }
  }
}
