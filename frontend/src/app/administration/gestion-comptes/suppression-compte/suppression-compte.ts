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
import { finalize, switchMap } from 'rxjs';

import { CompteReponse } from '../../../comptes/compte';
import { ErreursSpecifiques, interpreterErreurFormulaire } from '../../../comptes/erreurs-compte';
import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { CsrfService } from '../../../partage/csrf.service';
import { MESSAGE_SERVICE_INDISPONIBLE } from '../../../partage/probleme';
import { AdministrationApiService } from '../../administration-api.service';
import { SuppressionComptesGeres } from '../comptes-geres';
import { ResultatActionCompte } from '../resultat-action-compte';

const ERREURS_COMMUNES: ErreursSpecifiques<never> = {
  COMPTE_INTROUVABLE: { generale: "Ce compte n'existe plus." },
};

/**
 * Suppression définitive d'un Compte depuis sa ligne : bouton, confirmation en ligne, puis un
 * seul `DELETE`. Le serveur décide de tous les refus.
 */
@Component({
  selector: 'app-suppression-compte',
  templateUrl: './suppression-compte.html',
  styleUrls: ['../../../partage/confirmation-en-ligne.css', '../action-compte.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SuppressionCompte {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly boutonSupprimer = viewChild<ElementRef<HTMLButtonElement>>('boutonSupprimer');
  private readonly boutonAnnuler = viewChild<ElementRef<HTMLButtonElement>>('boutonAnnuler');

  readonly compte = input.required<CompteReponse>();
  readonly suppression = input.required<SuppressionComptesGeres>();
  /** Préfixe des `data-testid` (`benevole`, puis `admin` au 9.4). */
  readonly prefixe = input.required<string>();
  /** Route de l'écran, pour le retour après connexion. */
  readonly ecran = input.required<string>();
  /** Vrai quand la confirmation de cette ligne est la seule ouverte de la liste. */
  readonly ouverte = input(false);
  readonly ouvrir = output<void>();
  readonly fermer = output<void>();
  readonly termine = output<ResultatActionCompte>();

  protected readonly envoiEnCours = signal(false);
  protected readonly texteConfirmation = computed(() =>
    this.suppression().confirmation(this.compte().pseudo),
  );

  constructor() {
    // Navigation au clavier : le focus va sur « Annuler » à l'ouverture de la confirmation.
    effect(() => this.boutonAnnuler()?.nativeElement.focus());
  }

  protected annuler(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.fermer.emit();
    this.boutonSupprimer()?.nativeElement.focus();
  }

  /** Nouveau jeton CSRF puis `DELETE` ; boutons désactivés jusqu'à la réponse. */
  protected confirmer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    const { id, pseudo } = this.compte();
    const suppression = this.suppression();
    this.envoiEnCours.set(true);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => suppression.supprimer(this.administrationApi, id)),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () =>
          this.termine.emit({
            succes: suppression.succes(pseudo),
            recharger: true,
            compteRetire: true,
          }),
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, this.ecran())) {
      return;
    }
    const specifiques = { ...ERREURS_COMMUNES, ...this.suppression().erreurs };
    const { generale, jetonExpire } = interpreterErreurFormulaire(erreur, specifiques, []);
    this.envoiEnCours.set(false);
    this.termine.emit({
      erreur: generale ?? MESSAGE_SERVICE_INDISPONIBLE,
      recharger: !jetonExpire,
    });
    // Confirmation fermée : le focus revient sur « Supprimer », une fois le bouton réactivé.
    afterNextRender(() => this.boutonSupprimer()?.nativeElement.focus(), {
      injector: this.injector,
    });
    if (jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }
}
