import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { CsrfService } from '../../partage/csrf.service';
import { ComptesApiService } from '../comptes-api.service';
import {
  LONGUEUR_MIN_MOT_DE_PASSE,
  confirmationIdentique,
  erreurClientMotDePasse,
  erreurClientPseudo,
} from '../controles-formulaire-compte';
import { ErreursFormulaireCompte, interpreterErreurCompte } from '../erreurs-compte';
import { SessionService } from '../session.service';

const ERREURS_SPECIFIQUES: Record<string, ErreursFormulaireCompte> = {
  PSEUDO_DEJA_UTILISE: { pseudo: 'Ce pseudo est déjà utilisé.' },
};

type EtatEcran = 'SAISIE' | 'ENVOI' | 'SUCCES';

@Component({
  selector: 'app-creer-compte',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './creer-compte.html',
  styleUrls: ['../../partage/page-carte.css', '../formulaire-compte.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreerCompte implements OnInit {
  private readonly comptesApi = inject(ComptesApiService);
  private readonly csrf = inject(CsrfService);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
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
  protected readonly erreursServeur = signal<ErreursFormulaireCompte>({});
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
        error: (erreur: unknown) =>
          this.afficherErreur(interpreterErreurCompte(erreur, ERREURS_SPECIFIQUES)),
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
      ? 'Les deux mots de passe ne correspondent pas.'
      : undefined;
  }

  private afficherErreur(erreurs: ErreursFormulaireCompte): void {
    if (erreurs.dejaConnecte) {
      this.rejoindreAccueilConnecte();
      return;
    }
    this.erreursServeur.set(erreurs);
    this.etat.set('SAISIE');
    if (erreurs.jetonExpire) {
      this.demanderJeton();
    }
  }

  /** Session ouverte dans un autre onglet : l'état est resynchronisé, l'écran est réservé aux anonymes. */
  private rejoindreAccueilConnecte(): void {
    this.session
      .restaurer()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.router.navigateByUrl('/'));
  }

  /** Les mots de passe ne sont jamais conservés après un envoi, quel qu'en soit le résultat. */
  private viderMotsDePasse(): void {
    this.formulaire.patchValue({ motDePasse: '', confirmation: '' });
    this.formulaire.controls.motDePasse.markAsPristine();
    this.formulaire.controls.confirmation.markAsPristine();
    this.soumis.set(false);
  }

  private demanderJeton(): void {
    this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }
}
