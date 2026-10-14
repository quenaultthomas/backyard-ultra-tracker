import { DatePipe } from '@angular/common';
import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { RefusAccesService } from '../../../comptes/refus-acces.service';
import {
  PASTILLES_STATUT_INSCRIPTION,
  LIBELLES_STATUT_INSCRIPTION,
} from '../../../inscriptions/mon-inscription';
import { MESSAGE_SERVICE_INDISPONIBLE } from '../../../partage/probleme';
import { LIBELLES_STATUT_COURSE, PASTILLES_STATUT_COURSE } from '../course';
import {
  ECRAN_GESTION_COURSES,
  ETAT_MESSAGE_ERREUR,
  MESSAGE_COURSE_INTROUVABLE,
} from '../erreurs-course';
import { LIBELLES_MOTIF_ABANDON, PASTILLES_BOUCLAGE } from '../pilotage-course';
import { formaterCompteARebours, formaterDureeEcoulee } from './formats-duree';
import { RafraichissementPilotage } from './rafraichissement-pilotage';

/**
 * Pilotage d'une Course démarrée (admins et admin master) : Boucle courante, compte à rebours,
 * durée écoulée, inscrits `EN_COURSE` et leur bouclage, inscrits en abandon, tels que calculés
 * par le serveur.
 */
@Component({
  selector: 'app-ecran-pilotage',
  imports: [RouterLink, DatePipe],
  providers: [RafraichissementPilotage],
  templateUrl: './ecran-pilotage.html',
  styleUrls: [
    '../../../partage/page-carte.css',
    '../../../comptes/formulaire-compte.css',
    '../entete-course.css',
    '../fiche/inscrits-course/inscrits-course.css',
    './ecran-pilotage.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EcranPilotage implements OnInit {
  private readonly refusAcces = inject(RefusAccesService);
  private readonly router = inject(Router);
  protected readonly rafraichissement = inject(RafraichissementPilotage);

  /** Paramètre de route `:id`. */
  readonly id = input.required<string>();

  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly pastillesStatut = PASTILLES_STATUT_COURSE;
  protected readonly libellesInscription = LIBELLES_STATUT_INSCRIPTION;
  protected readonly pastillesInscription = PASTILLES_STATUT_INSCRIPTION;
  protected readonly messageIndisponible = MESSAGE_SERVICE_INDISPONIBLE;
  protected readonly pastillesBouclage = PASTILLES_BOUCLAGE;
  protected readonly libellesMotif = LIBELLES_MOTIF_ABANDON;

  protected readonly pilotage = this.rafraichissement.instantane;
  /** 409 `COURSE_NON_DEMARREE`. */
  protected readonly nonDemarree = signal(false);

  protected readonly compteARebours = computed(() => {
    const reste = this.rafraichissement.tempsAvantFinBoucle();
    return reste === null ? '' : formaterCompteARebours(reste);
  });

  protected readonly dureeEcoulee = computed(() => {
    const ecoulee = this.rafraichissement.dureeEcoulee();
    return ecoulee === null ? '' : formaterDureeEcoulee(ecoulee);
  });

  protected readonly compteur = computed(() => {
    const pilotage = this.pilotage();
    if (pilotage === null) {
      return '';
    }
    const enCourse = pilotage.inscritsEnCourse.length;
    const inscrits = pilotage.nombreInscrits;
    return (
      `${enCourse} ${enCourse > 1 ? 'coureurs' : 'coureur'} en course ` +
      `sur ${inscrits} ${inscrits > 1 ? 'inscrits' : 'inscrit'}`
    );
  });

  protected readonly compteurAbandons = computed(() => {
    const abandons = this.pilotage()?.nombreAbandons ?? 0;
    return `${abandons} ${abandons > 1 ? 'coureurs' : 'coureur'} en abandon`;
  });

  ngOnInit(): void {
    this.rafraichissement.demarrer(this.id(), (erreur) => this.traiterRefus(erreur));
  }

  protected ficheCourse(): string {
    return `${ECRAN_GESTION_COURSES}/${this.id()}`;
  }

  private traiterRefus(erreur: HttpErrorResponse): void {
    if (this.refusAcces.rediriger(erreur, `${this.ficheCourse()}/pilotage`)) {
      return;
    }
    if (erreur.status === HttpStatusCode.NotFound) {
      void this.router.navigateByUrl(ECRAN_GESTION_COURSES, {
        state: { [ETAT_MESSAGE_ERREUR]: MESSAGE_COURSE_INTROUVABLE },
      });
      return;
    }
    this.nonDemarree.set(true);
  }
}
