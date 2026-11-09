import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { LIBELLES_MOTIF_ABANDON } from '../../administration/courses/pilotage-course';
import { palierTableau } from '../palier-tableau';
import { PolaroidCoureur } from '../polaroid-coureur/polaroid-coureur';
import { SuiviCourse } from '../suivi-course';

/**
 * Sections publiques « Vainqueur(s) », « Coureurs en course » et « Abandons » d'une Course
 * démarrée : listes affichées dans l'ordre et selon la répartition décidés par le serveur ; coureurs
 * en course en polaroïds épinglés sur un tableau de liège, vainqueurs en grands polaroïds. Le
 * contenu projeté (statistiques) s'insère après le bandeau vainqueur, avant « Coureurs en course ».
 */
@Component({
  selector: 'app-coureurs-suivi',
  imports: [PolaroidCoureur],
  templateUrl: './coureurs-suivi.html',
  styleUrls: ['../../comptes/formulaire-compte.css', './coureurs-suivi.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CoureursSuivi {
  /** Dernier instantané du suivi (Course `EN_COURS` ou `TERMINEE`). */
  readonly suivi = input.required<SuiviCourse>();

  protected readonly libellesMotif = LIBELLES_MOTIF_ABANDON;
  /** Taille des polaroïds selon l'effectif reçu ; un changement de palier ne recrée aucun polaroïd. */
  protected readonly palier = computed(() => palierTableau(this.suivi().coureursEnCourse.length));
}
