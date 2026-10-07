import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { LIBELLES_STATUT_INSCRIPTION } from '../../../../inscriptions/mon-inscription';
import { InscritsCourseReponse } from '../../course';

/** Section « Inscrits » de la fiche d'une Course : lecture seule, valeurs telles que renvoyées par l'API. */
@Component({
  selector: 'app-inscrits-course',
  templateUrl: './inscrits-course.html',
  styleUrls: [
    '../../../../partage/page-carte.css',
    '../../../../comptes/formulaire-compte.css',
    './inscrits-course.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InscritsCourse {
  /** `null` tant que la réponse n'est pas arrivée. */
  readonly inscrits = input.required<InscritsCourseReponse | null>();
  /** Échec de lecture (5xx, réseau). */
  readonly erreur = input.required<boolean>();

  protected readonly libellesStatut = LIBELLES_STATUT_INSCRIPTION;

  protected readonly compteur = computed(() => {
    const reponse = this.inscrits();
    if (!reponse) {
      return '';
    }
    const { nombreInscrits, nombreMaxParticipants } = reponse;
    return `${nombreInscrits} ${nombreInscrits > 1 ? 'inscrits' : 'inscrit'} sur ${nombreMaxParticipants}`;
  });

  protected readonly places = computed(() => {
    const reponse = this.inscrits();
    if (!reponse) {
      return '';
    }
    const { complete, placesRestantes } = reponse;
    if (complete || placesRestantes === 0) {
      return 'Course complète';
    }
    return placesRestantes > 1
      ? `${placesRestantes} places restantes`
      : `${placesRestantes} place restante`;
  });
}
