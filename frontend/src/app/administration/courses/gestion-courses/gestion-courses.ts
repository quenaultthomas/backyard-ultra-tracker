import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs';

import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { ErreursFormulaire, interpreterErreurFormulaire } from '../../../comptes/erreurs-compte';
import { CsrfService } from '../../../partage/csrf.service';
import { AdministrationApiService } from '../../administration-api.service';
import {
  CHAMPS_COURSE,
  CHAMPS_NOMBRE,
  ChampCourse,
  SaisieCourse,
  controlerSaisieCourse,
  versRequeteDeclaration,
} from '../champs-course';
import { CourseReponse } from '../course';
import { ListeCourses } from '../liste-courses/liste-courses';

const ECRAN = '/administration/courses';

/** Liste des Courses et déclaration d'une Course (admins et admin master). */
@Component({
  selector: 'app-gestion-courses',
  imports: [ReactiveFormsModule, RouterLink, ListeCourses],
  templateUrl: './gestion-courses.html',
  styleUrls: [
    '../../../partage/page-carte.css',
    '../../../comptes/formulaire-compte.css',
    './gestion-courses.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GestionCourses implements OnInit {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly champsNombre = CHAMPS_NOMBRE;
  protected readonly formulaire = this.fb.group({
    nom: '',
    date: '',
    distanceBoucleMetres: this.fb.control<number | null>(null),
    dureeBoucleMinutes: this.fb.control<number | null>(null),
    denivelePositifBoucleMetres: this.fb.control<number | null>(null),
    nombreMaxParticipants: this.fb.control<number | null>(null),
    nombreMaxBoucles: this.fb.control<number | null>(null),
  });

  /** `null` tant que la liste n'est pas chargée. */
  protected readonly courses = signal<CourseReponse[] | null>(null);
  protected readonly erreurChargement = signal(false);
  protected readonly envoiEnCours = signal(false);
  protected readonly nomCourseDeclaree = signal<string | null>(null);
  private readonly soumis = signal(false);
  private readonly erreursServeur = signal<ErreursFormulaire<ChampCourse>>({});
  private readonly saisie = toSignal(
    this.formulaire.valueChanges.pipe(map((): SaisieCourse => this.formulaire.getRawValue())),
    { initialValue: this.formulaire.getRawValue() },
  );

  /** Contrôles de confort (après une première validation), prioritaires sur ceux du serveur. */
  protected readonly erreurs = computed<ErreursFormulaire<ChampCourse>>(() => ({
    ...this.erreursServeur(),
    ...(this.soumis() ? controlerSaisieCourse(this.saisie()) : {}),
  }));

  ngOnInit(): void {
    this.demanderJeton();
    this.chargerCourses();
  }

  protected soumettre(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.erreursServeur.set({});
    this.nomCourseDeclaree.set(null);
    this.soumis.set(true);
    const saisie = this.formulaire.getRawValue();
    if (Object.keys(controlerSaisieCourse(saisie)).length > 0) {
      return;
    }
    this.envoiEnCours.set(true);
    this.administrationApi
      .declarerCourse(versRequeteDeclaration(saisie))
      .pipe(
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (course) => this.confirmerDeclaration(course),
        error: (erreur: unknown) => this.traiterErreurDeclaration(erreur),
      });
  }

  private chargerCourses(): void {
    this.administrationApi
      .listerCourses()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (courses) => {
          this.courses.set(courses);
          this.erreurChargement.set(false);
        },
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, ECRAN)) {
            this.erreurChargement.set(true);
          }
        },
      });
  }

  private confirmerDeclaration(course: CourseReponse): void {
    this.nomCourseDeclaree.set(course.nom);
    this.soumis.set(false);
    this.formulaire.reset();
    this.chargerCourses();
  }

  private traiterErreurDeclaration(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN)) {
      return;
    }
    const erreurs = interpreterErreurFormulaire(erreur, {}, CHAMPS_COURSE);
    this.erreursServeur.set(erreurs);
    if (erreurs.jetonExpire) {
      this.demanderJeton();
    }
  }

  private demanderJeton(): void {
    this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }
}
