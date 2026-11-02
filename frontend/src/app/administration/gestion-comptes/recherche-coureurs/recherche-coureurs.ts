import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import {
  Observable,
  Subject,
  catchError,
  distinctUntilChanged,
  map,
  merge,
  of,
  startWith,
  switchMap,
  timer,
} from 'rxjs';

import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { FormulaireALaDemande } from '../../../partage/formulaire-a-la-demande';
import { AdministrationApiService } from '../../administration-api.service';
import { RechercheCoureursReponse } from '../../recherche-coureurs';
import { PromotionComptesGeres } from '../comptes-geres';

/** Contrôle d'affichage seulement : le serveur valide la recherche (3 à 30 caractères). */
const LONGUEUR_MIN_RECHERCHE = 3;
const DELAI_SAISIE_MS = 300;

type EtatRecherche =
  | { readonly nature: 'repos' | 'chargement' | 'erreur' }
  | { readonly nature: 'resultats'; readonly reponse: RechercheCoureursReponse };

/** Fragment rogné à rechercher ; `immediate` : sans attendre la fin de la frappe (Entrée, fermeture). */
interface Demande {
  readonly fragment: string;
  readonly immediate: boolean;
}

const REPOS: EtatRecherche = { nature: 'repos' };
const CHARGEMENT: EtatRecherche = { nature: 'chargement' };
const ERREUR: EtatRecherche = { nature: 'erreur' };

/**
 * Bouton « Promouvoir un coureur » et panneau de recherche d'un Compte coureur par pseudo,
 * ouvert à la demande. Une seule recherche compte à la fois : toute nouvelle saisie, le passage
 * sous 3 caractères ou la fermeture annule la précédente et en ignore la réponse.
 */
@Component({
  selector: 'app-recherche-coureurs',
  imports: [ReactiveFormsModule],
  templateUrl: './recherche-coureurs.html',
  styleUrls: [
    '../../../comptes/formulaire-compte.css',
    '../../../partage/formulaire-a-la-demande.css',
    './recherche-coureurs.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RechercheCoureurs {
  readonly promotion = input.required<PromotionComptesGeres>();
  /** Préfixe des `data-testid` (`benevole`, `admin`). */
  readonly prefixe = input.required<string>();
  /** Route de l'écran, pour le retour après connexion. */
  readonly ecran = input.required<string>();

  private readonly administrationApi = inject(AdministrationApiService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly boutonPromouvoir =
    viewChild.required<ElementRef<HTMLButtonElement>>('boutonPromouvoir');
  private readonly champRecherche =
    viewChild.required<ElementRef<HTMLInputElement>>('champRecherche');
  protected readonly ouverture = new FormulaireALaDemande(
    this.boutonPromouvoir,
    this.champRecherche,
  );

  protected readonly recherche = new FormControl('', { nonNullable: true });
  protected readonly etat = signal<EtatRecherche>(REPOS);
  private readonly demandesImmediates = new Subject<Demande>();

  constructor() {
    const frappes = this.recherche.valueChanges.pipe(
      map((saisie): Demande => ({ fragment: saisie.trim(), immediate: false })),
    );
    merge(frappes, this.demandesImmediates)
      .pipe(
        distinctUntilChanged(
          (avant, apres) => !apres.immediate && avant.fragment === apres.fragment,
        ),
        switchMap((demande) => this.rechercher(demande)),
        takeUntilDestroyed(),
      )
      .subscribe((etat) => this.etat.set(etat));
  }

  protected ouvrir(): void {
    this.ouverture.ouvrir();
  }

  /** Entrée : recherche sans attendre la fin de la frappe, y compris pour relancer après une erreur. */
  protected rechercherMaintenant(evenement: Event): void {
    evenement.preventDefault();
    this.demandesImmediates.next({ fragment: this.recherche.value.trim(), immediate: true });
  }

  /** Panneau masqué, champ vidé, recherche en cours annulée, focus rendu au bouton. */
  protected fermer(): void {
    this.demandesImmediates.next({ fragment: '', immediate: true });
    this.recherche.reset('', { emitEvent: false });
    this.ouverture.fermer();
  }

  private rechercher({ fragment, immediate }: Demande): Observable<EtatRecherche> {
    if ([...fragment].length < LONGUEUR_MIN_RECHERCHE) {
      return of(REPOS);
    }
    const appel = this.administrationApi.rechercherCoureurs(fragment).pipe(
      map((reponse): EtatRecherche => ({ nature: 'resultats', reponse })),
      catchError((erreur: unknown) =>
        of(this.refusAcces.rediriger(erreur, this.ecran()) ? REPOS : ERREUR),
      ),
      startWith(CHARGEMENT),
    );
    return immediate ? appel : timer(DELAI_SAISIE_MS).pipe(switchMap(() => appel));
  }
}
