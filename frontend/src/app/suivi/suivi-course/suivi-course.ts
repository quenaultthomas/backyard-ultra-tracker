import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  ElementRef,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';

import {
  LIBELLES_STATUT_COURSE,
  PASTILLES_STATUT_COURSE,
} from '../../administration/courses/course';
import {
  formaterCompteARebours,
  formaterDureeEcoulee,
} from '../../partage/chronometrage/formats-duree';
import { MESSAGE_SERVICE_INDISPONIBLE } from '../../partage/probleme';
import { CoureursSuivi } from '../coureurs-suivi/coureurs-suivi';
import { ProjectionSuivi } from '../projection-suivi';
import { RafraichissementSuivi } from '../rafraichissement-suivi';
import { StatistiquesSuivi } from '../statistiques-suivi/statistiques-suivi';
import { SuiviCourse as InstantaneSuivi } from '../suivi-course';

/**
 * Suivi public d'une Course (sans connexion) : nom, logo, statut, Boucle courante, compte à
 * rebours et durée écoulée, tels que calculés par le serveur et avancés localement entre deux
 * réponses ; vainqueur(s), coureurs en course et abandons d'une Course démarrée. L'actualisation
 * s'arrête après la première réponse `TERMINEE`, affichée en entier (listes et statistiques
 * finales, durée de la course figée). Mode projection : plein écran de la page ou affichage agrandi.
 */
@Component({
  selector: 'app-suivi-course',
  imports: [CoureursSuivi, StatistiquesSuivi],
  providers: [RafraichissementSuivi, ProjectionSuivi],
  templateUrl: './suivi-course.html',
  styleUrls: [
    '../../partage/page-carte.css',
    '../../administration/courses/entete-course.css',
    '../../partage/chronometrage/chronometrage.css',
    './suivi-course.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SuiviCourse implements OnInit {
  protected readonly rafraichissement = inject(RafraichissementSuivi);
  protected readonly projection = inject(ProjectionSuivi);
  private readonly racine = viewChild.required<ElementRef<HTMLElement>>('racine');

  /** Paramètre de route `:id`. */
  readonly id = input.required<string>();

  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly pastillesStatut = PASTILLES_STATUT_COURSE;
  protected readonly messageIndisponible = MESSAGE_SERVICE_INDISPONIBLE;

  protected readonly suivi = this.rafraichissement.instantane;
  /**
   * Annonce de fin, posée seulement quand l'instantané affiché passe de `EN_COURS` à `TERMINEE`
   * (rien à l'ouverture d'une Course déjà terminée) ; une fois posée, elle reste.
   */
  protected readonly annonceFin = linkedSignal<InstantaneSuivi | null, string>({
    source: this.suivi,
    computation: (suivant, precedent) =>
      precedent?.value ||
      (precedent?.source?.statut === 'EN_COURS' && suivant?.statut === 'TERMINEE'
        ? annoncerFin(suivant)
        : ''),
  });

  /** 404 `COURSE_INTROUVABLE` : actualisation arrêtée. */
  protected readonly introuvable = signal(false);
  /** Adresse du logo qui n'a pas pu être chargée : visuel de substitution à la place. */
  private readonly logoEnErreur = signal<string | null>(null);

  protected readonly logoAffiche = computed(() => {
    const url = this.suivi()?.logoUrl ?? null;
    return url !== null && url !== this.logoEnErreur() ? url : null;
  });

  protected readonly compteARebours = computed(() => {
    const reste = this.rafraichissement.tempsAvantFinBoucle();
    return reste === null ? '' : formaterCompteARebours(reste);
  });

  protected readonly dureeEcoulee = computed(() => {
    const ecoulee = this.rafraichissement.dureeEcoulee();
    return ecoulee === null ? '' : formaterDureeEcoulee(ecoulee);
  });

  /** Durée figée d'une Course `TERMINEE`, telle que renvoyée par le serveur. */
  protected readonly dureeFinale = computed(() => {
    const duree = this.suivi()?.dureeFinaleMillisecondes ?? null;
    return duree === null ? '' : formaterDureeEcoulee(duree);
  });

  ngOnInit(): void {
    this.rafraichissement.demarrer(this.id(), () => this.introuvable.set(true));
  }

  protected basculerProjection(): void {
    this.projection.basculer(this.racine().nativeElement);
  }

  protected signalerLogoEnErreur(url: string): void {
    this.logoEnErreur.set(url);
  }
}

/** « Course terminée. Vainqueur : Alice. », « … Vainqueurs : Alice, Bruno. » ou sans vainqueur. */
function annoncerFin(suivi: InstantaneSuivi): string {
  const pseudos = suivi.vainqueurs.map((vainqueur) => vainqueur.pseudo);
  if (pseudos.length === 0) {
    return 'Course terminée sans vainqueur.';
  }
  const libelle = pseudos.length > 1 ? 'Vainqueurs' : 'Vainqueur';
  return `Course terminée. ${libelle} : ${pseudos.join(', ')}.`;
}
