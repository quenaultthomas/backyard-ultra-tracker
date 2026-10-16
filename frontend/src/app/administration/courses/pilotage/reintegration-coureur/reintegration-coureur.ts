import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { AdministrationApiService } from '../../../administration-api.service';
import { InscritEnAbandon, Reintegration, libelleBouclesCorrigees } from '../../pilotage-course';
import { ConfirmationPilotage } from '../confirmation-pilotage';
import { RefusActionPilotage } from '../refus-action-pilotage';

const MESSAGE_PLUS_EN_ABANDON = "Ce coureur n'est plus en abandon.";

/** Refus du serveur qui ferment le panneau. */
const REFUS_REINTEGRATION: Readonly<Record<string, RefusActionPilotage>> = {
  INSCRIPTION_NON_EN_ABANDON: { message: MESSAGE_PLUS_EN_ABANDON, rafraichir: true },
  COURSE_TERMINEE: {
    message: "La course est terminée : aucune réintégration n'est possible.",
    rafraichir: true,
  },
  TEMPS_DE_COURSE_ECOULE: {
    message: "Le temps de la dernière boucle est écoulé : la réintégration n'est plus possible.",
    rafraichir: true,
  },
  COURSE_NON_DEMARREE: { message: "La course n'est pas démarrée.", rafraichir: true },
  HEURE_DE_DEPART_INCONNUE: {
    message: "L'heure de départ de la course est inconnue.",
    rafraichir: false,
  },
  INSCRIPTION_INTROUVABLE: { message: 'Ce coureur est introuvable.', rafraichir: false },
};

/**
 * Réintégration d'un coureur en abandon depuis le pilotage : confirmation en ligne (socle
 * {@link ConfirmationPilotage}). Le serveur décide de tout (éligibilité, Boucles corrigées) ;
 * l'écran affiche le nombre de Boucles corrigées qu'il renvoie.
 */
@Component({
  selector: 'app-reintegration-coureur',
  templateUrl: './reintegration-coureur.html',
  styleUrls: ['../../../../partage/confirmation-en-ligne.css', '../confirmation-pilotage.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { tabindex: '-1' },
})
export class ReintegrationCoureur extends ConfirmationPilotage<InscritEnAbandon> {
  private readonly administrationApi = inject(AdministrationApiService);

  /** Reçoit le focus à l'annulation si le bouton « Réintégrer » de la ligne a disparu. */
  readonly repliFocusAnnulation = input.required<HTMLElement>();

  protected readonly refus = REFUS_REINTEGRATION;
  protected readonly cible = computed(() =>
    this.rafraichissement
      .instantane()
      ?.inscritsEnAbandon.find(({ inscriptionId }) => inscriptionId === this.cibleId()),
  );

  constructor() {
    super();
    // Coureur visé absent d'un nouvel instantané (réintégré par un autre admin).
    this.fermerSiCibleAbsente(MESSAGE_PLUS_EN_ABANDON);
  }

  /** `POST` sans corps ; un seul envoi à la fois. */
  protected confirmer(): void {
    const cible = this.cible();
    if (this.envoiEnCours() || cible === undefined) {
      return;
    }
    this.erreur.set(null);
    this.envoyer(
      () => this.administrationApi.reintegrerCoureur(this.courseId(), cible.inscriptionId),
      (reintegration) => this.reussir(messageReintegration(reintegration, cible)),
    );
  }

  protected override repliFocus(): HTMLElement {
    return this.repliFocusAnnulation();
  }
}

function messageReintegration(reintegration: Reintegration, cible: InscritEnAbandon): string {
  const boucles = libelleBouclesCorrigees(reintegration.bouclesCorrigees.length);
  return `Coureur n° ${reintegration.dossard} ${cible.pseudo} réintégré : ${boucles}.`;
}
