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
import { filter } from 'rxjs';

import {
  LIBELLES_STATUT_COURSE,
  PASTILLES_STATUT_COURSE,
} from '../../administration/courses/course';
import { RefusAccesService } from '../../comptes/refus-acces.service';
import { BenevoleApiService } from '../benevole-api.service';
import { CameraScan } from '../camera-scan/camera-scan';
import { CourseBenevoleReponse } from '../course-benevole';
import { EtatFile } from '../etat-file/etat-file';
import { EvenementScan, FileScansService } from '../file-scans/file-scans.service';
import { PassageReponse } from '../passage';
import { RefusScan } from '../refus-scan';

const MESSAGE_SAISIE_VIDE = 'Saisissez le jeton du coureur.';

/** Retour du dernier scan : première tentative, gardé sur l'appareil, ou réponse définitive. */
type RetourScan =
  | { type: 'envoi' }
  | { type: 'attente' }
  | { type: 'succes'; passage: PassageReponse }
  | { type: 'refus'; refus: RefusScan };

/** Dernier scan de l'écran ; `saisie` : valeur du champ, rendue après un refus en direct. */
interface DernierScan {
  identifiantScan: string;
  saisie: string | null;
}

/**
 * Scan d'un passage par un bénévole : jeton QR d'un coureur saisi ou lu à la caméra, mis dans la
 * file locale puis envoyé. Le serveur décide de tout (Boucle, refus) ; le statut lu au chargement
 * ne sert qu'à l'affichage.
 */
@Component({
  selector: 'app-ecran-scan',
  imports: [ReactiveFormsModule, RouterLink, CameraScan, EtatFile],
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
  private readonly fileScans = inject(FileScansService);
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
  protected readonly erreurSaisie = signal<string | null>(null);
  protected readonly retour = signal<RetourScan | null>(null);
  private dernierScan: DernierScan | null = null;

  ngOnInit(): void {
    this.benevoleApi
      .listerMesCourses()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (courses) =>
          this.course.set(courses.find((course) => course.id === this.courseId()) ?? null),
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, `/benevole/courses/${this.courseId()}/scan`)) {
            this.erreurChargement.set(true);
          }
        },
      });
    this.fileScans.evenements$
      .pipe(
        filter((evenement) => evenement.courseId === this.courseId()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((evenement) => this.suivre(evenement));
  }

  /** Une nouvelle saisie efface le retour précédent. */
  protected effacerResultat(): void {
    this.retour.set(null);
    this.erreurSaisie.set(null);
    this.fileScans.effacerMessageEnvoyes();
  }

  /** Le champ est libéré dès la mise en file : la saisie n'attend jamais l'envoi. */
  protected enregistrer(): void {
    const saisie = this.formulaire.controls.jeton.value;
    if (saisie.replace(/\s+/g, '') === '') {
      this.erreurSaisie.set(MESSAGE_SAISIE_VIDE);
      this.champ()?.nativeElement.focus();
      return;
    }
    this.effacerResultat();
    this.mettreEnFile(saisie, saisie);
    this.formulaire.reset();
    this.champ()?.nativeElement.focus();
  }

  /** Jeton lu à la caméra : même mise en file que la saisie, sans toucher au champ. */
  protected scannerJeton(jeton: string): void {
    this.effacerResultat();
    this.mettreEnFile(jeton, null);
  }

  /**
   * « Envoi… » s'affiche aussitôt ; « gardé sur l'appareil » seulement une fois l'entrée écrite,
   * si ce scan pilote encore la zone et que rien d'autre ne l'a remplie entre-temps.
   */
  private mettreEnFile(jetonQr: string, saisie: string | null): void {
    const ajout = this.fileScans.ajouter(this.courseId(), jetonQr);
    if (!ajout) {
      return;
    }
    const { scan, ecrit } = ajout;
    this.dernierScan = { identifiantScan: scan.identifiantScan, saisie };
    if (scan.etat === 'EN_ENVOI') {
      this.retour.set({ type: 'envoi' });
      return;
    }
    void ecrit.then(() => {
      if (this.dernierScan?.identifiantScan === scan.identifiantScan && this.retour() === null) {
        this.retour.set({ type: 'attente' });
      }
    });
  }

  /**
   * Seul le dernier scan pilote la zone de retour ; tout refus la remplace. Un refus en différé
   * (entrée qui a été en attente) y reste : le dernier scan ne pilote plus la zone ensuite.
   */
  private suivre(evenement: EvenementScan): void {
    const estDernier = evenement.identifiantScan === this.dernierScan?.identifiantScan;
    if (evenement.type === 'refus') {
      this.retour.set({ type: 'refus', refus: evenement.refus });
      if (estDernier && evenement.enDirect) {
        this.rendreLaSaisie(this.dernierScan?.saisie ?? null);
      }
      if (estDernier || !evenement.enDirect) {
        this.dernierScan = null;
      }
    } else if (!estDernier) {
      return;
    } else if (evenement.type === 'succes') {
      this.retour.set({ type: 'succes', passage: evenement.passage });
      this.dernierScan = null;
    } else {
      this.retour.set({ type: 'attente' });
    }
  }

  /** Refus en direct d'une saisie : le jeton revient, sélectionné, si le champ est resté vide. */
  private rendreLaSaisie(saisie: string | null): void {
    const champ = this.champ()?.nativeElement;
    if (saisie === null || !champ || this.formulaire.controls.jeton.value !== '') {
      return;
    }
    this.formulaire.controls.jeton.setValue(saisie);
    champ.focus();
    champ.select();
  }
}
