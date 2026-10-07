import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { finalize, switchMap } from 'rxjs';

import { CompteReponse } from '../../../comptes/compte';
import { ErreursSpecifiques, interpreterErreurFormulaire } from '../../../comptes/erreurs-compte';
import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { CsrfService } from '../../../partage/csrf.service';
import { MESSAGE_SERVICE_INDISPONIBLE, lireProbleme } from '../../../partage/probleme';
import { AdministrationApiService } from '../../administration-api.service';
import { CHAMPS_NOMBRE, formaterDateCourse } from '../champs-course';
import { FicheCourseReponse, InscritsCourseReponse, LIBELLES_STATUT_COURSE } from '../course';
import {
  ECRAN_GESTION_COURSES,
  ETAT_MESSAGE_ERREUR,
  MESSAGE_COURSE_INTROUVABLE,
} from '../erreurs-course';
import { InscritsCourse } from './inscrits-course/inscrits-course';

const ERREURS_AFFECTATION: ErreursSpecifiques<never> = {
  VALIDATION_ECHOUEE: ({ erreurs }) => ({
    generale: erreurs?.some(({ code }) => code === 'BENEVOLE_INCONNU')
      ? "Un des bénévoles choisis n'existe plus. Rechargez la page."
      : MESSAGE_SERVICE_INDISPONIBLE,
  }),
  COURSE_TERMINEE: {
    generale: 'La course est terminée : ses bénévoles ne peuvent plus être modifiés.',
  },
};

/** Bénévole proposé à l'affectation : un Compte `BENEVOLE` connu, ou un identifiant inconnu. */
interface LigneBenevole {
  readonly id: string;
  readonly libelle: string;
}

/** Fiche d'une Course (admins et admin master) : rappel de la Course et bénévoles affectés. */
@Component({
  selector: 'app-fiche-course',
  imports: [RouterLink, InscritsCourse],
  templateUrl: './fiche-course.html',
  styleUrls: [
    '../../../partage/page-carte.css',
    '../../../comptes/formulaire-compte.css',
    './fiche-course.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FicheCourse implements OnInit {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  /** Paramètre de route `:id`. */
  readonly id = input.required<string>();

  protected readonly champsNombre = CHAMPS_NOMBRE;
  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly formaterDate = formaterDateCourse;

  /** `null` tant que la fiche n'est pas chargée. */
  protected readonly fiche = signal<FicheCourseReponse | null>(null);
  protected readonly introuvable = signal(false);
  /** Échec de lecture de la fiche autre qu'une Course introuvable (5xx, réseau). */
  protected readonly erreurChargement = signal(false);
  /** Tous les Comptes `BENEVOLE`, `null` tant que la liste n'est pas chargée. */
  protected readonly benevoles = signal<CompteReponse[] | null>(null);
  protected readonly erreurBenevoles = signal(false);
  /** Inscrits de la Course, `null` tant que la réponse n'est pas arrivée. */
  protected readonly inscrits = signal<InscritsCourseReponse | null>(null);
  protected readonly erreurInscrits = signal(false);
  /** Identifiants cochés, non encore enregistrés. */
  protected readonly coches = signal<ReadonlySet<string>>(new Set());
  protected readonly envoiEnCours = signal(false);
  protected readonly messageSucces = signal<string | null>(null);
  protected readonly erreur = signal<string | null>(null);

  /** Le statut renvoyé par l'API décide de l'affichage (l'API reste la référence). */
  protected readonly verrouillee = computed(() => this.fiche()?.statut === 'TERMINEE');

  /** Bénévoles dans l'ordre de l'API, puis identifiants affectés absents de cette liste. */
  protected readonly lignes = computed<LigneBenevole[]>(() => {
    const benevoles = this.benevoles() ?? [];
    const connus = new Set(benevoles.map(({ id }) => id));
    const inconnus = (this.fiche()?.benevoleIds ?? []).filter((id) => !connus.has(id));
    return [
      ...benevoles.map(({ id, pseudo }) => ({ id, libelle: pseudo })),
      ...inconnus.map((id) => ({ id, libelle: 'Bénévole inconnu' })),
    ];
  });

  protected readonly compteur = computed(() => {
    const nombre = this.coches().size;
    return nombre > 1 ? `${nombre} bénévoles affectés` : `${nombre} bénévole affecté`;
  });

  ngOnInit(): void {
    this.chargerFiche();
    this.administrationApi
      .listerBenevoles()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (benevoles) => this.benevoles.set(benevoles),
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, this.ecran())) {
            this.erreurBenevoles.set(true);
          }
        },
      });
    this.chargerInscrits();
  }

  protected basculer(id: string, coche: boolean): void {
    this.coches.update((coches) => {
      const suivantes = new Set(coches);
      if (coche) {
        suivantes.add(id);
      } else {
        suivantes.delete(id);
      }
      return suivantes;
    });
  }

  /** Nouveau jeton CSRF puis envoi de toutes les cases cochées. */
  protected enregistrer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.messageSucces.set(null);
    const requete = { benevoleIds: [...this.coches()] };
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => this.administrationApi.affecterBenevoles(this.id(), requete)),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (fiche) => {
          this.afficher(fiche);
          this.messageSucces.set(`Les bénévoles de la course ${fiche.nom} ont été enregistrés.`);
        },
        error: (erreur: unknown) => this.traiterErreurEnvoi(erreur),
      });
  }

  private chargerFiche(): void {
    this.administrationApi
      .lireFicheCourse(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (fiche) => this.afficher(fiche),
        error: (erreur: unknown) => {
          if (this.refusAcces.rediriger(erreur, this.ecran())) {
            return;
          }
          (estIntrouvable(erreur) ? this.introuvable : this.erreurChargement).set(true);
        },
      });
  }

  /** Lecture unique, indépendante de la fiche : un échec n'affecte que la section « Inscrits ». */
  private chargerInscrits(): void {
    this.administrationApi
      .listerInscrits(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (inscrits) => this.inscrits.set(inscrits),
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, this.ecran())) {
            this.erreurInscrits.set(true);
          }
        },
      });
  }

  private afficher(fiche: FicheCourseReponse): void {
    this.fiche.set(fiche);
    this.coches.set(new Set(fiche.benevoleIds));
  }

  /** Message dans `fiche-erreur` ; les cases sont conservées sauf si la fiche est rechargée. */
  private traiterErreurEnvoi(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, this.ecran())) {
      return;
    }
    if (estIntrouvable(erreur)) {
      void this.router.navigateByUrl(ECRAN_GESTION_COURSES, {
        state: { [ETAT_MESSAGE_ERREUR]: MESSAGE_COURSE_INTROUVABLE },
      });
      return;
    }
    const erreurs = interpreterErreurFormulaire(erreur, ERREURS_AFFECTATION, []);
    this.erreur.set(erreurs.generale ?? MESSAGE_SERVICE_INDISPONIBLE);
    if (lireProbleme(erreur)?.code === 'COURSE_TERMINEE') {
      this.chargerFiche();
    }
    if (erreurs.jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }

  /** Adresse de la fiche, pour le retour après connexion. */
  private ecran(): string {
    return `${ECRAN_GESTION_COURSES}/${this.id()}`;
  }
}

/** 404 (`COURSE_INTROUVABLE`) : la Course n'existe pas ou plus. */
function estIntrouvable(erreur: unknown): boolean {
  return erreur instanceof HttpErrorResponse && erreur.status === HttpStatusCode.NotFound;
}
