import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize, switchMap } from 'rxjs';

import { CsrfService } from '../../../partage/csrf.service';
import { SupprimerCompteRequete } from '../../compte';
import {
  ErreursFormulaire,
  ErreursSpecifiques,
  erreurTentativesExcessives,
  interpreterErreurFormulaire,
} from '../../erreurs-compte';
import { RefusAccesService } from '../../refus-acces.service';
import { SessionService } from '../../session.service';
import { ECRAN_MON_COMPTE } from '../ecran-mon-compte';

type ChampSuppression = keyof SupprimerCompteRequete;

const CHAMPS: readonly ChampSuppression[] = ['motDePasseActuel'];

const ERREURS_SPECIFIQUES: ErreursSpecifiques<ChampSuppression> = {
  MOT_DE_PASSE_ACTUEL_INCORRECT: { motDePasseActuel: 'Le mot de passe est incorrect.' },
  TENTATIVES_EXCESSIVES: erreurTentativesExcessives('Trop de tentatives.'),
};

/** Section « Supprimer mon compte » d'un coureur : avertissement, confirmation par mot de passe, envoi. */
@Component({
  selector: 'app-suppression-compte',
  imports: [ReactiveFormsModule],
  templateUrl: './suppression-compte.html',
  styleUrls: [
    '../../../partage/page-carte.css',
    '../../formulaire-compte.css',
    '../../../partage/confirmation-en-ligne.css',
    './suppression-compte.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SuppressionCompte {
  private readonly session = inject(SessionService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly boutonSupprimer = viewChild<ElementRef<HTMLButtonElement>>('boutonSupprimer');
  private readonly champMotDePasse = viewChild<ElementRef<HTMLInputElement>>('champMotDePasse');

  protected readonly formulaire = inject(NonNullableFormBuilder).group({
    motDePasseActuel: ['', Validators.required],
  });
  protected readonly confirmationOuverte = signal(false);
  protected readonly envoiEnCours = signal(false);
  protected readonly soumis = signal(false);
  protected readonly erreursServeur = signal<ErreursFormulaire<ChampSuppression>>({});

  constructor() {
    // Navigation au clavier : le focus va sur le mot de passe à l'ouverture de la confirmation.
    effect(() => this.champMotDePasse()?.nativeElement.focus());
  }

  /** Ouvre la confirmation, sans appel réseau. */
  protected ouvrir(): void {
    this.confirmationOuverte.set(true);
  }

  protected annuler(): void {
    this.reinitialiser();
    this.erreursServeur.set({});
    this.confirmationOuverte.set(false);
    this.boutonSupprimer()?.nativeElement.focus();
  }

  /** Nouveau jeton CSRF puis `POST` ; un seul envoi à la fois (double clic sans effet). */
  protected confirmer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.erreursServeur.set({});
    this.soumis.set(true);
    if (this.formulaire.invalid) {
      return;
    }
    const requete: SupprimerCompteRequete = this.formulaire.getRawValue();
    this.envoiEnCours.set(true);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => this.session.supprimerCompte(requete)),
        finalize(() => this.reinitialiser()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => void this.router.navigateByUrl('/connexion'),
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  protected erreurMotDePasse(): string | undefined {
    const erreurClient =
      this.soumis() && this.formulaire.controls.motDePasseActuel.hasError('required')
        ? 'Le mot de passe est obligatoire.'
        : undefined;
    return erreurClient ?? this.erreursServeur().motDePasseActuel;
  }

  /** 401 : connexion ; 403 (hors CSRF) : accès refusé ; sinon message, confirmation conservée. */
  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN_MON_COMPTE)) {
      return;
    }
    const erreurs = interpreterErreurFormulaire(erreur, ERREURS_SPECIFIQUES, CHAMPS);
    this.erreursServeur.set(erreurs);
    if (erreurs.jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }

  /** Le mot de passe n'est jamais conservé après un envoi, quel qu'en soit le résultat. */
  private reinitialiser(): void {
    this.formulaire.reset();
    this.soumis.set(false);
    this.envoiEnCours.set(false);
  }
}
