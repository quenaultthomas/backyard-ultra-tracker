import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
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

import { RefusAccesService } from '../../comptes/refus-acces.service';
import { CsrfService } from '../../partage/csrf.service';
import {
  MESSAGE_PAGE_EXPIREE,
  MESSAGE_SERVICE_INDISPONIBLE,
  lireProbleme,
} from '../../partage/probleme';
import { CoureurApiService } from '../coureur-api.service';
import { MonInscriptionReponse } from '../mon-inscription';

export const ECRAN_MES_INSCRIPTIONS = '/coureur/inscriptions';

/** Issue d'une désinscription, affichée au-dessus de la liste. */
export interface ResultatDesinscription {
  readonly message: string;
  readonly succes: boolean;
  /** L'état a changé côté serveur : confirmation à fermer et liste à relire. */
  readonly relire: boolean;
}

/** Désinscription depuis une ligne de « Mes inscriptions » : bouton, confirmation en ligne, envoi. */
@Component({
  selector: 'app-desinscription',
  templateUrl: './desinscription.html',
  styleUrls: [
    '../../administration/courses/action-ligne.css',
    '../../partage/confirmation-en-ligne.css',
    './desinscription.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Desinscription {
  private readonly coureurApi = inject(CoureurApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly boutonDesinscrire = viewChild<ElementRef<HTMLButtonElement>>('boutonDesinscrire');
  private readonly boutonAnnuler = viewChild<ElementRef<HTMLButtonElement>>('boutonAnnuler');

  readonly inscription = input.required<MonInscriptionReponse>();
  /** Vrai quand la confirmation de cette ligne est la seule ouverte de la liste. */
  readonly ouverte = input(false);
  readonly ouvrir = output<void>();
  readonly fermer = output<void>();
  readonly resultat = output<ResultatDesinscription>();

  protected readonly envoiEnCours = signal(false);
  /** Simple aide d'affichage selon le statut renvoyé par l'API, qui reste la référence. */
  protected readonly desinscriptible = computed(
    () => this.inscription().courseStatut === 'EN_PREPARATION',
  );
  protected readonly confirmationAffichee = computed(
    () => this.ouverte() && this.desinscriptible(),
  );

  constructor() {
    // Navigation au clavier : le focus va sur « Annuler » à l'ouverture de la confirmation.
    effect(() => this.boutonAnnuler()?.nativeElement.focus());
  }

  protected annuler(): void {
    this.fermer.emit();
    this.boutonDesinscrire()?.nativeElement.focus();
  }

  /** Nouveau jeton CSRF puis `DELETE` ; un seul envoi à la fois (double clic sans effet). */
  protected confirmer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    const { id, courseNom } = this.inscription();
    this.envoiEnCours.set(true);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => this.coureurApi.seDesinscrire(id)),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () =>
          this.resultat.emit({
            message: `Vous êtes désinscrit de ${courseNom}.`,
            succes: true,
            relire: true,
          }),
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN_MES_INSCRIPTIONS)) {
      return;
    }
    const statut = erreur instanceof HttpErrorResponse ? erreur.status : null;
    const code = lireProbleme(erreur)?.code;
    if (statut === HttpStatusCode.Conflict && code === 'DESINSCRIPTION_IMPOSSIBLE') {
      this.signalerErreur('La course a démarré : vous ne pouvez plus vous désinscrire.', true);
    } else if (statut === HttpStatusCode.NotFound) {
      this.signalerErreur("Cette inscription n'existe plus.", true);
    } else if (code === 'CSRF_INVALIDE') {
      this.signalerErreur(MESSAGE_PAGE_EXPIREE, false);
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    } else {
      this.signalerErreur(MESSAGE_SERVICE_INDISPONIBLE, false);
    }
  }

  private signalerErreur(message: string, relire: boolean): void {
    this.resultat.emit({ message, succes: false, relire });
  }
}
