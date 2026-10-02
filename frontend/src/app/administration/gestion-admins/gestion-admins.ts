import { DatePipe } from '@angular/common';
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

const ECRAN = '/administration/admins';

const ERREURS_SPECIFIQUES: Record<string, ErreursFormulaireCompte> = {
  PSEUDO_DEJA_UTILISE: { pseudo: 'Ce pseudo est déjà utilisé.' },
};

/** Liste des Comptes `ADMIN` et création d'un admin, réservées à l'admin master. */
@Component({
  selector: 'app-gestion-admins',
  imports: [ReactiveFormsModule, RouterLink, DatePipe],
  templateUrl: './gestion-admins.html',
  styleUrls: [
    '../../partage/page-carte.css',
    '../../comptes/formulaire-compte.css',
    './gestion-admins.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GestionAdmins implements OnInit {
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
  protected readonly admins = signal<CompteReponse[] | null>(null);
  protected readonly erreurChargement = signal(false);
  protected readonly envoiEnCours = signal(false);
  protected readonly soumis = signal(false);
  protected readonly erreursServeur = signal<ErreursFormulaireCompte>({});
  protected readonly pseudoCree = signal<string | null>(null);

  ngOnInit(): void {
    this.demanderJeton();
    this.chargerAdmins();
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
    this.administrationApi
      .creerAdmin({ pseudo, motDePasse })
      .pipe(
        finalize(() => this.terminerEnvoi()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (admin) => this.confirmerCreation(admin),
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

  private chargerAdmins(): void {
    this.administrationApi
      .listerAdmins()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (admins) => {
          this.admins.set(admins);
          this.erreurChargement.set(false);
        },
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, ECRAN)) {
            this.erreurChargement.set(true);
          }
        },
      });
  }

  private confirmerCreation(admin: CompteReponse): void {
    this.pseudoCree.set(admin.pseudo);
    this.formulaire.controls.pseudo.setValue('');
    this.chargerAdmins();
  }

  private traiterErreurCreation(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN)) {
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
