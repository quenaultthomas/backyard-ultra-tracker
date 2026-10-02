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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { CsrfService } from '../../partage/csrf.service';
import { ErreursFormulaireCompte, interpreterErreurCompte } from '../erreurs-compte';
import { destinationApresConnexion } from '../retour';
import { SessionService } from '../session.service';

/** Tout échec d'authentification affiche ce même message, quelle qu'en soit la cause. */
const ERREURS_SPECIFIQUES: Record<string, ErreursFormulaireCompte> = {
  IDENTIFIANTS_INVALIDES: { generale: 'Pseudo ou mot de passe incorrect.' },
};

/** Au moins un caractère autre qu'un espace : un pseudo fait d'espaces est vide pour l'API. */
const NON_BLANC = /\S/;

@Component({
  selector: 'app-connexion',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './connexion.html',
  styleUrl: '../formulaire-compte.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Connexion implements OnInit {
  private readonly session = inject(SessionService);
  private readonly csrf = inject(CsrfService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly destination = destinationApresConnexion(
    inject(ActivatedRoute).snapshot.queryParamMap.get('retour'),
  );

  protected readonly formulaire = inject(NonNullableFormBuilder).group({
    pseudo: ['', [Validators.required, Validators.pattern(NON_BLANC)]],
    motDePasse: ['', Validators.required],
  });

  protected readonly envoiEnCours = signal(false);
  protected readonly soumis = signal(false);
  protected readonly erreursServeur = signal<ErreursFormulaireCompte>({});
  protected readonly deconnexionRecente = signal(this.session.consommerDeconnexionRecente());

  ngOnInit(): void {
    this.demanderJeton();
  }

  protected soumettre(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.erreursServeur.set({});
    this.deconnexionRecente.set(false);
    this.soumis.set(true);
    if (this.formulaire.invalid) {
      return;
    }
    this.envoiEnCours.set(true);
    this.session
      .connecter(this.formulaire.getRawValue())
      .pipe(
        finalize(() => this.viderMotDePasse()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.rejoindreDestination(),
        error: (erreur: unknown) =>
          this.afficherErreur(interpreterErreurCompte(erreur, ERREURS_SPECIFIQUES)),
      });
  }

  protected erreurPseudo(): string | undefined {
    const pseudo = this.formulaire.controls.pseudo;
    if (this.soumis() && pseudo.invalid) {
      return 'Le pseudo est obligatoire.';
    }
    return this.erreursServeur().pseudo;
  }

  protected erreurMotDePasse(): string | undefined {
    if (this.soumis() && this.formulaire.controls.motDePasse.invalid) {
      return 'Le mot de passe est obligatoire.';
    }
    return this.erreursServeur().motDePasse;
  }

  private afficherErreur(erreurs: ErreursFormulaireCompte): void {
    if (erreurs.dejaConnecte) {
      this.session
        .restaurer()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => this.rejoindreDestination());
      return;
    }
    this.erreursServeur.set(erreurs);
    this.envoiEnCours.set(false);
    if (erreurs.jetonExpire) {
      this.demanderJeton();
    }
  }

  private rejoindreDestination(): void {
    void this.router.navigateByUrl(this.destination);
  }

  /** Le mot de passe n'est jamais conservé après un envoi ; le pseudo saisi l'est. */
  private viderMotDePasse(): void {
    this.formulaire.controls.motDePasse.reset();
    this.soumis.set(false);
  }

  private demanderJeton(): void {
    this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }
}
