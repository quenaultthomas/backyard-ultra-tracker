import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { LIBELLES_MOTIF_ABANDON } from '../../administration/courses/pilotage-course';
import { SuiviCourse } from '../suivi-course';

/**
 * Sections publiques « Vainqueur(s) », « Coureurs en course » et « Abandons » d'une Course
 * démarrée : listes affichées dans l'ordre et selon la répartition décidés par le serveur.
 */
@Component({
  selector: 'app-coureurs-suivi',
  templateUrl: './coureurs-suivi.html',
  styleUrls: ['../../comptes/formulaire-compte.css', './coureurs-suivi.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CoureursSuivi {
  /** Dernier instantané du suivi (Course `EN_COURS` ou `TERMINEE`). */
  readonly suivi = input.required<SuiviCourse>();

  protected readonly libellesMotif = LIBELLES_MOTIF_ABANDON;
}
