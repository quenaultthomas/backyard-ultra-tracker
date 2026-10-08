import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';

import { CsrfService } from '../../partage/csrf.service';
import { ChangerMotDePasseRequete } from '../compte';
import {
  LONGUEUR_MIN_MOT_DE_PASSE,
  confirmationIdentiqueA,
  erreurClientConfirmation,
  erreurClientMotDePasse,
} from '../controles-formulaire-compte';
import {
  ErreursFormulaire,
  ErreursSpecifiques,
  erreurTentativesExcessives,
  interpreterErreurFormulaire,
} from '../erreurs-compte';
import { RefusAccesService } from '../refus-acces.service';
import { LIBELLES_ROLE } from '../roles';
import { SessionService } from '../session.service';
import { ECRAN_MON_COMPTE } from './ecran-mon-compte';
import { SuppressionCompte } from './suppression-compte/suppression-compte';

type ChampMotDePasse = keyof ChangerMotDePasseRequete;

const CHAMPS: readonly ChampMotDePasse[] = ['motDePasseActuel', 'nouveauMotDePasse'];

const ERREURS_SPECIFIQUES: ErreursSpecifiques<ChampMotDePasse> = {
  MOT_DE_PASSE_ACTUEL_INCORRECT: { motDePasseActuel: 'Le mot de passe actuel est incorrect.' },
  NOUVEAU_MOT_DE_PASSE_IDENTIQUE: {
    nouveauMotDePasse: "Le nouveau mot de passe doit être différent de l'actuel.",
  },
  TENTATIVES_EXCESSIVES: erreurTentativesExcessives('Trop de tentatives.'),
};

/**
 * Écran « Mon compte » de tout Compte connecté : identité, changement du mot de passe (section
 * repliable, repliée à l'arrivée) et, pour un coureur, suppression du Compte.
 */
@Component({
  selector: 'app-mon-compte',
  imports: [ReactiveFormsModule, SuppressionCompte],
  templateUrl: './mon-compte.html',
  styleUrls: ['../../partage/page-carte.css', '../formulaire-compte.css', './mon-compte.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MonCompte implements OnInit {
  private readonly session = inject(SessionService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly boutonSection =
    viewChild.required<ElementRef<HTMLButtonElement>>('boutonSection');

  protected readonly compte = this.session.compte;
  /** Simple aide d'affichage : l'API refuse la suppression aux autres rôles (403). */
  protected readonly estCoureur = this.session.estCoureur;
  protected readonly libelleRole = computed(() => {
    const role = this.compte()?.role;
    return role === undefined ? null : LIBELLES_ROLE[role];
  });

  protected readonly formulaire = inject(NonNullableFormBuilder).group(
    {
      motDePasseActuel: ['', Validators.required],
      nouveauMotDePasse: [
        '',
        [Validators.required, Validators.minLength(LONGUEUR_MIN_MOT_DE_PASSE)],
      ],
      confirmation: [''],
    },
    { validators: confirmationIdentiqueA('nouveauMotDePasse') },
  );

  protected readonly deplie = signal(false);
  protected readonly envoiEnCours = signal(false);
  protected readonly soumis = signal(false);
  protected readonly succes = signal(false);
  protected readonly erreursServeur = signal<ErreursFormulaire<ChampMotDePasse>>({});

  ngOnInit(): void {
    this.demanderJeton();
  }

  /** Bascule la section, sans appel réseau ; le focus reste sur le bouton de section. */
  protected basculer(): void {
    if (this.deplie()) {
      this.viderFormulaire();
      this.erreursServeur.set({});
      this.deplie.set(false);
    } else {
      this.succes.set(false);
      this.deplie.set(true);
    }
  }

  protected soumettre(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.erreursServeur.set({});
    this.succes.set(false);
    this.soumis.set(true);
    if (this.formulaire.invalid) {
      return;
    }
    const { motDePasseActuel, nouveauMotDePasse } = this.formulaire.getRawValue();
    this.envoiEnCours.set(true);
    this.session
      .changerMotDePasse({ motDePasseActuel, nouveauMotDePasse })
      .pipe(
        finalize(() => this.terminerEnvoi()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.replierApresSucces(),
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  protected erreurActuel(): string | undefined {
    const actuel = this.formulaire.controls.motDePasseActuel;
    const erreurClient =
      this.soumis() && actuel.hasError('required')
        ? 'Le mot de passe actuel est obligatoire.'
        : undefined;
    return erreurClient ?? this.erreursServeur().motDePasseActuel;
  }

  protected erreurNouveau(): string | undefined {
    const erreurClient = this.soumis()
      ? erreurClientMotDePasse(
          this.formulaire.controls.nouveauMotDePasse,
          'Le nouveau mot de passe est obligatoire.',
        )
      : undefined;
    return erreurClient ?? this.erreursServeur().nouveauMotDePasse;
  }

  protected erreurConfirmation(): string | undefined {
    return this.soumis() ? erreurClientConfirmation(this.formulaire) : undefined;
  }

  /** 401 : retour à la connexion ; sinon message sous le champ concerné ou message général. */
  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN_MON_COMPTE)) {
      return;
    }
    const erreurs = interpreterErreurFormulaire(erreur, ERREURS_SPECIFIQUES, CHAMPS);
    this.erreursServeur.set(erreurs);
    if (erreurs.jetonExpire) {
      this.demanderJeton();
    }
  }

  /**
   * Le bouton « Changer le mot de passe » disparaît avec le panneau : le focus va sur le bouton de
   * section, une fois celui-ci réactivé par la fin de l'envoi.
   */
  private replierApresSucces(): void {
    this.succes.set(true);
    this.deplie.set(false);
    afterNextRender(() => this.boutonSection().nativeElement.focus(), {
      injector: this.injector,
    });
  }

  /** Les mots de passe ne sont jamais conservés après un envoi, quel qu'en soit le résultat. */
  private terminerEnvoi(): void {
    this.viderFormulaire();
    this.envoiEnCours.set(false);
  }

  /** Vide les champs : aucun mot de passe n'est conservé dans un panneau replié. */
  private viderFormulaire(): void {
    this.formulaire.reset();
    this.soumis.set(false);
  }

  private demanderJeton(): void {
    this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }
}
