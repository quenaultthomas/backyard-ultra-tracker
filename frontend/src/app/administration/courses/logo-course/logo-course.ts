import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, finalize, map, switchMap } from 'rxjs';

import { ErreursSpecifiques, interpreterErreurFormulaire } from '../../../comptes/erreurs-compte';
import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { CsrfService } from '../../../partage/csrf.service';
import { MESSAGE_SERVICE_INDISPONIBLE } from '../../../partage/probleme';
import { AdministrationApiService } from '../../administration-api.service';
import { ChampCourse } from '../champs-course';
import { CourseReponse } from '../course';
import {
  ECRAN_GESTION_COURSES,
  ERREURS_COURSE_NON_EDITABLE,
  estCourseNonEditable,
} from '../erreurs-course';
import { lireImage } from './lecture-image';

const MESSAGE_LOGO_TROP_VOLUMINEUX = 'Le logo ne doit pas dépasser 2 Mo.';
const MESSAGE_IMAGE_ILLISIBLE = "Ce fichier n'est pas une image lisible.";

const ERREURS_LOGO: ErreursSpecifiques<ChampCourse> = {
  ...ERREURS_COURSE_NON_EDITABLE,
  LOGO_TROP_VOLUMINEUX: { generale: MESSAGE_LOGO_TROP_VOLUMINEUX },
  LOGO_FORMAT_INVALIDE: { generale: 'Le logo doit être une image PNG, JPEG ou WebP.' },
  LOGO_REQUIS: { generale: 'Le fichier du logo est obligatoire.' },
};

/** Fichier choisi (panneau d'aperçu ouvert s'il est présent) et message d'erreur du bloc. */
interface EtatLogo {
  readonly fichier: File | null;
  /** URL `data:` de l'aperçu, `null` pendant la lecture du fichier. */
  readonly apercu: string | null;
  readonly erreur: string | null;
}

const ETAT_INITIAL: EtatLogo = { fichier: null, apercu: null, erreur: null };

/** Bloc logo d'une ligne de Course : vignette, choix avec aperçu, envoi et suppression. */
@Component({
  selector: 'app-logo-course',
  templateUrl: './logo-course.html',
  styleUrls: ['../action-ligne.css', './logo-course.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LogoCourse {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);

  readonly course = input.required<CourseReponse>();
  /** Faux quand un autre bloc logo de la liste est utilisé : aperçu et erreur sont masqués. */
  readonly actif = input(false);
  /** Ce bloc devient le seul actif de la liste. */
  readonly activer = output<void>();
  /** Liste à recharger, avec le message de succès éventuel. */
  readonly actualiser = output<string | null>();

  protected readonly envoiEnCours = signal(false);
  private readonly etat = signal<EtatLogo>(ETAT_INITIAL);
  /** État affiché : rien tant qu'un autre bloc de la liste est actif. */
  protected readonly etatAffiche = computed(() => (this.actif() ? this.etat() : ETAT_INITIAL));
  /** Le statut renvoyé par l'API décide de l'affichage des actions (l'API reste la référence). */
  protected readonly modifiable = computed(() => this.course().statut === 'EN_PREPARATION');

  protected choisir(champ: HTMLInputElement): void {
    const fichier = champ.files?.item(0) ?? null;
    // Vidé pour que choisir de nouveau le même fichier soit pris en compte.
    champ.value = '';
    if (fichier === null) {
      return;
    }
    this.etat.set({ ...ETAT_INITIAL, fichier });
    this.activer.emit();
    void lireImage(fichier).then((apercu) => {
      if (this.etat().fichier === fichier) {
        this.etat.set(
          apercu === null
            ? { ...ETAT_INITIAL, erreur: MESSAGE_IMAGE_ILLISIBLE }
            : { fichier, apercu, erreur: null },
        );
      }
    });
  }

  protected annuler(): void {
    this.etat.set(ETAT_INITIAL);
  }

  protected envoyer(): void {
    const { fichier, apercu } = this.etat();
    if (fichier === null || apercu === null) {
      return;
    }
    this.executer(this.administrationApi.envoyerLogo(this.course().id, fichier), 'enregistré');
  }

  protected supprimer(): void {
    this.etat.set(ETAT_INITIAL);
    this.activer.emit();
    const course = this.course();
    const suppression = this.administrationApi.supprimerLogo(course.id).pipe(map(() => course));
    this.executer(suppression, 'supprimé');
  }

  /** Nouveau jeton CSRF puis requête ; boutons désactivés jusqu'à la réponse. */
  private executer(requete: Observable<CourseReponse>, action: 'enregistré' | 'supprimé'): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.envoiEnCours.set(true);
    this.etat.update((etat) => ({ ...etat, erreur: null }));
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => requete),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ nom }) => {
          this.etat.set(ETAT_INITIAL);
          this.actualiser.emit(`Le logo de la course ${nom} a été ${action}.`);
        },
        error: (erreur: unknown) => this.traiterErreur(erreur),
      });
  }

  /** Message dans le bloc ; le fichier choisi est conservé sauf si la Course n'est plus éditable. */
  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN_GESTION_COURSES)) {
      return;
    }
    const erreurs = interpreterErreurFormulaire(erreur, ERREURS_LOGO, []);
    // Tout 413 vaut « trop volumineux », y compris celui de Caddy, sans `ProblemDetail`.
    const tropVolumineux =
      erreur instanceof HttpErrorResponse && erreur.status === HttpStatusCode.PayloadTooLarge;
    const message = tropVolumineux
      ? MESSAGE_LOGO_TROP_VOLUMINEUX
      : (erreurs.generale ?? MESSAGE_SERVICE_INDISPONIBLE);
    if (estCourseNonEditable(erreur)) {
      this.etat.set({ ...ETAT_INITIAL, erreur: message });
      this.actualiser.emit(null);
      return;
    }
    this.etat.update((etat) => ({ ...etat, erreur: message }));
    if (erreurs.jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }
}
