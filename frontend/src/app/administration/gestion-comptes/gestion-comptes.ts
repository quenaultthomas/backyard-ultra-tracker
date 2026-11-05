import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { CompteReponse } from '../../comptes/compte';
import {
  LONGUEUR_MIN_MOT_DE_PASSE,
  confirmationIdentique,
  erreurClientConfirmation,
  erreurClientMotDePasse,
  erreurClientPseudo,
} from '../../comptes/controles-formulaire-compte';
import { ErreursFormulaireCompte, interpreterErreurCompte } from '../../comptes/erreurs-compte';
import { CsrfService } from '../../partage/csrf.service';
import { FormulaireALaDemande } from '../../partage/formulaire-a-la-demande';
import { AdministrationApiService } from '../administration-api.service';
import { RefusAccesService } from '../../comptes/refus-acces.service';
import { MenuAdministration } from '../menu-administration/menu-administration';
import { ActivationCompte } from './activation-compte/activation-compte';
import { ComptesGeres } from './comptes-geres';
import { RechercheCoureurs } from './recherche-coureurs/recherche-coureurs';
import { ActionCompte, ConfirmationOuverte, ResultatActionCompte } from './resultat-action-compte';
import { SuppressionCompte } from './suppression-compte/suppression-compte';

/** Pastille de l'état d'un Compte (affichage seulement, l'état vient de l'API). */
const PASTILLES_ETAT = {
  actif: 'pastille-statut pastille-statut--en-course',
  desactive: 'pastille-statut pastille-statut--en-preparation',
} as const;

const ERREURS_SPECIFIQUES: Record<string, ErreursFormulaireCompte> = {
  PSEUDO_DEJA_UTILISE: { pseudo: 'Ce pseudo est déjà utilisé.' },
};

/**
 * Liste et création des Comptes d'un rôle géré par l'administration (admins, bénévoles).
 * Le type de Compte est fourni par la route (`data.comptesGeres`).
 */
@Component({
  selector: 'app-gestion-comptes',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    ActivationCompte,
    SuppressionCompte,
    MenuAdministration,
    RechercheCoureurs,
  ],
  templateUrl: './gestion-comptes.html',
  styleUrls: [
    '../../partage/page-carte.css',
    '../../comptes/formulaire-compte.css',
    '../../partage/formulaire-a-la-demande.css',
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
  private readonly boutonNouveau =
    viewChild.required<ElementRef<HTMLButtonElement>>('boutonNouveau');
  private readonly champPseudo = viewChild.required<ElementRef<HTMLInputElement>>('champPseudo');
  /** Formulaire de création fermé à l'arrivée, ouvert par le bouton « Nouvel admin » / « Nouveau bénévole ». */
  protected readonly ouverture = new FormulaireALaDemande(this.boutonNouveau, this.champPseudo);

  protected readonly formulaire = inject(NonNullableFormBuilder).group(
    {
      pseudo: ['', Validators.required],
      motDePasse: ['', [Validators.required, Validators.minLength(LONGUEUR_MIN_MOT_DE_PASSE)]],
      confirmation: [''],
    },
    { validators: confirmationIdentique },
  );

  protected readonly pastillesEtat = PASTILLES_ETAT;

  /** `null` tant que la liste n'est pas chargée. */
  protected readonly comptes = signal<CompteReponse[] | null>(null);
  protected readonly erreurChargement = signal(false);
  protected readonly envoiEnCours = signal(false);
  protected readonly soumis = signal(false);
  protected readonly erreursServeur = signal<ErreursFormulaireCompte>({});
  protected readonly pseudoCree = signal<string | null>(null);
  /** Compte et action dont la confirmation est ouverte (une seule à la fois, toutes actions confondues). */
  protected readonly confirmationOuverte = signal<ConfirmationOuverte | null>(null);
  protected readonly resultatAction = signal<ResultatActionCompte | null>(null);

  ngOnInit(): void {
    this.demanderJeton();
    this.chargerComptes();
  }

  /** Ouvre le formulaire vide, sans message ; déjà ouvert, seul le focus change. */
  protected ouvrir(): void {
    if (this.ouverture.ouvrir()) {
      this.pseudoCree.set(null);
    }
  }

  /** Ferme le formulaire, champs vidés (pseudo compris) et erreurs effacées, sans requête. */
  protected fermer(): void {
    this.viderFormulaire();
    this.ouverture.fermer();
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
    return this.soumis() ? erreurClientConfirmation(this.formulaire) : undefined;
  }

  protected estOuverte(id: string, action: ActionCompte): boolean {
    return this.idConfirmationOuverte(action) === id;
  }

  /** Identifiant du Compte dont la confirmation de cette action est ouverte, sinon `null`. */
  protected idConfirmationOuverte(action: ActionCompte): string | null {
    const ouverte = this.confirmationOuverte();
    return ouverte?.action === action ? ouverte.id : null;
  }

  /** Ferme la confirmation ouverte si elle porte sur cette action (laisse celle d'une autre action). */
  protected fermerConfirmation(action: ActionCompte): void {
    if (this.confirmationOuverte()?.action === action) {
      this.confirmationOuverte.set(null);
    }
  }

  /** Ouvre une confirmation (ferme la précédente, quelle que soit l'action) et efface les messages d'action. */
  protected ouvrirConfirmation(id: string, action: ActionCompte): void {
    this.resultatAction.set(null);
    this.confirmationOuverte.set({ id, action });
  }

  /** Confirmation fermée, message affiché, liste relue selon l'issue. */
  protected afficherResultat(resultat: ResultatActionCompte): void {
    this.confirmationOuverte.set(null);
    this.resultatAction.set(resultat);
    if (resultat.recharger) {
      this.chargerComptes();
    }
    if (resultat.compteRetire) {
      this.boutonNouveau().nativeElement.focus();
    }
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
    this.fermer();
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

  private viderFormulaire(): void {
    this.formulaire.reset();
    this.soumis.set(false);
    this.erreursServeur.set({});
  }

  private demanderJeton(): void {
    this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }
}
