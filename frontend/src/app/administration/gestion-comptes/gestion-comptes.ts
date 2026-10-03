import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { CompteReponse } from '../../comptes/compte';
import {
  LONGUEUR_MIN_MOT_DE_PASSE,
  confirmationIdentique,
  erreurClientMotDePasse,
  erreurClientPseudo,
} from '../../comptes/controles-formulaire-compte';
import { ErreursFormulaireCompte, interpreterErreurCompte } from '../../comptes/erreurs-compte';
import { CsrfService } from '../../partage/csrf.service';
import { AdministrationApiService } from '../administration-api.service';
import { RefusAccesService } from '../refus-acces.service';
import { ComptesGeres } from './comptes-geres';

const ERREURS_SPECIFIQUES: Record<string, ErreursFormulaireCompte> = {
  PSEUDO_DEJA_UTILISE: { pseudo: 'Ce pseudo est déjà utilisé.' },
};

/**
 * Liste et création des Comptes d'un rôle géré par l'administration (admins, bénévoles).
 * Le type de Compte est fourni par la route (`data.comptesGeres`).
 */
@Component({
  selector: 'app-gestion-comptes',
  imports: [ReactiveFormsModule, RouterLink, DatePipe],
  templateUrl: './gestion-comptes.html',
  styleUrls: [
    '../../partage/page-carte.css',
    '../../comptes/formulaire-compte.css',
    './gestion-comptes.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GestionComptes implements OnInit {
  readonly comptesGeres = input.required<ComptesGeres>();

  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly formulaire = inject(NonNullableFormBuilder).group(
    {
      pseudo: ['', Validators.required],
      motDePasse: ['', [Validators.required, Validators.minLength(LONGUEUR_MIN_MOT_DE_PASSE)]],
      confirmation: [''],
    },
    { validators: confirmationIdentique },
  );

  /** `null` tant que la liste n'est pas chargée. */
  protected readonly comptes = signal<CompteReponse[] | null>(null);
  protected readonly erreurChargement = signal(false);
  protected readonly envoiEnCours = signal(false);
  protected readonly soumis = signal(false);
  protected readonly erreursServeur = signal<ErreursFormulaireCompte>({});
  protected readonly pseudoCree = signal<string | null>(null);

  ngOnInit(): void {
    this.demanderJeton();
    this.chargerComptes();
  }

  protected soumettre(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.erreursServeur.set({});
    this.pseudoCree.set(null);
    this.soumis.set(true);
    if (this.formulaire.invalid) {
      return;
    }
    const { pseudo, motDePasse } = this.formulaire.getRawValue();
    this.envoiEnCours.set(true);
    this.comptesGeres()
      .creer(this.administrationApi, { pseudo, motDePasse })
      .pipe(
        finalize(() => this.terminerEnvoi()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (compte) => this.confirmerCreation(compte),
        error: (erreur: unknown) => this.traiterErreurCreation(erreur),
      });
  }

  protected erreurPseudo(): string | undefined {
    const erreurClient = this.soumis()
      ? erreurClientPseudo(this.formulaire.controls.pseudo)
      : undefined;
    return erreurClient ?? this.erreursServeur().pseudo;
  }

  protected erreurMotDePasse(): string | undefined {
    const erreurClient = this.soumis()
      ? erreurClientMotDePasse(this.formulaire.controls.motDePasse)
      : undefined;
    return erreurClient ?? this.erreursServeur().motDePasse;
  }

  protected erreurConfirmation(): string | undefined {
    return this.soumis() && this.formulaire.hasError('confirmationDifferente')
      ? 'Les mots de passe ne correspondent pas.'
      : undefined;
  }

  private chargerComptes(): void {
    this.comptesGeres()
      .lister(this.administrationApi)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (comptes) => {
          this.comptes.set(comptes);
          this.erreurChargement.set(false);
        },
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, this.comptesGeres().ecran)) {
            this.erreurChargement.set(true);
          }
        },
      });
  }

  private confirmerCreation(compte: CompteReponse): void {
    this.pseudoCree.set(compte.pseudo);
    this.formulaire.controls.pseudo.setValue('');
    this.chargerComptes();
  }

  private traiterErreurCreation(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, this.comptesGeres().ecran)) {
      return;
    }
    const erreurs = interpreterErreurCompte(erreur, ERREURS_SPECIFIQUES);
    this.erreursServeur.set(erreurs);
    if (erreurs.jetonExpire) {
      this.demanderJeton();
    }
  }

  /** Les mots de passe ne sont jamais conservés après un envoi, quel qu'en soit le résultat. */
  private terminerEnvoi(): void {
    this.formulaire.patchValue({ motDePasse: '', confirmation: '' });
    this.formulaire.markAsPristine();
    this.soumis.set(false);
    this.envoiEnCours.set(false);
  }

  private demanderJeton(): void {
    this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }
}
