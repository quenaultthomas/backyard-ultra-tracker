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

import { CompteReponse } from '../../../comptes/compte';
import { ErreursSpecifiques, interpreterErreurFormulaire } from '../../../comptes/erreurs-compte';
import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { CsrfService } from '../../../partage/csrf.service';
import { MESSAGE_SERVICE_INDISPONIBLE } from '../../../partage/probleme';
import { AdministrationApiService } from '../../administration-api.service';
import { ComptesGeres } from '../comptes-geres';

const ERREURS_ACTIVATION: ErreursSpecifiques<never> = {
  DERNIER_ADMIN_ACTIF: { generale: "Impossible : c'est le dernier administrateur actif." },
  ADMIN_MASTER_NON_DESACTIVABLE: {
    generale: "L'administrateur principal ne peut pas être désactivé.",
  },
  AUTO_DESACTIVATION_INTERDITE: {
    generale: 'Vous ne pouvez pas désactiver votre propre compte.',
  },
  COMPTE_INTROUVABLE: { generale: "Ce compte n'existe plus." },
};

/** Issue d'une désactivation ou d'une réactivation confirmée, affichée par la liste. */
export interface ResultatActivation {
  readonly succes?: string;
  readonly erreur?: string;
  /** La liste est à relire (faux pour un jeton CSRF expiré). */
  readonly recharger: boolean;
}

/**
 * Désactivation ou réactivation d'un Compte depuis sa ligne : bouton selon l'état renvoyé par
 * l'API, confirmation en ligne, puis un seul `POST`. Le serveur décide de tous les refus.
 */
@Component({
  selector: 'app-activation-compte',
  templateUrl: './activation-compte.html',
  styleUrls: ['../../../partage/confirmation-en-ligne.css', './activation-compte.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActivationCompte {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly boutonAction = viewChild<ElementRef<HTMLButtonElement>>('boutonAction');
  private readonly boutonAnnuler = viewChild<ElementRef<HTMLButtonElement>>('boutonAnnuler');

  readonly compte = input.required<CompteReponse>();
  readonly comptesGeres = input.required<ComptesGeres>();
  /** Vrai quand la confirmation de cette ligne est la seule ouverte de la liste. */
  readonly ouverte = input(false);
  readonly ouvrir = output<void>();
  readonly fermer = output<void>();
  readonly termine = output<ResultatActivation>();

  protected readonly envoiEnCours = signal(false);
  protected readonly texteConfirmation = computed(() => {
    const { pseudo, actif } = this.compte();
    return actif
      ? this.comptesGeres().confirmationDesactivation(pseudo)
      : `Réactiver le compte ${pseudo} ? Il pourra de nouveau se connecter avec son mot de passe.`;
  });

  constructor() {
    // Navigation au clavier : le focus va sur « Annuler » à l'ouverture de la confirmation.
    effect(() => this.boutonAnnuler()?.nativeElement.focus());
  }

  protected annuler(): void {
    this.fermer.emit();
    this.boutonAction()?.nativeElement.focus();
  }

  /** Nouveau jeton CSRF puis `POST` ; boutons désactivés jusqu'à la réponse. */
  protected confirmer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    const { id, pseudo, actif } = this.compte();
    const geres = this.comptesGeres();
    const action = actif ? geres.desactiver : geres.reactiver;
    const succes = `Le compte ${pseudo} a été ${actif ? 'désactivé' : 'réactivé'}.`;
    this.envoiEnCours.set(true);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => action(this.administrationApi, id)),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.termine.emit({ succes, recharger: true }),
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, this.comptesGeres().ecran)) {
      return;
    }
    const { generale, jetonExpire } = interpreterErreurFormulaire(erreur, ERREURS_ACTIVATION, []);
    this.termine.emit({
      erreur: generale ?? MESSAGE_SERVICE_INDISPONIBLE,
      recharger: !jetonExpire,
    });
    if (jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }
}
