import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { CoursesPubliquesApiService } from './courses-publiques-api.service';
import { CoursesPubliquesReponse } from './courses-publiques';
import { RubriqueCourses } from './rubrique-courses/rubrique-courses';

/**
 * Accueil public, identique pour tous : titre et accroche sur le décor backyard, puis les Courses
 * en cours, à venir et terminées. Chargées une fois à l'ouverture, sans rafraîchissement automatique.
 */
@Component({
  selector: 'app-accueil',
  imports: [RubriqueCourses],
  templateUrl: './accueil.html',
  styleUrl: './accueil.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Accueil implements OnInit {
  private readonly coursesPubliquesApi = inject(CoursesPubliquesApiService);
  private readonly destroyRef = inject(DestroyRef);

  /** `null` pendant le chargement ou après une erreur. */
  protected readonly courses = signal<CoursesPubliquesReponse | null>(null);
  protected readonly erreur = signal(false);

  ngOnInit(): void {
    this.charger();
  }

  protected charger(): void {
    this.erreur.set(false);
    this.courses.set(null);
    this.coursesPubliquesApi
      .lister()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (courses) => this.courses.set(courses),
        error: () => this.erreur.set(true),
      });
  }
}
