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

import { interpreterErreurFormulaire } from '../../../comptes/erreurs-compte';
import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { CsrfService } from '../../../partage/csrf.service';
import { MESSAGE_SERVICE_INDISPONIBLE, lireProbleme } from '../../../partage/probleme';
import { AdministrationApiService } from '../../administration-api.service';
import { CoureurTrouve } from '../../recherche-coureurs';
import { PassageComptesGeres } from '../comptes-geres';
import { ResultatChangementRole } from '../resultat-action-compte';

/**
 * Changement de rôle d'un coureur trouvé par la recherche : bouton, confirmation en ligne, puis
 * un seul `POST`. Le serveur décide de tous les refus.
 */
@Component({
  selector: 'app-changement-role',
  templateUrl: './changement-role.html',
  styleUrls: ['../../../partage/confirmation-en-ligne.css', './changement-role.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChangementRole {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly boutonPasser = viewChild<ElementRef<HTMLButtonElement>>('boutonPasser');
  private readonly boutonAnnuler = viewChild<ElementRef<HTMLButtonElement>>('boutonAnnuler');

  readonly coureur = input.required<CoureurTrouve>();
  readonly passage = input.required<PassageComptesGeres>();
  /** Préfixe des `data-testid` (`benevole-promotion`). */
  readonly prefixe = input.required<string>();
  /** Route de l'écran, pour le retour après connexion. */
  readonly ecran = input.required<string>();
  /** Vrai quand la confirmation de ce résultat est la seule ouverte de la page. */
  readonly ouverte = input(false);
  readonly ouvrir = output<void>();
  readonly fermer = output<void>();
  readonly termine = output<ResultatChangementRole>();

  protected readonly envoiEnCours = signal(false);
  protected readonly texteConfirmation = computed(() =>
    this.passage().confirmation(this.coureur().pseudo),
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
    this.boutonPasser()?.nativeElement.focus();
  }

  /** Nouveau jeton CSRF puis `POST` ; boutons désactivés jusqu'à la réponse. */
  protected confirmer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    const { id, pseudo } = this.coureur();
    const passage = this.passage();
    this.envoiEnCours.set(true);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => passage.passer(this.administrationApi, id)),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () =>
          this.termine.emit({
            succes: passage.succes(pseudo),
            recharger: true,
            relancerRecherche: true,
          }),
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, this.ecran())) {
      return;
    }
    const { generale, jetonExpire } = interpreterErreurFormulaire(
      erreur,
      this.passage().erreurs,
      [],
    );
    const introuvable = lireProbleme(erreur)?.code === 'COMPTE_INTROUVABLE';
    this.envoiEnCours.set(false);
    this.termine.emit({
      erreur: generale ?? MESSAGE_SERVICE_INDISPONIBLE,
      recharger: !jetonExpire,
      relancerRecherche: introuvable,
    });
    if (!introuvable) {
      // Confirmation fermée : le focus revient sur le bouton du résultat, une fois réactivé.
      afterNextRender(() => this.boutonPasser()?.nativeElement.focus(), {
        injector: this.injector,
      });
    }
    if (jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }
}
