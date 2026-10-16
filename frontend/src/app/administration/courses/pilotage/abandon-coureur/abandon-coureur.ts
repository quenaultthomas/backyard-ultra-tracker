import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize, switchMap } from 'rxjs';

import { interpreterErreurFormulaire } from '../../../../comptes/erreurs-compte';
import { RefusAccesService } from '../../../../comptes/refus-acces.service';
import { CsrfService } from '../../../../partage/csrf.service';
import { lireProbleme } from '../../../../partage/probleme';
import { AdministrationApiService } from '../../../administration-api.service';
import {
  CODE_COURSE_INTROUVABLE,
  ECRAN_GESTION_COURSES,
  ETAT_MESSAGE_ERREUR,
  MESSAGE_COURSE_INTROUVABLE,
} from '../../erreurs-course';
import {
  AbandonManuel,
  InscritEnCourse,
  LIBELLES_MOTIF_ABANDON,
  MOTIFS_ABANDON_MANUEL,
  MotifAbandonManuel,
} from '../../pilotage-course';
import { RafraichissementPilotage } from '../rafraichissement-pilotage';

const MESSAGE_PLUS_EN_COURSE = "Ce coureur n'est plus en course.";

/** Refus du serveur qui ferment le panneau ; `rafraichir` : un instantané est redemandé aussitôt. */
const REFUS_ABANDON: Readonly<Record<string, { message: string; rafraichir: boolean }>> = {
  INSCRIPTION_NON_EN_COURSE: { message: MESSAGE_PLUS_EN_COURSE, rafraichir: true },
  COURSE_TERMINEE: {
    message: "La course est terminée : aucun abandon n'est possible.",
    rafraichir: true,
  },
  COURSE_NON_DEMARREE: { message: "La course n'est pas démarrée.", rafraichir: true },
  INSCRIPTION_INTROUVABLE: { message: 'Ce coureur est introuvable.', rafraichir: false },
};

/**
 * Abandon manuel depuis le pilotage : un seul panneau de confirmation en ligne (hors du tableau,
 * il survit aux rafraîchissements), motif obligatoire, puis messages de résultat. Le serveur
 * décide de tout (éligibilité, Boucle d'abandon, fin de Course) ; l'avertissement « dernier
 * coureur » n'est qu'une indication tirée de la liste affichée.
 */
