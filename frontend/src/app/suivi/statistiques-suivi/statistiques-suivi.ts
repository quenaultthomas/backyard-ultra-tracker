import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { SuiviCourse } from '../suivi-course';
import { formaterAllure, formaterDenivele, formaterKilometres } from './formats-statistiques';

/**
 * Section publique « Statistiques » d'une Course démarrée : paramètres de Boucle, Boucles
 * terminées, cumuls et allure moyenne, tels que calculés par le serveur (seulement formatés ici).
 */
@Component({
  selector: 'app-statistiques-suivi',
  templateUrl: './statistiques-suivi.html',
  styleUrls: ['../../comptes/formulaire-compte.css', './statistiques-suivi.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatistiquesSuivi {
  /** Dernier instantané du suivi (Course `EN_COURS` avec heure de départ ou `TERMINEE`). */
  readonly suivi = input.required<SuiviCourse>();

  protected readonly parametresBoucle = computed(() => {
    const s = this.suivi();
    return `${formaterKilometres(s.distanceBoucleMetres)}, ${formaterDenivele(s.denivelePositifBoucleMetres)}`;
  });
  protected readonly distanceCumulee = computed(() =>
    formaterKilometres(this.suivi().distanceCumuleeMetres),
  );
  protected readonly deniveleCumule = computed(() =>
    formaterDenivele(this.suivi().denivelePositifCumuleMetres),
  );
  protected readonly allure = computed(() =>
    formaterAllure(this.suivi().allureSecondesParKilometre),
  );
}
