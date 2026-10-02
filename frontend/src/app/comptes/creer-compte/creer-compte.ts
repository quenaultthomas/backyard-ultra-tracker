import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMPTY, catchError, finalize } from 'rxjs';

import { CsrfService } from '../../partage/csrf.service';
import { ComptesApiService } from '../comptes-api.service';
import { ErreursCreerCompte, interpreterErreurCreation } from './erreurs-creer-compte';

/** Longueur minimale contrôlée par confort : la règle de référence est celle de l'API. */
const LONGUEUR_MIN_MOT_DE_PASSE = 12;

type EtatEcran = 'SAISIE' | 'ENVOI' | 'SUCCES';

function confirmationIdentique(groupe: AbstractControl): ValidationErrors | null {
  const { motDePasse, confirmation } = groupe.value as { motDePasse: string; confirmation: string };
  return motDePasse === confirmation ? null : { confirmationDifferente: true };
}

@Component({
  selector: 'app-creer-compte',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './creer-compte.html',
  styleUrl: './creer-compte.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreerCompte implements OnInit {
  private readonly comptesApi = inject(ComptesApiService);
  private readonly csrf = inject(CsrfService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly formulaire = inject(NonNullableFormBuilder).group(
    {
      pseudo: ['', Validators.required],
      motDePasse: ['', [Validators.required, Validators.minLength(LONGUEUR_MIN_MOT_DE_PASSE)]],
      confirmation: [''],
    },
    { validators: confirmationIdentique },
  );

  protected readonly etat = signal<EtatEcran>('SAISIE');
  protected readonly soumis = signal(false);
  protected readonly erreursServeur = signal<ErreursCreerCompte>({});
  protected readonly pseudoCree = signal('');

  ngOnInit(): void {
    this.demanderJeton();
  }

  protected soumettre(): void {
    if (this.etat() !== 'SAISIE') {
      return;
    }
    this.erreursServeur.set({});
    this.soumis.set(true);
    if (this.formulaire.invalid) {
      return;
    }
    const { pseudo, motDePasse } = this.formulaire.getRawValue();
    this.etat.set('ENVOI');
    this.comptesApi
      .creerCompte({ pseudo, motDePasse })
      .pipe(
        finalize(() => this.viderMotsDePasse()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (compte) => {
          this.pseudoCree.set(compte.pseudo);
          this.etat.set('SUCCES');
        },
        error: (erreur: unknown) => this.afficherErreur(interpreterErreurCreation(erreur)),
      });
  }

  protected erreurPseudo(): string | undefined {
    const pseudo = this.formulaire.controls.pseudo;
    if (this.soumis() && pseudo.hasError('required')) {
      return 'Le pseudo est obligatoire.';
    }
    return this.erreursServeur().pseudo;
  }

  protected erreurMotDePasse(): string | undefined {
    const motDePasse = this.formulaire.controls.motDePasse;
    if (this.soumis() && motDePasse.hasError('required')) {
      return 'Le mot de passe est obligatoire.';
    }
    if (this.soumis() && motDePasse.hasError('minlength')) {
      return `Le mot de passe doit faire au moins ${LONGUEUR_MIN_MOT_DE_PASSE} caractères.`;
    }
    return this.erreursServeur().motDePasse;
  }

  protected erreurConfirmation(): string | undefined {
    return this.soumis() && this.formulaire.hasError('confirmationDifferente')
      ? 'Les deux mots de passe ne correspondent pas.'
      : undefined;
  }

  private afficherErreur(erreurs: ErreursCreerCompte): void {
    this.erreursServeur.set(erreurs);
    this.etat.set('SAISIE');
    if (erreurs.jetonExpire) {
      this.demanderJeton();
    }
  }

  /** Les mots de passe ne sont jamais conservés après un envoi, quel qu'en soit le résultat. */
  private viderMotsDePasse(): void {
    this.formulaire.patchValue({ motDePasse: '', confirmation: '' });
    this.formulaire.controls.motDePasse.markAsPristine();
    this.formulaire.controls.confirmation.markAsPristine();
    this.soumis.set(false);
  }

  /**
   * Un échec ici n'est pas affiché : l'envoi suivant sans jeton valide reçoit un 403
   * `CSRF_INVALIDE`, qui affiche « La page a expiré » et redemande un jeton.
   */
  private demanderJeton(): void {
    this.csrf
      .obtenirJeton()
      .pipe(
        catchError(() => EMPTY),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }
}
