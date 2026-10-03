import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, switchMap } from 'rxjs';

import { formaterDateCourse } from '../../administration/courses/champs-course';
import { MESSAGE_COURSE_INTROUVABLE } from '../../administration/courses/erreurs-course';
import { RefusAccesService } from '../../comptes/refus-acces.service';
import { CsrfService } from '../../partage/csrf.service';
import {
  MESSAGE_PAGE_EXPIREE,
  MESSAGE_SERVICE_INDISPONIBLE,
  lireProbleme,
} from '../../partage/probleme';
import { CoureurApiService } from '../coureur-api.service';
import { CourseOuverteReponse, InscriptionReponse } from '../course-ouverte';

const ECRAN_ACCUEIL_COUREUR = '/coureur';
const CODE_INSCRIPTION_DEJA_EXISTANTE = 'INSCRIPTION_DEJA_EXISTANTE';
const MESSAGE_DEJA_INSCRIT = 'Vous êtes déjà inscrit à cette course.';

/** Erreur d'inscription, rattachée à la ligne de la Course concernée. */
interface ErreurInscription {
  readonly courseId: string;
  readonly message: string;
}

/** Accueil d'un coureur : Courses ouvertes et inscription en un clic (le dossard vient de l'API). */
@Component({
  selector: 'app-accueil-coureur',
  templateUrl: './accueil-coureur.html',
  styleUrls: [
    '../../partage/page-carte.css',
    '../../administration/courses/action-ligne.css',
    './accueil-coureur.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccueilCoureur implements OnInit {
  private readonly coureurApi = inject(CoureurApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly formaterDate = formaterDateCourse;
  /** `null` tant que la liste n'est pas chargée. */
  protected readonly courses = signal<CourseOuverteReponse[] | null>(null);
  protected readonly erreurChargement = signal(false);
  /** Identifiant de la Course dont l'inscription est en cours d'envoi. */
  protected readonly envoiEnCours = signal<string | null>(null);
  protected readonly messageInscription = signal<string | null>(null);
  protected readonly erreur = signal<ErreurInscription | null>(null);
  /** Erreur dont la ligne a disparu après relecture (Course supprimée) : affichée au-dessus. */
  protected readonly erreurHorsListe = computed(() => {
    const erreur = this.erreur();
    const courses = this.courses() ?? [];
    return erreur !== null && !courses.some((course) => course.id === erreur.courseId)
      ? erreur.message
      : null;
  });

  ngOnInit(): void {
    this.charger();
  }

  /** Nouveau jeton CSRF puis `POST` ; un seul envoi à la fois (double clic sans effet). */
  protected sinscrire(course: CourseOuverteReponse): void {
    if (this.envoiEnCours() !== null) {
      return;
    }
    this.envoiEnCours.set(course.id);
    this.erreur.set(null);
    this.messageInscription.set(null);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => this.coureurApi.sinscrire(course.id)),
        finalize(() => this.envoiEnCours.set(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (inscription) => this.afficherInscription(course, inscription),
        error: (erreur: unknown) => this.traiterErreur(course.id, erreur),
      });
  }

  /** « 6706 m · 60 min · 120 m D+ » */
  protected parametres(course: CourseOuverteReponse): string {
    return `${course.distanceBoucleMetres} m · ${course.dureeBoucleMinutes} min · ${course.denivelePositifBoucleMetres} m D+`;
  }

  /** « 24 boucles max · 50 participants max » */
  protected limites(course: CourseOuverteReponse): string {
    return `${course.nombreMaxBoucles} boucles max · ${course.nombreMaxParticipants} participants max`;
  }

  protected erreurDeLaLigne(courseId: string): string | null {
    const erreur = this.erreur();
    return erreur?.courseId === courseId ? erreur.message : null;
  }

  private charger(): void {
    this.coureurApi
      .listerCoursesOuvertes()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (courses) => {
          this.erreurChargement.set(false);
          this.courses.set(courses);
        },
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, ECRAN_ACCUEIL_COUREUR)) {
            this.erreurChargement.set(true);
          }
        },
      });
  }

  private afficherInscription(course: CourseOuverteReponse, inscription: InscriptionReponse): void {
    this.courses.update((courses) =>
      (courses ?? []).map((c) => (c.id === course.id ? { ...c, monInscription: inscription } : c)),
    );
    this.messageInscription.set(
      `Vous êtes inscrit à ${course.nom} avec le dossard ${inscription.dossard}.`,
    );
  }

  private traiterErreur(courseId: string, erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN_ACCUEIL_COUREUR)) {
      return;
    }
    const code = lireProbleme(erreur)?.code;
    if (code === CODE_INSCRIPTION_DEJA_EXISTANTE) {
      this.erreur.set({ courseId, message: MESSAGE_DEJA_INSCRIT });
      this.charger();
    } else if (erreur instanceof HttpErrorResponse && erreur.status === HttpStatusCode.NotFound) {
      this.erreur.set({ courseId, message: MESSAGE_COURSE_INTROUVABLE });
      this.charger();
    } else if (code === 'CSRF_INVALIDE') {
      this.erreur.set({ courseId, message: MESSAGE_PAGE_EXPIREE });
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    } else {
      this.erreur.set({ courseId, message: MESSAGE_SERVICE_INDISPONIBLE });
    }
  }
}
