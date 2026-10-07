import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, switchMap } from 'rxjs';

import { ErreursSpecifiques, interpreterErreurFormulaire } from '../../../comptes/erreurs-compte';
import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { CsrfService } from '../../../partage/csrf.service';
import { MESSAGE_SERVICE_INDISPONIBLE, lireProbleme } from '../../../partage/probleme';
import { AdministrationApiService } from '../../administration-api.service';
import { CourseReponse } from '../course';
import {
  CODE_COURSE_INTROUVABLE,
  ECRAN_GESTION_COURSES,
  MESSAGE_COURSE_INTROUVABLE,
} from '../erreurs-course';

const CODE_COURSE_NON_SUPPRIMABLE = 'COURSE_NON_SUPPRIMABLE';

const ERREURS_SUPPRESSION: ErreursSpecifiques<never> = {
  [CODE_COURSE_NON_SUPPRIMABLE]: {
    generale: "La course n'est plus en préparation : elle ne peut plus être supprimée.",
  },
};

/** Course qui n'existe plus côté serveur (supprimée par cette ligne ou déjà absente). */
export interface CourseRetiree {
  readonly id: string;
  readonly message: string;
  /** Faux quand la Course avait déjà disparu (404). */
  readonly succes: boolean;
}

interface ErreurSuppression {
  readonly message: string;
  /** Erreur liée à la confirmation : masquée quand celle-ci est fermée. */
  readonly avecConfirmation: boolean;
}

/** Suppression d'une Course depuis sa ligne : bouton, confirmation en ligne et erreurs. */
@Component({
  selector: 'app-suppression-course',
  templateUrl: './suppression-course.html',
  styleUrls: [
    '../action-ligne.css',
    '../../../partage/confirmation-en-ligne.css',
    './suppression-course.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SuppressionCourse {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly boutonSupprimer = viewChild<ElementRef<HTMLButtonElement>>('boutonSupprimer');
  private readonly boutonAnnuler = viewChild<ElementRef<HTMLButtonElement>>('boutonAnnuler');

  readonly course = input.required<CourseReponse>();
  /** Vrai quand la confirmation de cette ligne est la seule ouverte de la liste. */
  readonly ouverte = input(false);
  readonly ouvrir = output<void>();
  readonly fermer = output<void>();
  /** La Course n'existe plus : la ligne est à retirer et la liste à relire. */
  readonly retiree = output<CourseRetiree>();
  /** La liste est à relire (statut changé côté serveur). */
  readonly actualiser = output<void>();

  protected readonly envoiEnCours = signal(false);
  private readonly erreur = signal<ErreurSuppression | null>(null);
  /** Le statut renvoyé par l'API décide de l'affichage du bouton (l'API reste la référence). */
  protected readonly supprimable = computed(() => this.course().statut === 'EN_PREPARATION');
  protected readonly confirmationAffichee = computed(() => this.ouverte() && this.supprimable());
  protected readonly erreurAffichee = computed(() => {
    const erreur = this.erreur();
    return erreur !== null && (!erreur.avecConfirmation || this.confirmationAffichee())
      ? erreur.message
      : null;
  });

  constructor() {
    // Navigation au clavier : le focus va sur « Annuler » à l'ouverture de la confirmation.
    effect(() => this.boutonAnnuler()?.nativeElement.focus());
  }

  protected demanderConfirmation(): void {
    this.erreur.set(null);
    this.ouvrir.emit();
  }

  protected annuler(): void {
    this.erreur.set(null);
    this.fermer.emit();
    this.boutonSupprimer()?.nativeElement.focus();
  }

  /** Nouveau jeton CSRF puis `DELETE` ; boutons désactivés jusqu'à la réponse. */
  protected confirmer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    const { id, nom } = this.course();
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => this.administrationApi.supprimerCourse(id)),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () =>
          this.retiree.emit({ id, message: `La course ${nom} a été supprimée.`, succes: true }),
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN_GESTION_COURSES)) {
      return;
    }
    const code = lireProbleme(erreur)?.code;
    if (code === CODE_COURSE_INTROUVABLE) {
      this.retiree.emit({
        id: this.course().id,
        message: MESSAGE_COURSE_INTROUVABLE,
        succes: false,
      });
      return;
    }
    const { generale, jetonExpire } = interpreterErreurFormulaire(erreur, ERREURS_SUPPRESSION, []);
    const message = generale ?? MESSAGE_SERVICE_INDISPONIBLE;
    if (code === CODE_COURSE_NON_SUPPRIMABLE) {
      this.erreur.set({ message, avecConfirmation: false });
      this.fermer.emit();
      this.actualiser.emit();
      return;
    }
    this.erreur.set({ message, avecConfirmation: true });
    if (jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }
}
