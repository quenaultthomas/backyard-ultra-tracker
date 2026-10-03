import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { formaterDateCourse } from '../../administration/courses/champs-course';
import { LIBELLES_STATUT_COURSE } from '../../administration/courses/course';
import { RefusAccesService } from '../../comptes/refus-acces.service';
import { BenevoleApiService } from '../benevole-api.service';
import { CourseBenevoleReponse } from '../course-benevole';

const ECRAN_ACCUEIL_BENEVOLE = '/benevole';

/** Accueil d'un bénévole : Courses auxquelles il est affecté (aucune action avant le scan). */
@Component({
  selector: 'app-accueil-benevole',
  templateUrl: './accueil-benevole.html',
  styleUrls: ['../../partage/page-carte.css', './accueil-benevole.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccueilBenevole implements OnInit {
  private readonly benevoleApi = inject(BenevoleApiService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly formaterDate = formaterDateCourse;
  /** `null` tant que la liste n'est pas chargée. */
  protected readonly courses = signal<CourseBenevoleReponse[] | null>(null);
  protected readonly erreurChargement = signal(false);

  ngOnInit(): void {
    this.benevoleApi
      .listerMesCourses()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (courses) => this.courses.set(courses),
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, ECRAN_ACCUEIL_BENEVOLE)) {
            this.erreurChargement.set(true);
          }
        },
      });
  }
}
