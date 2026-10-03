import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
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
  versRequeteCourse,
  versSaisieCourse,
} from '../champs-course';
import { CourseReponse } from '../course';
import {
  ECRAN_GESTION_COURSES,
  ERREURS_COURSE_NON_EDITABLE,
  estCourseNonEditable,
} from '../erreurs-course';
import { ListeCourses } from '../liste-courses/liste-courses';

/** Liste, déclaration et modification des Courses (admins et admin master). */
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
  private readonly champNom = viewChild.required<ElementRef<HTMLInputElement>>('champNom');

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
  /** Course en cours de modification ; `null` en mode déclaration. */
  protected readonly courseEditee = signal<CourseReponse | null>(null);
  protected readonly messageSucces = signal<string | null>(null);
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

  /** Passe le formulaire en mode édition, pré-rempli depuis la ligne de la liste. */
  protected editer(course: CourseReponse): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.revenirEnDeclaration();
    this.messageSucces.set(null);
    this.courseEditee.set(course);
    this.formulaire.setValue(versSaisieCourse(course));
    this.champNom().nativeElement.focus();
  }

  protected annuler(): void {
    this.revenirEnDeclaration();
  }

  protected soumettre(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.erreursServeur.set({});
    this.messageSucces.set(null);
    this.soumis.set(true);
    const saisie = this.formulaire.getRawValue();
    if (Object.keys(controlerSaisieCourse(saisie)).length > 0) {
      return;
    }
    const requete = versRequeteCourse(saisie);
    const courseEditee = this.courseEditee();
    const envoi =
      courseEditee === null
        ? this.administrationApi.declarerCourse(requete)
        : this.administrationApi.modifierCourse(courseEditee.id, requete);
    const action = courseEditee === null ? 'déclarée' : 'modifiée';
    this.envoiEnCours.set(true);
    envoi
      .pipe(
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ nom }) => this.confirmer(`La course ${nom} a été ${action}.`),
        error: (erreur: unknown) => this.traiterErreurEnvoi(erreur),
      });
  }

  /**
   * Après une action sur un logo : message de succès (ou effacement de l'ancien) et liste
   * rechargée. Le formulaire, en déclaration comme en édition, n'est pas touché.
   */
  protected actualiserApresLogo(messageSucces: string | null): void {
    this.messageSucces.set(messageSucces);
    this.chargerCourses();
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
          if (!this.refusAcces.rediriger(erreur, ECRAN_GESTION_COURSES)) {
            this.erreurChargement.set(true);
          }
        },
      });
  }

  private confirmer(message: string): void {
    this.revenirEnDeclaration();
    this.messageSucces.set(message);
    this.chargerCourses();
  }

  private traiterErreurEnvoi(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN_GESTION_COURSES)) {
      return;
    }
    const erreurs = interpreterErreurFormulaire(erreur, ERREURS_COURSE_NON_EDITABLE, CHAMPS_COURSE);
    // La Course n'est plus éditable : le mode édition est quitté.
    if (estCourseNonEditable(erreur)) {
      this.revenirEnDeclaration();
      this.chargerCourses();
    }
    this.erreursServeur.set(erreurs);
    if (erreurs.jetonExpire) {
      this.demanderJeton();
    }
  }

  /** Mode déclaration, formulaire vidé et messages d'erreur effacés. */
  private revenirEnDeclaration(): void {
    this.courseEditee.set(null);
    this.soumis.set(false);
    this.erreursServeur.set({});
    this.formulaire.reset();
  }

  private demanderJeton(): void {
    this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }
}