@Component({
  selector: 'app-abandon-coureur',
  imports: [ReactiveFormsModule],
  templateUrl: './abandon-coureur.html',
  styleUrls: ['../../../../partage/confirmation-en-ligne.css', './abandon-coureur.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Cible du focus quand le panneau se ferme sur un résultat (la ligne du coureur disparaît).
  host: { tabindex: '-1' },
})
export class AbandonCoureur {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly rafraichissement = inject(RafraichissementPilotage);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly titre = viewChild<ElementRef<HTMLElement>>('titre');

  readonly courseId = input.required<string>();

  /** Inscription visée par le panneau ouvert, `null` panneau fermé. */
  readonly cibleId = signal<string | null>(null);
  readonly envoiEnCours = signal(false);

  protected readonly motifs = MOTIFS_ABANDON_MANUEL;
  protected readonly libellesMotif = LIBELLES_MOTIF_ABANDON;
  protected readonly formulaire = new FormGroup({
    motif: new FormControl<MotifAbandonManuel | null>(null, Validators.required),
  });
  protected readonly soumis = signal(false);
  protected readonly erreurMotifServeur = signal<string | null>(null);
  protected readonly succes = signal<string | null>(null);
  protected readonly erreur = signal<string | null>(null);

  private readonly inscritsEnCourse = computed(
    () => this.rafraichissement.instantane()?.inscritsEnCourse ?? [],
  );
  protected readonly cible = computed(() =>
    this.inscritsEnCourse().find(({ inscriptionId }) => inscriptionId === this.cibleId()),
  );
  protected readonly dernier = computed(() => this.inscritsEnCourse().length === 1);

  private boutonOrigine: HTMLElement | null = null;

  constructor() {
    // Coureur visé absent d'un nouvel instantané (abandon automatique, fin de Course).
    effect(() => {
      if (this.cibleId() !== null && this.cible() === undefined && !this.envoiEnCours()) {
        this.fermerSurResultat({ erreur: MESSAGE_PLUS_EN_COURSE });
      }
    });
  }

  /** Ouvre le panneau pour `inscriptionId`, sans appel réseau ; le focus va sur son titre. */
  ouvrir(inscriptionId: string, bouton: HTMLElement): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.boutonOrigine = bouton;
    this.formulaire.reset();
    this.soumis.set(false);
    this.erreurMotifServeur.set(null);
    this.succes.set(null);
    this.erreur.set(null);
    this.cibleId.set(inscriptionId);
    afterNextRender(() => this.titre()?.nativeElement.focus(), { injector: this.injector });
  }

  /** « Annuler » ou Échap : fermé sans appel, focus rendu au bouton « Abandonner » de la ligne. */
  protected annuler(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.cibleId.set(null);
    this.boutonOrigine?.focus();
  }

  /** Motif requis, puis nouveau jeton CSRF et `POST` ; un seul envoi à la fois. */
  protected confirmer(): void {
    const cible = this.cible();
    const motif = this.formulaire.controls.motif.value;
    if (this.envoiEnCours() || cible === undefined) {
      return;
    }
    this.soumis.set(true);
    this.erreurMotifServeur.set(null);
    this.erreur.set(null);
    if (motif === null) {
      return;
    }
    this.envoiEnCours.set(true);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() =>
          this.administrationApi.abandonnerCoureur(this.courseId(), cible.inscriptionId, motif),
        ),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (abandon) => this.traiterSucces(abandon, cible),
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  protected erreurMotif(): string | null {
    const manquant = this.soumis() && this.formulaire.controls.motif.value === null;
    return manquant ? "Choisissez un motif d'abandon." : this.erreurMotifServeur();
  }

  private traiterSucces(abandon: AbandonManuel, cible: InscritEnCourse): void {
    const fin =
      abandon.statutCourse === 'TERMINEE'
        ? ' La course est terminée : plus aucun coureur en course.'
        : '';
    this.fermerSurResultat({
      succes: `Abandon enregistré : coureur n° ${abandon.dossard} ${cible.pseudo}.${fin}`,
    });
    this.rafraichissement.actualiserMaintenant();
  }

  /** Redirections (401, 403, Course introuvable), refus qui ferment, sinon panneau conservé. */
  private traiterErreur(erreur: unknown): void {
    const ecran = `${ECRAN_GESTION_COURSES}/${this.courseId()}/pilotage`;
    if (this.refusAcces.rediriger(erreur, ecran)) {
      return;
    }
    const code = lireProbleme(erreur)?.code;
    if (code === CODE_COURSE_INTROUVABLE) {
      void this.router.navigateByUrl(ECRAN_GESTION_COURSES, {
        state: { [ETAT_MESSAGE_ERREUR]: MESSAGE_COURSE_INTROUVABLE },
      });
      return;
    }
    if (code !== undefined && Object.hasOwn(REFUS_ABANDON, code)) {
      const { message, rafraichir } = REFUS_ABANDON[code];
      this.fermerSurResultat({ erreur: message });
      if (rafraichir) {
        this.rafraichissement.actualiserMaintenant();
      }
      return;
    }
    const { motif, generale, jetonExpire } = interpreterErreurFormulaire(erreur, {}, ['motif']);
    this.erreurMotifServeur.set(motif ?? null);
    this.erreur.set(generale ?? null);
    if (jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }

  /** Panneau fermé avec un message ; le focus, s'il était dans le panneau, va sur ce message. */
  private fermerSurResultat(resultat: { succes?: string; erreur?: string }): void {
    const focusDansPanneau = this.hote.nativeElement.contains(document.activeElement);
    this.cibleId.set(null);
    this.succes.set(resultat.succes ?? null);
    this.erreur.set(resultat.erreur ?? null);
    if (focusDansPanneau) {
      afterNextRender(() => this.hote.nativeElement.focus(), { injector: this.injector });
    }
  }
}
