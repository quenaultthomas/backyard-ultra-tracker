import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { finalize, switchMap } from 'rxjs';

import {
  ErreursSpecifiques,
  interpreterErreurFormulaire,
} from '../../../../comptes/erreurs-compte';
import { RefusAccesService } from '../../../../comptes/refus-acces.service';
import { CsrfService } from '../../../../partage/csrf.service';
import { MESSAGE_SERVICE_INDISPONIBLE, lireProbleme } from '../../../../partage/probleme';
import { AdministrationApiService } from '../../../administration-api.service';
import { FicheCourseReponse } from '../../course';
import {
  ECRAN_GESTION_COURSES,
  ETAT_MESSAGE_ERREUR,
  MESSAGE_COURSE_INTROUVABLE,
} from '../../erreurs-course';

const CODE_COURSE_NON_DEMARRABLE = 'COURSE_NON_DEMARRABLE';

/** Refus du serveur qui ferment la confirmation (la fiche n'est relue que pour le premier). */
const ERREURS_DEMARRAGE: ErreursSpecifiques<never> = {
  [CODE_COURSE_NON_DEMARRABLE]: {
    generale: "La course n'est plus en préparation : elle ne peut plus être démarrée.",
  },
  COURSE_HORS_DATE: { generale: 'La course ne peut être démarrée que le jour de sa date.' },
  COURSE_SANS_INSCRIT: {
    generale: "La course ne peut pas être démarrée : aucun coureur n'est inscrit.",
  },
};

/**
 * Section « Démarrage » de la fiche d'une Course : bouton, confirmation en ligne, résultat.
 * Le serveur seul décide si la Course est démarrable (jour J, inscrits) : le bouton est toujours
 * actif pour une Course `EN_PREPARATION` et les refus sont affichés tels quels.
 */
@Component({
  selector: 'app-demarrage-course',
  templateUrl: './demarrage-course.html',
  styleUrls: ['../../../../comptes/formulaire-compte.css', './demarrage-course.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DemarrageCourse {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly boutonDemarrer = viewChild<ElementRef<HTMLButtonElement>>('boutonDemarrer');
  private readonly boutonAnnuler = viewChild<ElementRef<HTMLButtonElement>>('boutonAnnuler');

  readonly fiche = input.required<FicheCourseReponse>();
  /** La Course a été démarrée : fiche renvoyée par l'API. */
  readonly demarree = output<FicheCourseReponse>();
  /** La fiche est à relire (statut changé côté serveur). */
  readonly relire = output<void>();

  protected readonly confirmationOuverte = signal(false);
  protected readonly envoiEnCours = signal(false);
  protected readonly messageSucces = signal<string | null>(null);
  protected readonly erreur = signal<string | null>(null);
  /** Le statut renvoyé par l'API décide de l'affichage du bouton (l'API reste la référence). */
  protected readonly demarrable = computed(() => this.fiche().statut === 'EN_PREPARATION');
  /** Hors préparation, la section ne reste affichée que pour le résultat d'un démarrage. */
  protected readonly visible = computed(
    () => this.demarrable() || this.messageSucces() !== null || this.erreur() !== null,
  );

  constructor() {
    // Navigation au clavier : le focus va sur « Annuler » à l'ouverture de la confirmation.
    effect(() => this.boutonAnnuler()?.nativeElement.focus());
  }

  protected ouvrirConfirmation(): void {
    this.erreur.set(null);
    this.confirmationOuverte.set(true);
  }

  protected annuler(): void {
    this.confirmationOuverte.set(false);
    this.boutonDemarrer()?.nativeElement.focus();
  }

  /** Nouveau jeton CSRF puis `POST` ; boutons désactivés jusqu'à la réponse. */
  protected confirmer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => this.administrationApi.demarrerCourse(this.fiche().id)),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (fiche) => {
          this.confirmationOuverte.set(false);
          this.messageSucces.set(`La course ${fiche.nom} a été démarrée.`);
          this.demarree.emit(fiche);
        },
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  private traiterErreur(erreur: unknown): void {
    const ecran = `${ECRAN_GESTION_COURSES}/${this.fiche().id}`;
    if (this.refusAcces.rediriger(erreur, ecran)) {
      return;
    }
    // 404 : la Course n'existe plus (comportement de l'enregistrement des bénévoles).
    if (erreur instanceof HttpErrorResponse && erreur.status === HttpStatusCode.NotFound) {
      void this.router.navigateByUrl(ECRAN_GESTION_COURSES, {
        state: { [ETAT_MESSAGE_ERREUR]: MESSAGE_COURSE_INTROUVABLE },
      });
      return;
    }
    const code = lireProbleme(erreur)?.code;
    const { generale, jetonExpire } = interpreterErreurFormulaire(erreur, ERREURS_DEMARRAGE, []);
    this.erreur.set(generale ?? MESSAGE_SERVICE_INDISPONIBLE);
    if (code !== undefined && Object.hasOwn(ERREURS_DEMARRAGE, code)) {
      this.fermerConfirmationApresRefus();
    }
    if (code === CODE_COURSE_NON_DEMARRABLE) {
      this.relire.emit();
    }
    if (jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }

  /** Confirmation fermée par un refus : le focus revient sur « Démarrer la course », réactivé. */
  private fermerConfirmationApresRefus(): void {
    this.confirmationOuverte.set(false);
    afterNextRender(() => this.boutonDemarrer()?.nativeElement.focus(), {
      injector: this.injector,
    });
  }
}
