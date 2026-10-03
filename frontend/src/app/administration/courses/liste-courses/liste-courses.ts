import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { SessionService } from '../../../comptes/session.service';
import { CHAMPS_NOMBRE, formaterDateCourse } from '../champs-course';
import { CourseReponse, LIBELLES_STATUT_COURSE } from '../course';
import { ECRAN_GESTION_COURSES } from '../erreurs-course';
import { LogoCourse } from '../logo-course/logo-course';
import { CourseRetiree, SuppressionCourse } from '../suppression-course/suppression-course';

/** Liste des Courses, dans l'ordre renvoyé par l'API (le front ne retrie pas). */
@Component({
  selector: 'app-liste-courses',
  imports: [RouterLink, LogoCourse, SuppressionCourse],
  templateUrl: './liste-courses.html',
  styleUrls: ['../action-ligne.css', './liste-courses.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ListeCourses {
  readonly courses = input.required<readonly CourseReponse[]>();
  /** Demande de modification d'une Course (bouton affiché selon le statut renvoyé par l'API). */
  readonly modifier = output<CourseReponse>();
  /** Logo envoyé, supprimé ou refusé : liste à recharger, avec le message de succès éventuel. */
  readonly actualiser = output<string | null>();
  /** Course supprimée ou déjà absente : ligne à retirer et liste à recharger. */
  readonly retirer = output<CourseRetiree>();

  /** Aide d'affichage : l'API réserve la suppression d'une Course à l'admin master. */
  protected readonly peutSupprimer = inject(SessionService).estAdminMaster;

  protected readonly ecranGestion = ECRAN_GESTION_COURSES;
  protected readonly champsNombre = CHAMPS_NOMBRE;
  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly formaterDate = formaterDateCourse;
  /** Course dont le bloc logo est actif : un seul aperçu ou message de logo à la fois. */
  protected readonly logoActif = signal<string | null>(null);
  /** Course dont la confirmation de suppression est ouverte : une seule à la fois. */
  protected readonly suppressionOuverte = signal<string | null>(null);
}
