import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { formaterDateCourse } from '../../administration/courses/champs-course';
import { CoursePubliqueReponse } from '../courses-publiques';

/** Rubrique de l'accueil public (en cours, à venir, terminées) : cartes de Course dans l'ordre de l'API. */
@Component({
  selector: 'app-rubrique-courses',
  imports: [RouterLink],
  templateUrl: './rubrique-courses.html',
  styleUrl: './rubrique-courses.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RubriqueCourses {
  /** Suffixe des `data-testid` : `en-cours`, `a-venir`, `terminees`. */
  readonly cle = input.required<string>();
  readonly titre = input.required<string>();
  readonly messageVide = input.required<string>();
  readonly courses = input.required<readonly CoursePubliqueReponse[]>();

  protected readonly formaterDate = formaterDateCourse;
  /** Adresses de logo qui n'ont pas pu être chargées : visuel de substitution à la place. */
  private readonly logosEnErreur = signal<ReadonlySet<string>>(new Set());

  protected logoAffiche(course: CoursePubliqueReponse): string | null {
    const url = course.logoUrl;
    return url !== null && !this.logosEnErreur().has(url) ? url : null;
  }

  protected signalerLogoEnErreur(url: string): void {
    this.logosEnErreur.update((urls) => new Set(urls).add(url));
  }
}
