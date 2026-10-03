import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { CHAMPS_NOMBRE, formaterDateCourse } from '../champs-course';
import { CourseReponse, LIBELLES_STATUT_COURSE } from '../course';
import { LogoCourse } from '../logo-course/logo-course';

/** Liste des Courses, dans l'ordre renvoyé par l'API (le front ne retrie pas). */
@Component({
  selector: 'app-liste-courses',
  imports: [LogoCourse],
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

  protected readonly champsNombre = CHAMPS_NOMBRE;
  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly formaterDate = formaterDateCourse;
  /** Course dont le bloc logo est actif : un seul aperçu ou message de logo à la fois. */
  protected readonly logoActif = signal<string | null>(null);
}
