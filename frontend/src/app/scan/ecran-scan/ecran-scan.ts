import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TimeoutError, finalize, switchMap, timeout } from 'rxjs';

import {
  LIBELLES_STATUT_COURSE,
  PASTILLES_STATUT_COURSE,
} from '../../administration/courses/course';
import { RefusAccesService } from '../../comptes/refus-acces.service';
import { CsrfService } from '../../partage/csrf.service';
import { MESSAGE_SERVICE_INDISPONIBLE, lireProbleme } from '../../partage/probleme';
import { BenevoleApiService } from '../benevole-api.service';
import { CameraScan } from '../camera-scan/camera-scan';
import { CourseBenevoleReponse } from '../course-benevole';
import { tirerIdentifiantScan } from '../identifiant-scan';
import { ErreurScan, PassageReponse } from '../passage';
import { RefusScan, lireRefusScan } from '../refus-scan';

const MESSAGE_INJOIGNABLE =
  "Le serveur est injoignable. Le passage n'est peut-être pas enregistré : validez de nouveau avec le même jeton.";
const MESSAGE_SAISIE_VIDE = 'Saisissez le jeton du coureur.';
/** Au-delà, l'envoi est considéré comme perdu (même traitement qu'une coupure réseau). */
const DELAI_ENVOI_MILLISECONDES = 15_000;

/** Un jeton lu à la caméra ne touche pas au champ (pas de clavier virtuel qui s'ouvre). */
type OrigineJeton = 'saisie' | 'camera';

/** Succès, refus définitif de l'API, ou panne sans réponse définitive (message seul). */
type ResultatScan =
  | { type: 'succes'; passage: PassageReponse }
  | { type: 'refus'; refus: RefusScan }
  | { type: 'erreur'; message: string };

/** Envoi sans réponse définitive : son `identifiantScan` est réutilisé pour le même jeton. */
interface TentativeEnAttente {
  jeton: string;
  identifiantScan: string;
}

/**
 * Scan d'un passage par un bénévole : jeton QR d'un coureur saisi ou lu à la caméra, et retour du serveur.
 * Le serveur décide de tout (Boucle, refus) ; le statut lu au chargement ne sert qu'à l'affichage.
 */
@Component({
  selector: 'app-ecran-scan',
  imports: [ReactiveFormsModule, RouterLink, CameraScan],
  templateUrl: './ecran-scan.html',
  styleUrls: [
    '../../partage/page-carte.css',
    '../../comptes/formulaire-compte.css',
    './ecran-scan.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EcranScan implements OnInit {
  private readonly benevoleApi = inject(BenevoleApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly champ = viewChild<ElementRef<HTMLInputElement>>('champJeton');

  /** Paramètre de route `:courseId`. */
  readonly courseId = input.required<string>();

  protected readonly libellesStatut = LIBELLES_STATUT_COURSE;
  protected readonly pastillesStatut = PASTILLES_STATUT_COURSE;
  /** `undefined` pendant le chargement, `null` si la Course n'est pas dans la liste du bénévole. */
  protected readonly course = signal<CourseBenevoleReponse | null | undefined>(undefined);
  protected readonly erreurChargement = signal(false);
  protected readonly formulaire = new FormGroup({
    jeton: new FormControl('', { nonNullable: true }),
  });
  protected readonly envoiEnCours = signal(false);
  protected readonly erreurSaisie = signal<string | null>(null);
  protected readonly resultat = signal<ResultatScan | null>(null);
  private tentative: TentativeEnAttente | null = null;

  ngOnInit(): void {
    this.benevoleApi
      .listerMesCourses()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (courses) =>
          this.course.set(courses.find((course) => course.id === this.courseId()) ?? null),
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, this.ecran())) {
            this.erreurChargement.set(true);
          }
        },
      });
  }

  /** Une nouvelle saisie efface le résultat précédent. */
  protected effacerResultat(): void {
    this.resultat.set(null);
    this.erreurSaisie.set(null);
  }

  protected enregistrer(): void {
    if (this.envoiEnCours()) {
      return;
    }
    const saisie = this.formulaire.controls.jeton.value;
    const jetonNettoye = saisie.replace(/\s+/g, '');
    this.effacerResultat();
    if (jetonNettoye === '') {
      this.erreurSaisie.set(MESSAGE_SAISIE_VIDE);
      this.champ()?.nativeElement.focus();
      return;
    }
    this.envoyer(saisie, jetonNettoye, 'saisie');
  }

  /** Jeton lu à la caméra : même envoi que la saisie ; le résultat précédent reste jusqu'au suivant. */
  protected scannerJeton(jeton: string): void {
    if (!this.envoiEnCours()) {
      this.envoyer(jeton, jeton, 'camera');
    }
  }

  private envoyer(jetonQr: string, jetonNettoye: string, origine: OrigineJeton): void {
    const identifiantScan = this.identifiantPour(jetonNettoye);
    this.envoiEnCours.set(true);
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() =>
          this.benevoleApi.enregistrerPassage(this.courseId(), {
            jetonQr,
            identifiantScan,
          }),
        ),
        timeout(DELAI_ENVOI_MILLISECONDES),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (passage) => this.afficherSucces(passage, origine),
        error: (erreur: unknown) => this.afficherErreur(erreur, origine),
      });
  }

  /** Même jeton sans réponse définitive : même identifiant (l'API est idempotente). */
  private identifiantPour(jeton: string): string {
    if (this.tentative?.jeton !== jeton) {
      this.tentative = { jeton, identifiantScan: tirerIdentifiantScan() };
    }
    return this.tentative.identifiantScan;
  }

  private afficherSucces(passage: PassageReponse, origine: OrigineJeton): void {
    this.tentative = null;
    this.resultat.set({ type: 'succes', passage });
    if (origine === 'saisie') {
      this.formulaire.reset();
      this.champ()?.nativeElement.focus();
    }
  }

  private afficherErreur(erreur: unknown, origine: OrigineJeton): void {
    if (estSansReponseDefinitive(erreur)) {
      this.afficherEchec({ type: 'erreur', message: MESSAGE_INJOIGNABLE }, origine);
      return;
    }
    this.tentative = null;
    const probleme = lireProbleme(erreur) as ErreurScan | null;
    if (
      probleme?.code !== 'BENEVOLE_NON_AFFECTE' &&
      this.refusAcces.rediriger(erreur, this.ecran())
    ) {
      return;
    }
    this.afficherEchec(
      probleme
        ? { type: 'refus', refus: lireRefusScan(probleme) }
        : { type: 'erreur', message: MESSAGE_SERVICE_INDISPONIBLE },
      origine,
    );
  }

  /** Saisie : le jeton reste dans le champ, sélectionné, pour être corrigé ou validé de nouveau. */
  private afficherEchec(resultat: ResultatScan, origine: OrigineJeton): void {
    this.resultat.set(resultat);
    if (origine === 'camera') {
      return;
    }
    const champ = this.champ()?.nativeElement;
    champ?.focus();
    champ?.select();
  }

  private ecran(): string {
    return `/benevole/courses/${this.courseId()}/scan`;
  }
}

/** Coupure réseau, délai dépassé ou erreur serveur : le passage a pu être enregistré ou non. */
function estSansReponseDefinitive(erreur: unknown): boolean {
  if (erreur instanceof TimeoutError) {
    return true;
  }
  return (
    erreur instanceof HttpErrorResponse &&
    (erreur.status === 0 || erreur.status >= HttpStatusCode.InternalServerError)
  );
}
