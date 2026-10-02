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
import { ProblemDetail } from '../../partage/probleme';
import {
  ErreursFormulaireCompte,
  ErreursSpecifiques,
  interpreterErreurCompte,
} from '../erreurs-compte';
import { destinationApresConnexion } from '../retour';
import { SessionService } from '../session.service';

/**
 * Message de blocage temporaire : délai en minutes arrondi au supérieur, ou message
 * de repli si l'API ne fournit pas de délai exploitable.
 */
function erreurTentativesExcessives({
  reessayerDansSecondes,
}: ProblemDetail): ErreursFormulaireCompte {
  const prefixe = 'Trop de tentatives de connexion. Réessayez';
  if (
    typeof reessayerDansSecondes !== 'number' ||
    !Number.isFinite(reessayerDansSecondes) ||
    reessayerDansSecondes <= 0
  ) {
    return { generale: `${prefixe} plus tard.` };
  }
  const minutes = Math.ceil(reessayerDansSecondes / 60);
  return { generale: `${prefixe} dans ${minutes} ${minutes === 1 ? 'minute' : 'minutes'}.` };
}

const ERREURS_SPECIFIQUES: ErreursSpecifiques = {
  /** Tout échec d'authentification affiche ce même message, quelle qu'en soit la cause. */
  IDENTIFIANTS_INVALIDES: { generale: 'Pseudo ou mot de passe incorrect.' },
  TENTATIVES_EXCESSIVES: erreurTentativesExcessives,
};

/** Au moins un caractère autre qu'un espace : un pseudo fait d'espaces est vide pour l'API. */
const NON_BLANC = /\S/;

@Component({
  selector: 'app-connexion',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './connexion.html',
  styleUrls: ['../../partage/page-carte.css', '../formulaire-compte.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Connexion implements OnInit {
  private readonly session = inject(SessionService);
  private readonly csrf = inject(CsrfService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly retour = inject(ActivatedRoute).snapshot.queryParamMap.get('retour');

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

  /** La destination dépend du rôle du Compte connecté, connu seulement après la connexion. */
  private rejoindreDestination(): void {
    const role = this.session.compte()?.role;
    void this.router.navigateByUrl(destinationApresConnexion(this.retour, role));
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
