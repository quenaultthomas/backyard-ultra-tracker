import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { CHAMPS_NOMBRE, formaterDateCourse } from '../champs-course';
import { CourseReponse, LIBELLES_STATUT_COURSE } from '../course';

/** Liste des Courses, dans l'ordre renvoyé par l'API (le front ne retrie pas). */
@Component({
  selector: 'app-liste-courses',
  templateUrl: './liste-courses.html',
  styleUrl: './liste-courses.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ListeCourses {
  readonly courses = input.required<readonly CourseReponse[]>();

  protected readonly champsNombre = CHAMPS_NOMBRE;
  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly formaterDate = formaterDateCourse;
}
