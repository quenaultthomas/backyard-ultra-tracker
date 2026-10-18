import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
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
import { RafraichissementSuivi } from '../rafraichissement-suivi';

/**
 * Suivi public d'une Course (sans connexion) : nom, logo, statut, Boucle courante, compte à
 * rebours et durée écoulée, tels que calculés par le serveur et avancés localement entre deux
 * réponses.
 */
@Component({
  selector: 'app-suivi-course',
  providers: [RafraichissementSuivi],
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

  /** Paramètre de route `:id`. */
  readonly id = input.required<string>();

  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly pastillesStatut = PASTILLES_STATUT_COURSE;
  protected readonly messageIndisponible = MESSAGE_SERVICE_INDISPONIBLE;

  protected readonly suivi = this.rafraichissement.instantane;
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

  ngOnInit(): void {
    this.rafraichissement.demarrer(this.id(), () => this.introuvable.set(true));
  }

  protected signalerLogoEnErreur(url: string): void {
    this.logoEnErreur.set(url);
  }
}
